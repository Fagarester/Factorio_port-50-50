#ifndef GLADIO_PRESENT_H
#define GLADIO_PRESENT_H

#include <X11/Xlib.h>
#include <GL/glx.h>
#include <stdint.h>
#include <stdbool.h>

/* Gives the window a frame buffer shared with the Gladio server and Termux:X11 (memfd + MIT-SHM); again when it is resized. */
extern void gladio_present_prepare(Display* dpy, GLXDrawable drawable);
/* A free present buffer for the next frame (waits for Termux:X11 to release one); -1 = shared memory frame. */
extern int gladio_present_acquire(Display* dpy, GLXDrawable drawable);
extern int gladio_swap_interval(void);
// frame time trace: one swap, times in microseconds
typedef struct FrameTrace {
    uint64_t entry, acquired, replied, exit;
    int serverSpan, serverIdle, serverGpuWait, serverBlit;
} FrameTrace;
extern uint64_t gladio_now_us(void);
extern bool gladio_present_now(void);
extern void gladio_frame_trace(FrameTrace* trace);
extern void gladio_set_swap_interval(int interval);
/* Shows the frame the server just wrote. */
/* bufferIndex: the buffer this frame is being copied into (busy from now on); presentIndex: the finished one to show. */
extern void gladio_present_finish(Display* dpy, GLXDrawable drawable, int bufferIndex, int presentIndex);

#endif
