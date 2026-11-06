// Minimal libhybris EGL window-system module: no windows, pbuffers only (recon test).
#include <stdlib.h>
#include "ws.h"
static struct ws_egl_interface *iface;
static void init_module(struct ws_egl_interface *i) { iface = i; }
static struct _EGLDisplay *GetDisplay(EGLNativeDisplayType n) { return calloc(1, sizeof(struct _EGLDisplay)); }
static void Terminate(struct _EGLDisplay *d) {}
static EGLNativeWindowType CreateWindow(EGLNativeWindowType w, struct _EGLDisplay *d) { return w; }
static void DestroyWindow(EGLNativeWindowType w) {}
static __eglMustCastToProperFunctionPointerType GetProc(const char *p) { return NULL; }
static void Passthrough(EGLContext *c, EGLenum *t, EGLClientBuffer *b, const EGLint **a) {}
static const char *QueryString(EGLDisplay d, EGLint n, const char *(*real)(EGLDisplay, EGLint)) { return real(d, n); }
static void ReleaseDisplay(struct _EGLDisplay *d) { free(d); }
struct ws_module ws_module_info = {
	.init_module = init_module, .GetDisplay = GetDisplay, .Terminate = Terminate,
	.CreateWindow = CreateWindow, .DestroyWindow = DestroyWindow, .eglGetProcAddress = GetProc,
	.passthroughImageKHR = Passthrough, .eglQueryString = QueryString, .releaseDisplay = ReleaseDisplay,
};
