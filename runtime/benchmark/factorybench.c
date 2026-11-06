/*
 * Factory benchmark: LD_PRELOADed into Factorio for Settings → Benchmark.
 *
 * Times every frame the game shows (glXSwapBuffers / eglSwapBuffers, whichever driver draws: Zink, Freedreno,
 * Gladio...) and learns which main menu scene is on screen from Factorio's log: the benchmark mod (Benchmark.java)
 * makes every vanilla menu simulation log "FACTORY_BENCH <name>" when it starts.
 * The mod has a single menu simulation whose save is a symlink (FACTORY_BENCH_SLOT); when a scene starts, the slot
 * is pointed at the next scene's save, so Factorio plays them in the list's order (it would pick at random).
 * Each scene is measured for
 * BENCH_MEASURE seconds after BENCH_WARMUP seconds; when all of them are measured the results go to
 * $FACTORY_BENCH_OUT/result.txt (+ frames.csv) and the game is asked to quit (SIGTERM).
 *
 * SDL looks glXSwapBuffers up with dlsym(), so dlsym() itself is wrapped (as MangoHud does).
 *
 * Every 250 ms it also samples the load: the CPU time of each of the game's threads, of the app (Gladio's server,
 * the X server: FACTORY_BENCH_APP_PID), each core's clock (and busy share when Android lets /proc/stat be read) and
 * the GPU's busy share and clock (Adreno's kgsl, MediaTek's ged, when readable); per scene in result.txt, every
 * sample in load.csv.
 *
 * Environment: FACTORY_BENCH_OUT (output folder), FACTORY_BENCH_LOG (factorio-current.log),
 * FACTORY_BENCH_NAMES (the scenes, comma separated, in report order), FACTORY_BENCH_SLOT (the mod's save symlink)
 * and FACTORY_BENCH_SAVES (each scene's save, comma separated, same order).
 */
#define _GNU_SOURCE
#include <dirent.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define BENCH_WARMUP_NS   1000000000ull    /* the scene's first second: loading hitches */
#define BENCH_MEASURE_NS 10000000000ull    /* then 10 s measured (the mod makes each scene 12 s long) */
#define NO_MARKER_NS    240000000000ull    /* no scene at all for 4 min: the mod didn't run */
#define TOTAL_LIMIT_NS 1500000000000ull    /* 25 min: give up on scenes Factorio never picked */
#define MAX_FRAMES (1 << 19)
#define MAX_SCENES 64
#define MAX_PLAYED 256

typedef void (*swap_fn)(void *display, unsigned long drawable);
typedef unsigned (*egl_swap_fn)(void *display, void *surface);
typedef void *(*gpa_fn)(const unsigned char *name);
typedef void *(*dlsym_fn)(void *handle, const char *name);

static dlsym_fn real_dlsym;
static swap_fn real_glx_swap;
static egl_swap_fn real_egl_swap;
static gpa_fn real_gpa, real_gpa_arb;

static uint64_t frames[MAX_FRAMES];
static atomic_uint frame_count;
static atomic_int watcher_started;
static int enabled = -1;

typedef struct {
    char name[64];
    char save[512];         /* its save, for the slot; "" when unknown */
    uint64_t start;         /* 0: not seen yet */
    uint64_t next_marker;   /* the next scene's start (any scene), 0 while none */
} Scene;

static Scene scenes[MAX_SCENES];
static int scene_count;
static char played[MAX_PLAYED][64];
static uint64_t played_at[MAX_PLAYED];
static int played_count;

/* ---- load samples ---- */

#define SAMPLE_NS 250000000ull
#define MAX_SAMPLES 8192    /* 34 min of 250 ms */
#define MAX_THREADS 96
#define MAX_CPUS 16

typedef struct {
    int tid;
    char name[20];
    unsigned long long ticks;
} Thread;

typedef struct {
    uint64_t t;
    float game, app;               /* % of one core */
    float gpu_busy, gpu_mhz;       /* -1: unknown */
    float core_mhz[MAX_CPUS];
    float core_busy[MAX_CPUS];     /* -1: unknown */
    float thread[MAX_THREADS];     /* % of one core, by Thread slot */
} Sample;

static Thread threads[MAX_THREADS];
static int thread_count;
static Sample *samples;
static int sample_count;
static int cpu_count;
static int have_core_busy;
static unsigned long long last_game_ticks, last_app_ticks;
static unsigned long long last_core_busy[MAX_CPUS], last_core_total[MAX_CPUS];
static uint64_t last_sample_t;
static int app_pid;

static uint64_t now_ns(void);

static int read_text(const char *path, char *buffer, size_t size) {
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return -1;
    ssize_t got = read(fd, buffer, size - 1);
    close(fd);
    if (got < 0) return -1;
    buffer[got] = 0;
    return (int)got;
}

/* utime + stime of a /proc stat file, in clock ticks; 0 when unreadable */
static unsigned long long stat_ticks(const char *path) {
    char text[1024];
    if (read_text(path, text, sizeof(text)) <= 0) return 0;
    char *p = strrchr(text, ')');
    if (!p) return 0;
    unsigned long long utime = 0, stime = 0;
    /* after "comm)": state(3) ppid pgrp session tty tpgid flags minflt cminflt majflt cmajflt utime(14) stime(15) */
    if (sscanf(p + 2, "%*c %*d %*d %*d %*d %*d %*u %*u %*u %*u %*u %llu %llu", &utime, &stime) != 2) return 0;
    return utime + stime;
}

/* the first number in a file (scaled), or -1 */
static float read_number(const char *path, int field, float scale) {
    char text[256];
    if (read_text(path, text, sizeof(text)) <= 0) return -1;
    char *p = text;
    for (int i = 0; ; i++) {
        while (*p && !(*p >= '0' && *p <= '9') && *p != '-') p++;
        if (!*p) return -1;
        char *end;
        double value = strtod(p, &end);
        if (end == p) return -1;
        if (i == field) return (float)(value * scale);
        p = end;
    }
}

static float gpu_busy(void) {
    float v = read_number("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", 0, 1);   /* Adreno: "42 %" */
    if (v < 0) v = read_number("/sys/kernel/ged/hal/gpu_utilization", 0, 1);       /* MediaTek */
    if (v < 0) v = read_number("/sys/class/misc/mali0/device/utilization", 0, 1);
    return v;
}

static float gpu_mhz(void) {
    float v = read_number("/sys/class/kgsl/kgsl-3d0/clock_mhz", 0, 1);
    if (v < 0) v = read_number("/sys/class/kgsl/kgsl-3d0/gpuclk", 0, 1e-6f);
    if (v < 0) v = read_number("/sys/kernel/ged/hal/current_freqency", 1, 1e-3f);   /* "index kHz" */
    if (v < 0) v = read_number("/sys/class/misc/mali0/device/cur_freq", 0, 1e-6f);
    return v;
}

static int thread_slot(int tid) {
    for (int i = 0; i < thread_count; i++) if (threads[i].tid == tid) return i;
    if (thread_count >= MAX_THREADS) return -1;
    Thread *th = &threads[thread_count];
    th->tid = tid;
    char path[64], name[64];
    snprintf(path, sizeof(path), "/proc/self/task/%d/comm", tid);
    if (read_text(path, name, sizeof(name)) > 0) {
        name[strcspn(name, "\n")] = 0;
        snprintf(th->name, sizeof(th->name), "%s", name);
    }
    else snprintf(th->name, sizeof(th->name), "%d", tid);
    if (tid == getpid()) snprintf(th->name, sizeof(th->name), "main");    /* the game's own thread: updates the map */
    th->ticks = 0;
    return thread_count++;
}

static void take_sample(uint64_t t) {
    if (!samples) {
        samples = calloc(MAX_SAMPLES, sizeof(Sample));
        if (!samples) return;
        cpu_count = (int)sysconf(_SC_NPROCESSORS_CONF);
        if (cpu_count < 1) cpu_count = 1;
        if (cpu_count > MAX_CPUS) cpu_count = MAX_CPUS;
        const char *pid = getenv("FACTORY_BENCH_APP_PID");
        app_pid = pid ? atoi(pid) : 0;
    }
    int first = last_sample_t == 0;
    double hz = (double)sysconf(_SC_CLK_TCK);
    double seconds = first ? 1 : (t - last_sample_t) / 1e9;
    Sample *sm = sample_count < MAX_SAMPLES ? &samples[sample_count] : NULL;

    unsigned long long game = stat_ticks("/proc/self/stat");
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/stat", app_pid);
    unsigned long long app = app_pid ? stat_ticks(path) : 0;

    if (sm) {
        memset(sm, 0, sizeof(*sm));
        sm->t = t;
        sm->game = first ? 0 : (float)(100.0 * (game - last_game_ticks) / hz / seconds);
        sm->app = first || !app ? -1 : (float)(100.0 * (app - last_app_ticks) / hz / seconds);
        sm->gpu_busy = gpu_busy();
        sm->gpu_mhz = gpu_mhz();
    }
    last_game_ticks = game;
    last_app_ticks = app;

    DIR *dir = opendir("/proc/self/task");
    if (dir) {
        struct dirent *entry;
        while ((entry = readdir(dir))) {
            int tid = atoi(entry->d_name);
            if (tid <= 0) continue;
            int slot = thread_slot(tid);
            if (slot < 0) continue;
            snprintf(path, sizeof(path), "/proc/self/task/%d/stat", tid);
            unsigned long long ticks = stat_ticks(path);
            if (sm && !first && threads[slot].ticks && ticks >= threads[slot].ticks)
                sm->thread[slot] = (float)(100.0 * (ticks - threads[slot].ticks) / hz / seconds);
            threads[slot].ticks = ticks;
        }
        closedir(dir);
    }

    for (int c = 0; c < cpu_count && sm; c++) {
        snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq", c);
        sm->core_mhz[c] = read_number(path, 0, 1e-3f);
        sm->core_busy[c] = -1;
    }
    static char text[8192];
    if (read_text("/proc/stat", text, sizeof(text)) > 0) {
        char *line = text;
        while ((line = strstr(line, "\ncpu"))) {
            line++;
            int c;
            unsigned long long v[8] = {0};
            if (sscanf(line, "cpu%d %llu %llu %llu %llu %llu %llu %llu %llu", &c, &v[0], &v[1], &v[2], &v[3], &v[4], &v[5], &v[6], &v[7]) < 5) continue;
            if (c < 0 || c >= cpu_count) continue;
            unsigned long long total = 0;
            for (int i = 0; i < 8; i++) total += v[i];
            unsigned long long busy = total - v[3] - v[4];
            if (sm && !first && total > last_core_total[c]) {
                sm->core_busy[c] = (float)(100.0 * (busy - last_core_busy[c]) / (total - last_core_total[c]));
                have_core_busy = 1;
            }
            last_core_busy[c] = busy;
            last_core_total[c] = total;
        }
    }
    last_sample_t = t;
    if (sm && !first) sample_count++;
}

static uint64_t now_ns(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}

static void resolve_dlsym(void) {
    if (real_dlsym) return;
    real_dlsym = (dlsym_fn)dlvsym(RTLD_NEXT, "dlsym", "GLIBC_2.34");
    if (!real_dlsym) real_dlsym = (dlsym_fn)dlvsym(RTLD_NEXT, "dlsym", "GLIBC_2.17");
}

static int is_enabled(void) {
    if (enabled < 0) enabled = getenv("FACTORY_BENCH_OUT") != NULL;
    return enabled;
}

static void *watcher(void *unused);

static void on_frame(void) {
    uint64_t t = now_ns();
    unsigned n = atomic_load_explicit(&frame_count, memory_order_relaxed);
    if (n < MAX_FRAMES) {
        frames[n] = t;
        atomic_store_explicit(&frame_count, n + 1, memory_order_release);
    }
    if (!atomic_exchange(&watcher_started, 1)) {
        /* after the first frame: by now Factorio has moved its old log away and writes the new one */
        pthread_t thread;
        if (pthread_create(&thread, NULL, watcher, NULL) == 0) pthread_detach(thread);
    }
}

static void bench_glXSwapBuffers(void *display, unsigned long drawable) {
    on_frame();
    if (real_glx_swap) real_glx_swap(display, drawable);
}

static unsigned bench_eglSwapBuffers(void *display, void *surface) {
    on_frame();
    return real_egl_swap ? real_egl_swap(display, surface) : 0;
}

static void *wrap(const char *name, void *found);

static void *bench_glXGetProcAddress(const unsigned char *name) {
    return wrap((const char *)name, real_gpa ? real_gpa(name) : NULL);
}

static void *bench_glXGetProcAddressARB(const unsigned char *name) {
    return wrap((const char *)name, real_gpa_arb ? real_gpa_arb(name) : NULL);
}

/* The real function in, ours out (when it is one we time). */
static void *wrap(const char *name, void *found) {
    if (!found || !name || !is_enabled()) return found;
    if (!strcmp(name, "glXSwapBuffers")) {
        if (found != (void *)bench_glXSwapBuffers) real_glx_swap = (swap_fn)found;
        return (void *)bench_glXSwapBuffers;
    }
    if (!strcmp(name, "eglSwapBuffers")) {
        if (found != (void *)bench_eglSwapBuffers) real_egl_swap = (egl_swap_fn)found;
        return (void *)bench_eglSwapBuffers;
    }
    if (!strcmp(name, "glXGetProcAddress")) {
        if (found != (void *)bench_glXGetProcAddress) real_gpa = (gpa_fn)found;
        return (void *)bench_glXGetProcAddress;
    }
    if (!strcmp(name, "glXGetProcAddressARB")) {
        if (found != (void *)bench_glXGetProcAddressARB) real_gpa_arb = (gpa_fn)found;
        return (void *)bench_glXGetProcAddressARB;
    }
    return found;
}

void *dlsym(void *handle, const char *name) {
    resolve_dlsym();
    if (!real_dlsym) return NULL;
    return wrap(name, real_dlsym(handle, name));
}

/* ---- the scenes ---- */

static void load_names(void) {
    const char *list = getenv("FACTORY_BENCH_NAMES");
    if (!list) return;
    const char *p = list;
    while (*p && scene_count < MAX_SCENES) {
        const char *end = strchr(p, ',');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        if (len > 0 && len < sizeof(scenes[0].name)) {
            memcpy(scenes[scene_count].name, p, len);
            scenes[scene_count].name[len] = 0;
            scene_count++;
        }
        if (!end) break;
        p = end + 1;
    }
}

/* FACTORY_BENCH_SAVES: the scenes' saves, in FACTORY_BENCH_NAMES's order. */
static void load_saves(void) {
    const char *list = getenv("FACTORY_BENCH_SAVES");
    if (!list) return;
    const char *p = list;
    for (int i = 0; i < scene_count && *p; i++) {
        const char *end = strchr(p, ',');
        size_t len = end ? (size_t)(end - p) : strlen(p);
        if (len < sizeof(scenes[0].save)) {
            memcpy(scenes[i].save, p, len);
            scenes[i].save[len] = 0;
        }
        if (!end) break;
        p = end + 1;
    }
}

/* Points the mod's save slot at the scene after `current` (in list order) that hasn't been shown yet. */
static void advance_slot(const Scene *current) {
    const char *slot = getenv("FACTORY_BENCH_SLOT");
    if (!slot || !current) return;
    int at = (int)(current - scenes);
    for (int k = 1; k < scene_count; k++) {
        const Scene *next = &scenes[(at + k) % scene_count];
        if (next->start || !next->save[0]) continue;
        char tmp[4096];
        snprintf(tmp, sizeof(tmp), "%s.next", slot);
        unlink(tmp);
        if (symlink(next->save, tmp) == 0 && rename(tmp, slot) == 0) return;
        unlink(tmp);
        return;
    }
}

static Scene *find_scene(const char *name) {
    for (int i = 0; i < scene_count; i++) if (!strcmp(scenes[i].name, name)) return &scenes[i];
    if (scene_count < MAX_SCENES) {    /* a scene the list didn't name: measured too */
        Scene *s = &scenes[scene_count++];
        snprintf(s->name, sizeof(s->name), "%s", name);
        return s;
    }
    return NULL;
}

static void on_marker(const char *name, uint64_t t) {
    /* the same scene logged twice in a row within seconds is one start */
    if (played_count > 0 && !strcmp(played[played_count - 1], name) && t - played_at[played_count - 1] < 5000000000ull) return;
    for (int i = 0; i < scene_count; i++) if (scenes[i].start && !scenes[i].next_marker) scenes[i].next_marker = t;
    if (played_count < MAX_PLAYED) {
        snprintf(played[played_count], sizeof(played[0]), "%s", name);
        played_at[played_count++] = t;
    }
    Scene *s = find_scene(name);
    if (s && !s->start) s->start = t;    /* a scene played again keeps its first measurement */
    advance_slot(s);
}

/* New lines of the log: "FACTORY_BENCH <name>" anywhere in a line. */
static void scan_log(int fd, char *pending, size_t *pending_len, size_t capacity) {
    for (;;) {
        ssize_t got = read(fd, pending + *pending_len, capacity - 1 - *pending_len);
        if (got <= 0) return;
        *pending_len += (size_t)got;
        pending[*pending_len] = 0;
        char *line = pending;
        char *nl;
        while ((nl = strchr(line, '\n'))) {
            *nl = 0;
            /* the message itself (": FACTORY_BENCH name"): Factorio also logs the script's source, where it reads
               "log('FACTORY_BENCH name')" */
            char *mark = strstr(line, ": FACTORY_BENCH ");
            if (mark) {
                char name[64];
                if (sscanf(mark + 16, "%63[A-Za-z0-9_-]", name) == 1) on_marker(name, now_ns());
            }
            line = nl + 1;
        }
        size_t rest = *pending_len - (size_t)(line - pending);
        memmove(pending, line, rest);
        *pending_len = rest;
        if (*pending_len >= capacity - 1) *pending_len = 0;    /* a line that long isn't ours */
    }
}

static int all_measured(uint64_t t) {
    if (scene_count == 0) return 0;
    for (int i = 0; i < scene_count; i++) {
        if (!scenes[i].start) return 0;
        if (t < scenes[i].start + BENCH_WARMUP_NS + BENCH_MEASURE_NS && !scenes[i].next_marker) return 0;
    }
    return 1;
}

typedef struct {
    unsigned frames;
    double avg_ms, fps, low1_fps, worst_ms, over33, over50;
} Stats;

static int compare_double(const void *a, const void *b) {
    double x = *(const double *)a, y = *(const double *)b;
    return x < y ? -1 : x > y;
}

/* Frame intervals ending inside [from, to). */
static int measure(uint64_t from, uint64_t to, unsigned count, Stats *out, double *intervals) {
    unsigned n = 0;
    double sum = 0;
    for (unsigned i = 1; i < count; i++) {
        if (frames[i] < from || frames[i] >= to || frames[i - 1] < from) continue;
        double ms = (frames[i] - frames[i - 1]) / 1e6;
        intervals[n++] = ms;
        sum += ms;
    }
    memset(out, 0, sizeof(*out));
    if (n == 0) return 0;
    out->frames = n;
    out->avg_ms = sum / n;
    out->fps = 1000.0 / out->avg_ms;
    unsigned over33 = 0, over50 = 0;
    for (unsigned i = 0; i < n; i++) {
        if (intervals[i] > 33.4) over33++;
        if (intervals[i] > 50.0) over50++;
    }
    out->over33 = 100.0 * over33 / n;
    out->over50 = 100.0 * over50 / n;
    qsort(intervals, n, sizeof(double), compare_double);
    out->worst_ms = intervals[n - 1];
    /* "1% low": the average frame rate of the slowest 1 % of frames */
    unsigned slow = n / 100 ? n / 100 : 1;
    double slow_sum = 0;
    for (unsigned i = n - slow; i < n; i++) slow_sum += intervals[i];
    out->low1_fps = 1000.0 * slow / slow_sum;
    return 1;
}

static void window_of(const Scene *s, uint64_t *from, uint64_t *to) {
    *from = s->start + BENCH_WARMUP_NS;
    *to = s->start + BENCH_WARMUP_NS + BENCH_MEASURE_NS;
    if (s->next_marker && s->next_marker < *to) *to = s->next_marker;
}

/* average of a sample field over a window; -1 when nothing known */
static float average(uint64_t from, uint64_t to, size_t offset) {
    double sum = 0;
    int n = 0;
    for (int i = 0; i < sample_count; i++) {
        if (samples[i].t < from || samples[i].t >= to) continue;
        float v = *(const float *)((const char *)&samples[i] + offset);
        if (v < 0) continue;
        sum += v;
        n++;
    }
    return n ? (float)(sum / n) : -1;
}

static void print_value(FILE *f, float v, const char *unit, int width) {
    char text[32];
    if (v < 0) snprintf(text, sizeof(text), "-");
    else snprintf(text, sizeof(text), "%.0f%s", v, unit);
    fprintf(f, " %*s", width, text);
}

static void write_load(FILE *f) {
    if (!samples || sample_count == 0) return;
    fprintf(f, "\nload, average over each scene's measured 10 s (cpu: 100%% = one core busy; '-' = Android doesn't let it be read)\n");
    fprintf(f, "%-34s %8s %8s %8s %8s  %s\n", "scene", "game cpu", "app cpu", "gpu busy", "gpu MHz", "busiest game threads");
    for (int i = 0; i < scene_count; i++) {
        const Scene *s = &scenes[i];
        if (!s->start) continue;
        uint64_t from, to;
        window_of(s, &from, &to);
        fprintf(f, "%-34s", s->name);
        print_value(f, average(from, to, offsetof(Sample, game)), "%", 8);
        print_value(f, average(from, to, offsetof(Sample, app)), "%", 8);
        print_value(f, average(from, to, offsetof(Sample, gpu_busy)), "%", 8);
        print_value(f, average(from, to, offsetof(Sample, gpu_mhz)), "", 8);
        /* the three busiest threads */
        float best[3] = {-1, -1, -1};
        int which[3] = {-1, -1, -1};
        for (int k = 0; k < thread_count; k++) {
            float v = average(from, to, offsetof(Sample, thread) + sizeof(float) * k);
            for (int b = 0; b < 3; b++) {
                if (v > best[b]) {
                    for (int m = 2; m > b; m--) { best[m] = best[m - 1]; which[m] = which[m - 1]; }
                    best[b] = v; which[b] = k;
                    break;
                }
            }
        }
        fprintf(f, " ");
        for (int b = 0; b < 3; b++) if (which[b] >= 0 && best[b] >= 1) fprintf(f, " %s %.0f%%", threads[which[b]].name, best[b]);
        fprintf(f, "\n");
    }
    fprintf(f, "\ncores, average clock in MHz%s:\n", have_core_busy ? " / busy %" : " (busy % not readable)");
    fprintf(f, "%-34s", "scene");
    for (int c = 0; c < cpu_count; c++) fprintf(f, " %*s%d", have_core_busy ? 8 : 4, "cpu", c);
    fprintf(f, "\n");
    for (int i = 0; i < scene_count; i++) {
        const Scene *s = &scenes[i];
        if (!s->start) continue;
        uint64_t from, to;
        window_of(s, &from, &to);
        fprintf(f, "%-34s", s->name);
        for (int c = 0; c < cpu_count; c++) {
            float mhz = average(from, to, offsetof(Sample, core_mhz) + sizeof(float) * c);
            if (have_core_busy) {
                float busy = average(from, to, offsetof(Sample, core_busy) + sizeof(float) * c);
                char text[32];
                snprintf(text, sizeof(text), "%.0f/%.0f%%", mhz < 0 ? 0 : mhz, busy < 0 ? 0 : busy);
                fprintf(f, " %9s", text);
            }
            else print_value(f, mhz, "", 5);
        }
        fprintf(f, "\n");
    }
}

/* every sample: load.csv */
static void write_load_csv(const char *out_dir, uint64_t first_frame) {
    if (!samples || sample_count == 0) return;
    char path[4096];
    snprintf(path, sizeof(path), "%s/load.csv", out_dir);
    FILE *f = fopen(path, "w");
    if (!f) return;
    fprintf(f, "time_s,scene,game_cpu,app_cpu,gpu_busy,gpu_mhz");
    for (int c = 0; c < cpu_count; c++) fprintf(f, ",cpu%d_mhz", c);
    if (have_core_busy) for (int c = 0; c < cpu_count; c++) fprintf(f, ",cpu%d_busy", c);
    for (int k = 0; k < thread_count; k++) fprintf(f, ",%s_%d", threads[k].name, threads[k].tid);
    fprintf(f, "\n");
    for (int i = 0; i < sample_count; i++) {
        const Sample *sm = &samples[i];
        const char *scene = "-";
        for (int p = 0; p < played_count; p++) if (played_at[p] <= sm->t) scene = played[p];
        fprintf(f, "%.2f,%s,%.0f,%.0f,%.0f,%.0f", (sm->t - first_frame) / 1e9, scene, sm->game, sm->app, sm->gpu_busy, sm->gpu_mhz);
        for (int c = 0; c < cpu_count; c++) fprintf(f, ",%.0f", sm->core_mhz[c]);
        if (have_core_busy) for (int c = 0; c < cpu_count; c++) fprintf(f, ",%.0f", sm->core_busy[c]);
        for (int k = 0; k < thread_count; k++) fprintf(f, ",%.0f", sm->thread[k]);
        fprintf(f, "\n");
    }
    fclose(f);
}

static void write_results(const char *out_dir, uint64_t first_frame, const char *note) {
    unsigned count = atomic_load_explicit(&frame_count, memory_order_acquire);
    double *intervals = malloc(sizeof(double) * (count ? count : 1));
    if (!intervals) return;
    char path[4096];

    snprintf(path, sizeof(path), "%s/result.txt.part", out_dir);
    FILE *f = fopen(path, "w");
    if (!f) { free(intervals); return; }
    if (note) fprintf(f, "%s\n\n", note);
    fprintf(f, "%-34s %7s %7s %8s %8s %6s %6s %6s\n", "scene", "avg fps", "1% low", "avg ms", "worst ms", ">33ms", ">50ms", "frames");
    double fps_sum = 0, low_sum = 0, worst = 0, ms_sum = 0;
    unsigned measured = 0, total_frames = 0;
    const Scene *slowest = NULL;
    double slowest_fps = 1e9;
    for (int i = 0; i < scene_count; i++) {
        const Scene *s = &scenes[i];
        Stats st;
        uint64_t from, to;
        if (!s->start) {
            fprintf(f, "%-34s %s\n", s->name, "not shown");
            continue;
        }
        window_of(s, &from, &to);
        if (!measure(from, to, count, &st, intervals)) {
            fprintf(f, "%-34s %s\n", s->name, "no frames");
            continue;
        }
        fprintf(f, "%-34s %7.1f %7.1f %8.2f %8.1f %5.1f%% %5.1f%% %6u\n", s->name, st.fps, st.low1_fps, st.avg_ms,
                st.worst_ms, st.over33, st.over50, st.frames);
        fps_sum += st.fps; low_sum += st.low1_fps; ms_sum += st.avg_ms; measured++; total_frames += st.frames;
        if (st.worst_ms > worst) worst = st.worst_ms;
        if (st.fps < slowest_fps) { slowest_fps = st.fps; slowest = s; }
    }
    if (measured) {
        /* the score: 100 x the scenes' average of (0.6 x avg fps + 0.4 x 1% low); 6000 = a steady 60 fps */
        double points = 0;
        for (int i = 0; i < scene_count; i++) {
            Stats st;
            uint64_t from, to;
            if (!scenes[i].start) continue;
            window_of(&scenes[i], &from, &to);
            if (measure(from, to, count, &st, intervals)) points += 0.6 * (st.fps > 60 ? 60 : st.fps) + 0.4 * (st.low1_fps > 60 ? 60 : st.low1_fps);
        }
        long score = (long)(100.0 * points / measured + 0.5);
        fprintf(f, "\nSCORE %ld%s\n", score, (int)measured == scene_count ? "" : " (incomplete: not every scene was measured)");
        fprintf(f, "\n%-34s %7.1f %7.1f %8.2f %8.1f %6s %6s %6u\n", "AVERAGE of scenes", fps_sum / measured,
                low_sum / measured, ms_sum / measured, worst, "", "", total_frames);
        fprintf(f, "scenes measured: %u of %d\n", measured, scene_count);
        if (slowest) fprintf(f, "slowest scene: %s (%.1f fps)\n", slowest->name, slowest_fps);
    }
    write_load(f);
    fprintf(f, "\norder they played in (s from the first frame):\n");
    for (int i = 0; i < played_count; i++)
        fprintf(f, "  %6.1f  %s\n", (played_at[i] - first_frame) / 1e9, played[i]);
    fclose(f);
    write_load_csv(out_dir, first_frame);
    char final_path[4096];
    snprintf(final_path, sizeof(final_path), "%s/result.txt", out_dir);
    rename(path, final_path);

    snprintf(path, sizeof(path), "%s/frames.csv", out_dir);
    f = fopen(path, "w");
    if (f) {
        fprintf(f, "scene,frame,time_ms,frame_ms\n");
        for (int i = 0; i < scene_count; i++) {
            const Scene *s = &scenes[i];
            if (!s->start) continue;
            uint64_t from, to;
            window_of(s, &from, &to);
            unsigned k = 0;
            for (unsigned j = 1; j < count; j++) {
                if (frames[j] < from || frames[j] >= to || frames[j - 1] < from) continue;
                fprintf(f, "%s,%u,%.3f,%.3f\n", s->name, k++, (frames[j] - s->start) / 1e6, (frames[j] - frames[j - 1]) / 1e6);
            }
        }
        fclose(f);
    }
    free(intervals);
}

/* For the status line on the game screen: "measured total scene new" (new: 1 = measured now, 0 = shown again). */
static void write_progress(const char *out_dir) {
    char path[4096], final_path[4096];
    snprintf(path, sizeof(path), "%s/progress.txt.part", out_dir);
    FILE *f = fopen(path, "w");
    if (!f) return;
    int seen = 0;
    for (int i = 0; i < scene_count; i++) if (scenes[i].start) seen++;
    const char *current = played_count ? played[played_count - 1] : "-";
    int fresh = 0;
    for (int i = 0; i < scene_count; i++)
        if (played_count && scenes[i].start == played_at[played_count - 1] && !strcmp(scenes[i].name, current)) fresh = 1;
    fprintf(f, "%d %d %s %d\n", seen, scene_count, current, fresh);
    fclose(f);
    snprintf(final_path, sizeof(final_path), "%s/progress.txt", out_dir);
    rename(path, final_path);
}

static void *watcher(void *unused) {
    (void)unused;
    const char *out_dir = getenv("FACTORY_BENCH_OUT");
    const char *log_path = getenv("FACTORY_BENCH_LOG");
    if (!out_dir || !log_path) return NULL;
    load_names();
    load_saves();
    uint64_t first_frame = frames[0];
    int fd = -1;
    static char pending[16384];
    size_t pending_len = 0;
    int last_played = -1;
    write_progress(out_dir);
    for (;;) {
        struct timespec pause = {0, 100 * 1000000};
        nanosleep(&pause, NULL);
        if (fd < 0) fd = open(log_path, O_RDONLY | O_CLOEXEC);
        if (fd >= 0) scan_log(fd, pending, &pending_len, sizeof(pending));
        if (played_count != last_played) {
            last_played = played_count;
            write_progress(out_dir);
        }
        uint64_t t = now_ns();
        if (t - last_sample_t >= SAMPLE_NS) take_sample(t);
        const char *note = NULL;
        if (all_measured(t)) note = NULL;
        else if (played_count == 0 && t - first_frame > NO_MARKER_NS)
            note = "No menu scene reported itself in Factorio's log: the benchmark mod didn't run.";
        else if (t - first_frame > TOTAL_LIMIT_NS)
            note = "Stopped after 25 minutes: not every scene was shown.";
        else continue;
        write_results(out_dir, first_frame, note);
        kill(getpid(), SIGTERM);    /* Factorio quits cleanly */
        return NULL;
    }
}
