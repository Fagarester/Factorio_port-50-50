// Android functions of the Gladio server, from the phone's own libraries through libhybris (recon build).
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include "android/hardware_buffer.h"
void *android_dlopen(const char *filename, int flag);
void *android_dlsym(void *handle, const char *symbol);
static void *lib(const char *name) {
	void *h = android_dlopen(name, 0);
	if (!h) { fprintf(stderr, "shim: %s missing\n", name); abort(); }
	return h;
}
static void *nw(const char *s) { static void *h; if (!h) h = lib("libnativewindow.so"); void *f = android_dlsym(h, s); if (!f) { fprintf(stderr, "shim: no %s\n", s); abort(); } return f; }
#define NW(ret, name, params, args) ret name params { static ret (*fp) params; if (!fp) fp = nw(#name); return fp args; }
#define NWV(name, params, args) void name params { static void (*fp) params; if (!fp) fp = nw(#name); fp args; }
NW(int, AHardwareBuffer_allocate, (const AHardwareBuffer_Desc *d, AHardwareBuffer **o), (d, o))
NWV(AHardwareBuffer_acquire, (AHardwareBuffer *b), (b))
NWV(AHardwareBuffer_release, (AHardwareBuffer *b), (b))
NWV(AHardwareBuffer_describe, (const AHardwareBuffer *b, AHardwareBuffer_Desc *d), (b, d))
NW(int, AHardwareBuffer_lock, (AHardwareBuffer *b, uint64_t u, int32_t f, const void *r, void **o), (b, u, f, r, o))
NW(int, AHardwareBuffer_unlock, (AHardwareBuffer *b, int32_t *f), (b, f))
NW(int, AHardwareBuffer_sendHandleToUnixSocket, (const AHardwareBuffer *b, int fd), (b, fd))
NW(int, AHardwareBuffer_recvHandleFromUnixSocket, (int fd, AHardwareBuffer **o), (fd, o))
NW(const void *, AHardwareBuffer_getNativeHandle, (const AHardwareBuffer *b), (b))
int ASharedMemory_create(const char *name, size_t size) {
	static int (*f)(const char *, size_t); static void *h;
	if (!f) { if (!h) h = lib("libandroid.so"); f = android_dlsym(h, "ASharedMemory_create"); }
	return f(name, size);
}
#define EP(ret, name, params, args) ret name params { static ret (*fp) params; if (!fp) fp = (void *)eglGetProcAddress(#name); return fp args; }
EP(EGLSyncKHR, eglCreateSyncKHR, (EGLDisplay d, EGLenum t, const EGLint *a), (d, t, a))
EP(EGLint, eglClientWaitSyncKHR, (EGLDisplay d, EGLSyncKHR s, EGLint fl, EGLTimeKHR t), (d, s, fl, t))
EP(EGLBoolean, eglDestroySyncKHR, (EGLDisplay d, EGLSyncKHR s), (d, s))
EP(EGLImageKHR, eglCreateImageKHR, (EGLDisplay d, EGLContext c, EGLenum t, EGLClientBuffer b, const EGLint *a), (d, c, t, b, a))
EP(EGLBoolean, eglDestroyImageKHR, (EGLDisplay d, EGLImageKHR i), (d, i))
EP(EGLClientBuffer, eglGetNativeClientBufferANDROID, (const struct AHardwareBuffer *b), (b))
