#define ARRAY_SIZE(array) (sizeof(array) / sizeof(array)[0])
#define MIN(a, b) (((a)<(b))?(a):(b))
#define MAX(a, b) (((a)>(b))?(a):(b))
#define CLAMP(x, low, high) (((x)>(high))?(high):(((x)<(low))?(low):(x)))
#define PACK16(a, b) (a << 16) | (b & 0xffff)
#define PACK32(a, b) ((int64_t)a << 32) | (b & 0xffffffffl)
#define UNPACK32(packed, a, b) int a = (int)(packed >> 32); int b = (int)(packed & 0xffffffffl)
#define IS_POWER_OF_TWO(n) ((n > 0) && ((n & (n - 1)) == 0))
#define SWAP(a, b, type) do { type tmp = a; a = b; b = tmp; } while (0)
#define BITMASK_SET(bits, flag) bits |= flag
#define BITMASK_UNSET(bits, flag) bits &= ~flag
#define GETEXP(x) (31 - __builtin_clz(x))

#define LIBVULKAN_PATH "/system/lib64/libvulkan.so"

#define CLOSEFD(x) \
    do { \
        if (x > 0) { \
            close(x); \
            x = -1; \
        } \
    } \
    while(0)

#define MEMFREE(x) \
    do { \
        if (x != NULL) { \
            free(x); \
            x = NULL; \
        } \
    } \
    while(0)

#ifndef WINLATOR_H
#define WINLATOR_H

#include <unistd.h>
#include <syscall.h>

#include <fcntl.h>
#include <stdio.h>
#include <string.h>

// Cache directory of the running app: "/data/data/<package>/cache".
// The package name is NOT hardcoded: an Android app process is named after its package,
// so it is read from /proc/self/cmdline. This lets the APK be re-packaged under any name.
#define APP_CACHE_DIR_SIZE 192
static inline const char* getAppCacheDir(void) {
    static char path[APP_CACHE_DIR_SIZE];
    static volatile int ready = 0;
    if (!ready) {
        char name[128] = {0};
        char tmp[APP_CACHE_DIR_SIZE] = {0};
        int fd = open("/proc/self/cmdline", O_RDONLY);
        if (fd >= 0) {
            ssize_t n = read(fd, name, sizeof(name) - 1);
            close(fd);
            if (n <= 0) name[0] = '\0';
        }
        char* colon = strchr(name, ':'); // strip ":service" style suffix of secondary processes
        if (colon) *colon = '\0';
        snprintf(tmp, sizeof(tmp), "/data/data/%s/cache", name);
        memcpy(path, tmp, sizeof(path));
        __sync_synchronize();
        ready = 1;
    }
    return path;
}

static inline pid_t currentThreadId() {
#ifdef __ANDROID__
    return gettid();
#else
    return syscall(SYS_gettid);
#endif
}

#ifdef __ANDROID__
#include <android/log.h>
#define println(...) __android_log_print(ANDROID_LOG_DEBUG, "System.out", __VA_ARGS__)
#else

#define println(fmt, ...) \
    do { \
        char fmtBuf[256] = {0}; \
        sprintf(fmtBuf, "%s\n", fmt); \
        fprintf(stderr, fmtBuf __VA_OPT__(,) __VA_ARGS__); \
    } \
    while (0)
#endif

#endif