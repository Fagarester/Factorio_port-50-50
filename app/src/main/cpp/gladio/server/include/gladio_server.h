#ifndef GLADIO_SERVER_H
#define GLADIO_SERVER_H

#include "gl_context.h"

/* The window size the client registered with its frame buffer (SET_FRAME_BUFFER). */
extern void GladioServer_getWindowSize(int clientFd, int windowId, short* outWidth, short* outHeight);
/* Puts the finished frame into the client's present buffer (bufferIndex >= 0, AHardwareBuffer) or
   its shared memory frame buffer; false when the window was resized. */
// frame time trace / present mode of the swap being handled (set and read on the client's server thread)
extern __thread bool gladio_present_now;      // show this frame (wait for it on the GPU), not the one before
extern __thread int gladio_last_gpu_wait_us;  // waiting for the GPU fence in this swap
extern __thread int gladio_last_blit_us;      // copy into the present buffer + flush
extern bool GladioServer_updateWindowContent(int clientFd, int drawableId, short width, short height, int bufferIndex, int* outPresentIndex);
extern GLXContext* GladioServer_getGLXContext(int clientFd, int contextId);
/* listens for clients on path (a thread accepts them); false and why in error otherwise */
extern bool GladioServer_listen(const char* path, char* error, size_t errorSize);

#endif
