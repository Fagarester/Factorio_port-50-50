#define GLADIO_REQUEST_NAMES 1 // names for the round trip stats
/*
 * Frames to the window (Factory, Termux:X11).
 *
 * Main path: the server gives every window three AHardwareBuffers, imported into Termux:X11 as pixmaps (DRI3,
 * modifier 1256 = rows bottom-up as GL draws them). Each frame the server draws into a free one, and it is shown
 * with Present: Termux:X11 flips to it or copies it on the GPU, nothing goes through the CPU.
 * Fallback (DRI3 refused): the server reads the frame into a memfd shared with Termux:X11 (MIT-SHM ShmAttachFd).
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <sys/mman.h>
#include <unistd.h>
#include <time.h>
#include <math.h>
#include <stdlib.h>
#include <X11/Xlib-xcb.h>
#include <xcb/xcb.h>
#include <xcb/shm.h>
#include <xcb/dri3.h>
#include <xcb/present.h>

#include "gladio.h"
#include "gl_context.h"
#include "present.h"

#define GLX_OPCODE_SET_FRAME_BUFFER 40
#define GLX_OPCODE_PRESENT_BUFFERS 41
#define MAX_WINDOWS 8
// 4: one on screen, one queued in Termux:X11, one finished, one being drawn: the game almost never waits for a free one
#define NUM_PRESENT_BUFFERS 4
// 1255: rows as GL leaves them in the AHardwareBuffer are already the right way up in Termux:X11 (1256 showed them upside down)
#define MODIFIER_AHARDWAREBUFFER 1255

typedef struct Presenter {
    GLXDrawable drawable;
    int width;
    int height;
    int depth;
    // shared memory fallback
    xcb_shm_seg_t segment;
    xcb_gcontext_t gc;
    // AHardwareBuffers shown with Present
    int numBuffers;
    xcb_pixmap_t pixmaps[NUM_PRESENT_BUFFERS];
    bool busy[NUM_PRESENT_BUFFERS];
    int current;
    uint32_t serial;
    uint32_t completedSerial; // last frame Termux:X11 has put on the screen (or skipped)
    uint32_t eventId;
    xcb_special_event_t* events;
} Presenter;

static Presenter presenters[MAX_WINDOWS];

/* every 5 s in the game's log: how long the game waited for a free buffer */
static uint64_t statsStart = 0;
static uint64_t statsVsyncTotal = 0; // time the swaps waited for the previous frame to be shown
static uint64_t lastVsyncWait = 0;
extern unsigned gl_state_skipped;
static int statsFrames = 0;
static uint64_t statsWaitTotal = 0, statsWaitMax = 0;
static int statsWaits = 0;

static uint64_t nowMicros(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000u + t.tv_nsec / 1000;
}

static void countFrame(uint64_t waited) {
    static int frameNumber = 0;
    frameNumber++;
    if (gl_trace_live > 0) {
        GL_TRACE("gladio-trace: ---- frame %d", frameNumber);
        if (frameNumber == gl_trace_frame) gl_trace_dump_recent(4000);
    }
    uint64_t now = nowMicros();
    if (statsStart == 0) statsStart = now;
    statsFrames++;
    statsWaitTotal += waited;
    if (waited > statsWaitMax) statsWaitMax = waited;
    if (waited > 1000) statsWaits++;
    if (now - statsStart >= 5000000u && !gl_profile) {
        // the 5 s stats only with GLADIO_PROFILE=1
        statsStart = now;
        statsFrames = statsWaits = 0;
        statsWaitTotal = statsWaitMax = statsVsyncTotal = 0;
        gl_roundtrips = 0;
        gl_inline_bytes = 0;
        memset(gl_roundtrip_codes, 0, sizeof(gl_roundtrip_codes));
    }
    else if (now - statsStart >= 5000000u) {
        double seconds = (now - statsStart) / 1000000.0;
        println("gladio stats: %.1f fps, waited for a free buffer in %d of %d frames, avg %.2f ms, max %.1f ms, %.1f server round trips per frame, vsync wait avg %.2f ms",
                statsFrames / seconds, statsWaits, statsFrames, statsWaitTotal / 1000.0 / statsFrames, statsWaitMax / 1000.0, (double)gl_roundtrips / statsFrames,
                statsVsyncTotal / 1000.0 / statsFrames);
        statsVsyncTotal = 0;
        // the calls that waited for the server most often
        char top[512] = "";
        for (int n = 0; n < 6; n++) {
            int best = -1;
            for (int i = 0; i < 1024; i++) if (gl_roundtrip_codes[i] && (best < 0 || gl_roundtrip_codes[i] > gl_roundtrip_codes[best])) best = i;
            if (best < 0) break;
            size_t used = strlen(top);
            snprintf(top + used, sizeof(top) - used, " %s=%.1f", requestCodeToString(best), (double)gl_roundtrip_codes[best] / statsFrames);
            gl_roundtrip_codes[best] = 0;
        }
        if (top[0]) println("gladio round trips per frame:%s", top);
        println("gladio buffer data: %.2f MB per frame", gl_inline_bytes / 1048576.0 / statsFrames);
        gl_inline_bytes = 0;
        memset(gl_roundtrip_codes, 0, sizeof(gl_roundtrip_codes));
        gl_roundtrips = 0;
        statsStart = now;
        statsFrames = statsWaits = 0;
        statsWaitTotal = statsWaitMax = 0;
    }
}
static int numPresenters = 0;
static void readPresentEvents(Presenter* presenter, xcb_generic_event_t* event);
static void traceShown(xcb_present_complete_notify_event_t* complete);

// vsync like Mesa: the swap returns once the frame before this one is on the screen. The game then starts its frames
// in step with the display; without it frames arrive at random points of the refresh, some are shown twice, some
// dropped (the slight judder Zink does not have). At most 100 ms, Termux:X11 may not be drawing (in the background).
static void waitForPreviousFrame(xcb_connection_t* conn, Presenter* presenter) {
    uint64_t start = nowMicros();
    for (;;) {
        xcb_generic_event_t* event;
        while ((event = xcb_poll_for_special_event(conn, presenter->events))) readPresentEvents(presenter, event);
        if ((int32_t)(presenter->serial - 1 - presenter->completedSerial) <= 0) break;
        if (nowMicros() - start > 100000) break;
        usleep(250);
    }
    lastVsyncWait = nowMicros() - start;
    statsVsyncTotal += lastVsyncWait;
}

// ---- frame time trace ----------------------------------------------------------------------------------------------
// Every 5 s a summary line; GLADIO_FRAMELOG=<frame>: every frame from that one on for 600 frames (gameplay, not loading).
typedef struct Sum { double n, sum, sq, max; } Sum;
static void add(Sum* s, double v) { s->n++; s->sum += v; s->sq += v * v; if (v > s->max) s->max = v; }
static double avg(Sum* s) { return s->n ? s->sum / s->n : 0; }
static double sd(Sum* s) { if (s->n < 2) return 0; double m = avg(s), v = s->sq / s->n - m * m; return v > 0 ? sqrt(v) : 0; }

static struct {
    uint64_t start, prevEntry, prevExit;
    int frame, logFrom, logTo;
    Sum interval, game, acquire, reply, present, ringWrite, srvBusy, srvIdle, srvGpu, srvBlit, shownGap, glTime, glCalls, wakes, writes;
    int over20, over33, over50;
    int vblanks[4]; // shown 1, 2, 3, 4+ vblanks after the previous one
    int skipped;
    uint64_t lastMsc, lastUst;
} ft;

uint64_t gladio_now_us(void) {
    return nowMicros();
}

bool gladio_present_now(void) {
    static int value = -1;
    if (value < 0) {
        const char* env = getenv("GLADIO_PRESENT_NOW");
        value = env && *env && atoi(env) != 0;
        if (value) println("gladio: GLADIO_PRESENT_NOW=1: every frame is shown at its own swap (not one swap later)");
    }
    return value;
}

static bool frameLogging(void) {
    if (ft.logFrom == 0) {
        const char* env = getenv("GLADIO_FRAMELOG");
        ft.logFrom = env && *env ? atoi(env) : -1;
        ft.logTo = ft.logFrom + 600;
    }
    return ft.logFrom > 0 && ft.frame >= ft.logFrom && ft.frame < ft.logTo;
}

static void traceShown(xcb_present_complete_notify_event_t* complete) {
    if (complete->kind != XCB_PRESENT_COMPLETE_KIND_PIXMAP) return;
    if (complete->mode == XCB_PRESENT_COMPLETE_MODE_SKIP) ft.skipped++;
    if (ft.lastMsc && complete->msc > ft.lastMsc) {
        uint64_t vblanks = complete->msc - ft.lastMsc;
        ft.vblanks[vblanks >= 4 ? 3 : vblanks - 1]++;
        add(&ft.shownGap, (complete->ust - ft.lastUst) / 1000.0);
    }
    if (frameLogging())
        println("gladio shown #%u: %+lld vblank(s), %.1f ms after the previous one, mode %s", complete->serial,
                ft.lastMsc ? (long long)(complete->msc - ft.lastMsc) : 0LL, ft.lastUst ? (complete->ust - ft.lastUst) / 1000.0 : 0.0,
                complete->mode == XCB_PRESENT_COMPLETE_MODE_SKIP ? "SKIP" : complete->mode == XCB_PRESENT_COMPLETE_MODE_FLIP ? "flip" : "copy");
    ft.lastMsc = complete->msc;
    ft.lastUst = complete->ust;
}

void gladio_frame_trace(FrameTrace* t) {
    ft.frame++;
    uint64_t vsync = lastVsyncWait;
    lastVsyncWait = 0;
    uint64_t ringWrite = ring_write_wait_us;
    ring_write_wait_us = 0;
    if (ft.prevEntry) {
        double interval = (t->entry - ft.prevEntry) / 1000.0;
        add(&ft.interval, interval);
        if (interval > 20) ft.over20++;
        if (interval > 33) ft.over33++;
        if (interval > 50) ft.over50++;
        add(&ft.game, (t->entry - ft.prevExit) / 1000.0);
    }
    add(&ft.acquire, (t->acquired - t->entry) / 1000.0);
    add(&ft.reply, (t->replied - t->acquired) / 1000.0);
    add(&ft.present, (t->exit - t->replied) / 1000.0);
    add(&ft.ringWrite, ringWrite / 1000.0);
    add(&ft.glTime, gl_call_time_ns / 1000000.0);
    add(&ft.glCalls, gl_call_count);
    add(&ft.wakes, ring_wakes);
    add(&ft.writes, ring_writes);
    double frameGlTime = gl_call_time_ns / 1000000.0;
    unsigned frameGlCalls = gl_call_count, frameWakes = ring_wakes;
    gl_call_time_ns = 0;
    gl_call_count = ring_wakes = ring_writes = 0;
    add(&ft.srvBusy, (t->serverSpan - t->serverIdle) / 1000.0);
    add(&ft.srvIdle, t->serverIdle / 1000.0);
    add(&ft.srvGpu, t->serverGpuWait / 1000.0);
    add(&ft.srvBlit, t->serverBlit / 1000.0);

    if (frameLogging())
        println("gladio frame %d: interval %.1f ms = game %.1f + swap %.1f (buffer %.1f, server reply %.1f, present+vsync %.1f of it vsync %.1f), "
                "ring full %.1f, in Gladio calls %.1f (%u calls, %u server wakeups) | server: busy %.1f idle %.1f gpu wait %.1f copy %.1f",
                ft.frame, ft.prevEntry ? (t->entry - ft.prevEntry) / 1000.0 : 0.0, ft.prevExit ? (t->entry - ft.prevExit) / 1000.0 : 0.0,
                (t->exit - t->entry) / 1000.0, (t->acquired - t->entry) / 1000.0, (t->replied - t->acquired) / 1000.0,
                (t->exit - t->replied) / 1000.0, vsync / 1000.0, ringWrite / 1000.0, frameGlTime, frameGlCalls, frameWakes,
                (t->serverSpan - t->serverIdle) / 1000.0, t->serverIdle / 1000.0, t->serverGpuWait / 1000.0, t->serverBlit / 1000.0);

    ft.prevEntry = t->entry;
    ft.prevExit = t->exit;
    if (!ft.start) ft.start = t->exit;
    if (t->exit - ft.start >= 5000000u && ft.interval.n > 0 && !gl_profile) {
        // the 5 s summary only with GLADIO_PROFILE=1 (no log noise in normal play)
        memset(&ft.interval, 0, sizeof(ft) - offsetof(__typeof__(ft), interval));
        ft.start = t->exit;
    }
    else if (t->exit - ft.start >= 5000000u && ft.interval.n > 0) {
        println("gladio frametime: %d frames, interval avg %.1f sd %.1f max %.1f ms, >20 ms %d, >33 ms %d, >50 ms %d | game avg %.1f max %.1f | "
                "swap: buffer %.1f, server reply avg %.1f max %.1f, present+vsync avg %.1f max %.1f | ring full %.2f",
                (int)ft.interval.n, avg(&ft.interval), sd(&ft.interval), ft.interval.max, ft.over20, ft.over33, ft.over50,
                avg(&ft.game), ft.game.max, avg(&ft.acquire), avg(&ft.reply), ft.reply.max, avg(&ft.present), ft.present.max, avg(&ft.ringWrite));
        if (gl_profile) {
            if (gl_profile > 1)
                println("gladio frametime: game thread in Gladio calls avg %.1f max %.1f ms, %.0f calls, %.0f ring writes, %.0f server wakeups per frame%s",
                        avg(&ft.glTime), ft.glTime.max, avg(&ft.glCalls), avg(&ft.writes), avg(&ft.wakes), gl_batch_ops > 0 ? " (batched)" : "");
            else
                println("gladio frametime: %.0f calls, %.0f ring writes, %.0f server wakeups per frame%s (GLADIO_PROFILE=2: time in the calls too)",
                        avg(&ft.glCalls), avg(&ft.writes), avg(&ft.wakes), gl_batch_ops > 0 ? " (batched)" : "");
            char top[700] = "";
            for (int n = 0; n < 12; n++) {
                int best = -1;
                for (int i = 0; i < 1024; i++) if (gl_request_counts[i] && (best < 0 || gl_request_counts[i] > gl_request_counts[best])) best = i;
                if (best < 0) break;
                size_t used = strlen(top);
                snprintf(top + used, sizeof(top) - used, " %s=%.0f", requestCodeToString(best), gl_request_counts[best] / ft.interval.n);
                gl_request_counts[best] = 0;
            }
            memset(gl_request_counts, 0, sizeof(gl_request_counts));
            println("gladio frametime: requests per frame:%s; not sent (already set): %.0f; handed over directly: %.0f; "
                    "glActiveTexture made lazy: %.0f", top, gl_state_skipped / ft.interval.n, gl_direct_calls / ft.interval.n,
                    gl_lazy_units / ft.interval.n);
            gl_lazy_units = 0;
            gl_state_skipped = 0;
            gl_direct_calls = 0;
        }
        println("gladio frametime: server busy avg %.1f max %.1f, idle avg %.1f, gpu wait avg %.1f max %.1f, copy %.2f | shown: after 1 vblank %d, 2: %d, 3: %d, 4+: %d, skipped %d, gap sd %.1f ms",
                avg(&ft.srvBusy), ft.srvBusy.max, avg(&ft.srvIdle), avg(&ft.srvGpu), ft.srvGpu.max, avg(&ft.srvBlit),
                ft.vblanks[0], ft.vblanks[1], ft.vblanks[2], ft.vblanks[3], ft.skipped, sd(&ft.shownGap));
        int logFrom = ft.logFrom, logTo = ft.logTo, frame = ft.frame;
        uint64_t lastMsc = ft.lastMsc, lastUst = ft.lastUst;
        memset(&ft, 0, sizeof(ft));
        ft.logFrom = logFrom; ft.logTo = logTo; ft.frame = frame; ft.lastMsc = lastMsc; ft.lastUst = lastUst;
        ft.start = t->exit;
        ft.prevEntry = t->entry;
        ft.prevExit = t->exit;
    }
}

// glXSwapInterval*: what the game asked for; GLADIO_VSYNC=0/1 in the settings' variables overrides it
static int swapInterval = 0;
int gladio_swap_interval(void) {
    static int forced = -2;
    if (forced == -2) {
        const char* env = getenv("GLADIO_VSYNC");
        forced = env && *env ? atoi(env) : -1;
        if (forced >= 0) println("gladio: GLADIO_VSYNC=%d", forced);
    }
    // default 0: measured smoother than presenting at the vblank (v28 trace: frame time sd 3.5-5 vs 3-8 ms), the game's
    // own interval is only used with GLADIO_VSYNC=1
    return forced >= 0 ? (forced > 0 ? (swapInterval > 0 ? swapInterval : 1) : 0) : 0;
}

void gladio_set_swap_interval(int interval) {
    if (interval < 0) interval = 1; // adaptive vsync: plain vsync
    if (interval != swapInterval && gl_profile) println("gladio: swap interval %d", interval);
    swapInterval = interval;
}
static int dri3State = 0; // 0 unknown, 1 works, -1 refused: shared memory from then on

static Presenter* findPresenter(GLXDrawable drawable) {
    for (int i = 0; i < numPresenters; i++) if (presenters[i].drawable == drawable) return &presenters[i];
    return NULL;
}

static void releasePresenter(xcb_connection_t* conn, Presenter* presenter) {
    if (presenter->segment) xcb_shm_detach(conn, presenter->segment);
    if (presenter->gc) xcb_free_gc(conn, presenter->gc);
    for (int i = 0; i < presenter->numBuffers; i++) xcb_free_pixmap(conn, presenter->pixmaps[i]);
    if (presenter->events) {
        xcb_present_select_input(conn, presenter->eventId, presenter->drawable, 0);
        xcb_unregister_for_special_event(conn, presenter->events);
    }
    presenter->segment = 0;
    presenter->gc = 0;
    presenter->numBuffers = 0;
    presenter->events = NULL;
    presenter->width = presenter->height = 0;
}

/* SET_FRAME_BUFFER: [drawable, width, height] + the memfd; the server maps it before it replies. */
static bool sendFrameBuffer(GLXDrawable drawable, int width, int height, int fd) {
    int data[3] = {(int)drawable, width, height};
    if (!glx_send(serverFd, GLX_OPCODE_SET_FRAME_BUFFER, data, sizeof(data))) return false;
    if (send_fds(serverFd, &fd, 1, NULL, 0) < 0) return false;
    ArrayBuffer reply = {0};
    bool ok = glx_recv(serverFd, &reply);
    ArrayBuffer_free(&reply);
    return ok;
}

static bool setupSharedMemory(xcb_connection_t* conn, Presenter* presenter, int width, int height) {
    size_t size = (size_t)width * height * 4;
    int fd = memfd_create("gladio-frame", MFD_CLOEXEC);
    if (fd < 0 || ftruncate(fd, size) < 0) {
        println("gladio: frame buffer %dx%d: memfd failed", width, height);
        if (fd >= 0) close(fd);
        return false;
    }

    presenter->segment = xcb_generate_id(conn);
    // ShmAttachFd takes its own copy of the descriptor
    xcb_generic_error_t* error = xcb_request_check(conn, xcb_shm_attach_fd_checked(conn, presenter->segment, dup(fd), 0));
    if (error) {
        println("gladio: Termux:X11 refused the shared frame buffer (ShmAttachFd error %d)", error->error_code);
        free(error);
        presenter->segment = 0;
        close(fd);
        return false;
    }
    presenter->gc = xcb_generate_id(conn);
    xcb_create_gc(conn, presenter->gc, presenter->drawable, 0, NULL);

    if (!sendFrameBuffer(presenter->drawable, width, height, fd)) println("gladio: the server did not take the frame buffer");
    close(fd);
    return true;
}

/* PRESENT_BUFFERS: the server allocates the AHardwareBuffers and sends one socket per buffer; Termux:X11 takes each
 * buffer from its socket while it creates the pixmap. */
static bool setupPresentBuffers(xcb_connection_t* conn, Presenter* presenter, int width, int height) {
    int data[4] = {(int)presenter->drawable, width, height, NUM_PRESENT_BUFFERS};
    if (!glx_send(serverFd, GLX_OPCODE_PRESENT_BUFFERS, data, sizeof(data))) return false;
    ArrayBuffer reply = {0};
    if (!glx_recv(serverFd, &reply)) {
        ArrayBuffer_free(&reply);
        return false;
    }
    int count = ArrayBuffer_getInt(&reply);
    int stride = ArrayBuffer_getInt(&reply);
    ArrayBuffer_free(&reply);

    int fds[NUM_PRESENT_BUFFERS];
    int numFds = 0;
    char byte;
    if (count <= 0 || recv_fds(serverFd, fds, &numFds, &byte, 1) < 0 || numFds != count) return false;

    xcb_dri3_query_version_reply_t* version = xcb_dri3_query_version_reply(conn, xcb_dri3_query_version(conn, 1, 2), NULL);
    free(version);

    bool ok = true;
    for (int i = 0; i < count; i++) {
        if (!ok) {
            close(fds[i]);
            continue;
        }
        xcb_pixmap_t pixmap = xcb_generate_id(conn);
        // xcb closes the fd once it is sent
        xcb_generic_error_t* error = xcb_request_check(conn, xcb_dri3_pixmap_from_buffers_checked(
            conn, pixmap, presenter->drawable, 1, width, height, stride * 4, 0, 0, 0, 0, 0, 0, 0,
            presenter->depth, 32, MODIFIER_AHARDWAREBUFFER, &fds[i]));
        if (error) {
            println("gladio: Termux:X11 refused the present buffer (DRI3 error %d)", error->error_code);
            free(error);
            ok = false;
            continue;
        }
        presenter->pixmaps[presenter->numBuffers] = pixmap;
        presenter->busy[presenter->numBuffers] = false;
        presenter->numBuffers++;
    }
    if (!ok) {
        for (int i = 0; i < presenter->numBuffers; i++) xcb_free_pixmap(conn, presenter->pixmaps[i]);
        presenter->numBuffers = 0;
        return false;
    }

    presenter->eventId = xcb_generate_id(conn);
    presenter->events = xcb_register_for_special_xge(conn, &xcb_present_id, presenter->eventId, NULL);
    xcb_present_select_input(conn, presenter->eventId, presenter->drawable, XCB_PRESENT_EVENT_MASK_IDLE_NOTIFY | XCB_PRESENT_EVENT_MASK_COMPLETE_NOTIFY);
    xcb_flush(conn);
    return true;
}

// GLADIO_PRESENT=xserver: the game runs on the app's own X server (r34 display); the Gladio server copies each frame
// into the X window itself at the swap, so nothing to set up or show here
static bool xserverDisplay(void) {
    static int value = -1;
    if (value < 0) {
        const char* mode = getenv("GLADIO_PRESENT");
        value = mode && strcmp(mode, "xserver") == 0;
    }
    return value;
}

void gladio_present_prepare(Display* dpy, GLXDrawable drawable) {
    if (!dpy || !drawable || xserverDisplay()) return;
    Window root;
    int x, y;
    unsigned int width, height, border, depth;
    if (!XGetGeometry(dpy, drawable, &root, &x, &y, &width, &height, &border, &depth)) return;

    Presenter* presenter = findPresenter(drawable);
    if (presenter && presenter->width == (int)width && presenter->height == (int)height &&
        (presenter->numBuffers > 0 || presenter->segment)) return;

    xcb_connection_t* conn = XGetXCBConnection(dpy);
    if (!presenter) {
        if (numPresenters == MAX_WINDOWS) return;
        presenter = &presenters[numPresenters++];
        memset(presenter, 0, sizeof(Presenter));
        presenter->drawable = drawable;
    }
    else releasePresenter(conn, presenter);
    presenter->depth = depth;

    // GLADIO_PRESENT=shm: frames through shared memory (the older, slower way), to compare
    if (dri3State == 0) {
        const char* mode = getenv("GLADIO_PRESENT");
        if (mode && strcmp(mode, "shm") == 0) {
            dri3State = -1;
            println("gladio: frames go through shared memory (GLADIO_PRESENT=shm)");
        }
    }

    bool ready = false;
    if (dri3State >= 0) {
        ready = setupPresentBuffers(conn, presenter, width, height);
        if (dri3State == 0) {
            dri3State = ready ? 1 : -1;
            println("gladio: frames %s", ready ? "go to Termux:X11 as AHardwareBuffers (DRI3 + Present)" : "go through shared memory (DRI3 not available)");
        }
    }
    if (!ready) ready = setupSharedMemory(conn, presenter, width, height);
    if (!ready) return;

    presenter->width = width;
    presenter->height = height;
    if (gl_profile) println("gladio: window 0x%lx: %ux%u depth %u", (unsigned long)drawable, width, height, depth);
}

static void readPresentEvents(Presenter* presenter, xcb_generic_event_t* event) {
    while (event) {
        xcb_present_generic_event_t* generic = (xcb_present_generic_event_t*)event;
        if (generic->evtype == XCB_PRESENT_EVENT_IDLE_NOTIFY) {
            xcb_pixmap_t pixmap = ((xcb_present_idle_notify_event_t*)event)->pixmap;
            for (int i = 0; i < presenter->numBuffers; i++) if (presenter->pixmaps[i] == pixmap) presenter->busy[i] = false;
        }
        else if (generic->evtype == XCB_PRESENT_EVENT_COMPLETE_NOTIFY) {
            xcb_present_complete_notify_event_t* complete = (xcb_present_complete_notify_event_t*)event;
            traceShown(complete);
            if (complete->kind == XCB_PRESENT_COMPLETE_KIND_PIXMAP && (int32_t)(complete->serial - presenter->completedSerial) > 0)
                presenter->completedSerial = complete->serial;
        }
        free(event);
        event = NULL;
    }
}

int gladio_present_acquire(Display* dpy, GLXDrawable drawable) {
    Presenter* presenter = findPresenter(drawable);
    if (!dpy || !presenter || presenter->numBuffers == 0) return -1;
    xcb_connection_t* conn = XGetXCBConnection(dpy);
    uint64_t start = nowMicros();

    xcb_generic_event_t* event;
    while ((event = xcb_poll_for_special_event(conn, presenter->events))) readPresentEvents(presenter, event);
    for (;;) {
        for (int n = 1; n <= presenter->numBuffers; n++) {
            int i = (presenter->current + n) % presenter->numBuffers;
            if (!presenter->busy[i]) {
                presenter->current = i;
                countFrame(nowMicros() - start);
                return i;
            }
        }
        // all three are still on screen or queued: wait until Termux:X11 lets one go
        event = xcb_wait_for_special_event(conn, presenter->events);
        if (!event) return -1;
        readPresentEvents(presenter, event);
    }
}

void gladio_present_finish(Display* dpy, GLXDrawable drawable, int bufferIndex, int presentIndex) {
    Presenter* presenter = findPresenter(drawable);
    if (!dpy || !presenter) return;
    xcb_connection_t* conn = XGetXCBConnection(dpy);

    if (bufferIndex >= 0 && bufferIndex < presenter->numBuffers) {
        // being drawn into until the next swap shows it; then on screen until Termux:X11 reports it idle
        presenter->busy[bufferIndex] = true;
        if (presentIndex >= 0 && presentIndex < presenter->numBuffers) {
            // swap interval 0: ASYNC, shown at once (tears / uneven pacing); 1+: at Termux:X11's next vblank, which it runs
            // right before it draws the screen, so the frames follow the display (smooth; the buffer wait throttles the game)
            int interval = gladio_swap_interval();
            xcb_present_pixmap(conn, drawable, presenter->pixmaps[presentIndex], ++presenter->serial, 0, 0, 0, 0,
                               XCB_NONE, XCB_NONE, XCB_NONE, interval > 0 ? XCB_PRESENT_OPTION_NONE : XCB_PRESENT_OPTION_ASYNC,
                               0, interval > 1 ? interval : 0, 0, 0, NULL);
            xcb_flush(conn);
            if (interval > 0) waitForPreviousFrame(conn, presenter);
        }
        return;
    }
    if (!presenter->segment) return;
    xcb_shm_put_image(conn, drawable, presenter->gc, presenter->width, presenter->height, 0, 0,
                      presenter->width, presenter->height, 0, 0, presenter->depth,
                      XCB_IMAGE_FORMAT_Z_PIXMAP, 0, presenter->segment, 0);
    // round trip: the X server has copied the frame before the server writes the next one into the buffer
    free(xcb_get_input_focus_reply(conn, xcb_get_input_focus(conn), NULL));
}
