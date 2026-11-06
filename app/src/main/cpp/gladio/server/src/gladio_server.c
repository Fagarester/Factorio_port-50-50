/*
 * Gladio server for Termux:X11.
 *
 * In Winlator the Gladio client spoke GLX to the Java X server, which ran in the app and owned the windows.
 * Termux:X11 is a separate process, so the client now connects to this socket instead: the same GLX
 * requests (X11 framing), answered here, plus SET_FRAME_BUFFER. Rendering is unchanged (ring buffers,
 * request_handler on Android's GLES driver). A finished frame is read into a memfd the client shares with
 * Termux:X11 (MIT-SHM, ShmAttachFd), and the client puts it into its window.
 */
#define EGL_EGLEXT_PROTOTYPES 1
#include <errno.h>
#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <time.h>
#include <android/log.h>
#include <android/hardware_buffer.h>

#include "gladio_server.h"
#include "socket_utils.h"

#define TAG "GladioServer"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#define OPCODE_CREATE_GL_CONTEXT 1
#define OPCODE_DESTROY_GL_CONTEXT 2
#define OPCODE_CREATE_CONTEXT 3
#define OPCODE_DESTROY_CONTEXT 4
#define OPCODE_QUERY_VERSION 7
#define OPCODE_QUERY_EXTENSIONS_STRING 18
#define OPCODE_QUERY_SERVER_STRING 19
#define OPCODE_GET_FB_CONFIGS 21
#define OPCODE_CREATE_CONTEXT_ATTRIBS_ARB 34
#define OPCODE_SET_FRAME_BUFFER 40
#define OPCODE_PRESENT_BUFFERS 41
#define MAX_PRESENT_BUFFERS 4

#define GLX_VENDOR 1
#define GLX_VERSION 2
#define GLX_EXTENSIONS 3
#define GLX_CONTEXT_MAJOR_VERSION_ARB 0x2091
#define GLX_CONTEXT_MINOR_VERSION_ARB 0x2092

static const char* GLX_EXTENSIONS_STRING = "GLX_ARB_create_context GLX_ARB_get_proc_address";

typedef struct Entry {
    int id;
    void* value;
} Entry;

typedef struct PresentBuffer {
    AHardwareBuffer* buffer;
    EGLImageKHR image;
    // GL objects of the context that drew into it (FBOs are not shared between contexts)
    EGLContext glContext;
    GLuint texture;
    GLuint framebuffer;
} PresentBuffer;

typedef struct Frame {
    int drawable;
    int width;
    int height;
    uint32_t* pixels;
    size_t size;
    // Termux:X11 shows these directly (DRI3 + Present): no copy through the CPU
    PresentBuffer buffers[MAX_PRESENT_BUFFERS];
    int numBuffers;
    // the frame copied at the last swap: shown at the next one, when its fence has passed (no glFinish stall)
    int pendingIndex;
    EGLSyncKHR pendingFence;
    // replaced buffers whose GL objects must be deleted on a GL thread
    PresentBuffer retired[MAX_PRESENT_BUFFERS * 2];
    int numRetired;
} Frame;

typedef struct Client {
    int fd;
    Entry glContexts[64];
    int numGLContexts;
    Entry glxContexts[64];
    int numGLXContexts;
    Frame frames[8];
    int numFrames;
    struct Client* next;
} Client;

static pthread_mutex_t clientsLock = PTHREAD_MUTEX_INITIALIZER;
static Client* clients = NULL;
static bool started = false;

static Client* findClient(int fd) {
    for (Client* client = clients; client; client = client->next) if (client->fd == fd) return client;
    return NULL;
}

static void* findEntry(Entry* entries, int count, int id) {
    for (int i = 0; i < count; i++) if (entries[i].id == id) return entries[i].value;
    return NULL;
}

static void putEntry(Entry* entries, int* count, int max, int id, void* value) {
    for (int i = 0; i < *count; i++) {
        if (entries[i].id == id) {
            entries[i].value = value;
            return;
        }
    }
    if (*count < max) {
        entries[*count].id = id;
        entries[*count].value = value;
        (*count)++;
    }
}

static void* removeEntry(Entry* entries, int* count, int id) {
    for (int i = 0; i < *count; i++) {
        if (entries[i].id == id) {
            void* value = entries[i].value;
            entries[i] = entries[--(*count)];
            return value;
        }
    }
    return NULL;
}

// ------------------------------------------------------------------ the X server inside the app (r34 display)
// When the game runs on the app's own X server (com.factorio.xserver) the window belongs to Java: its size comes from
// there, and a finished frame is copied by the GPU straight into the window's texture (Gladio's contexts share the X
// server renderer's EGL context), as Winlator's LegacyGLXExtension did. No present buffers, no fence, no frame of delay.
extern EGLContext globalEGLContext;
static JavaVM* javaVM = NULL;
static jclass serverClass = NULL;
static jmethodID updateWindowContentMethod = NULL, windowSizeMethod = NULL;
static pthread_key_t jniDetachKey;
static pthread_once_t jniDetachOnce = PTHREAD_ONCE_INIT;

static void detachThread(void* unused) {
    if (javaVM) (*javaVM)->DetachCurrentThread(javaVM);
}

static void createDetachKey(void) {
    pthread_key_create(&jniDetachKey, detachThread);
}

static JNIEnv* threadEnv(void) {
    if (!javaVM) return NULL;
    JNIEnv* env = NULL;
    if ((*javaVM)->GetEnv(javaVM, (void**)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*javaVM)->AttachCurrentThread(javaVM, &env, NULL) != JNI_OK) return NULL;
    pthread_once(&jniDetachOnce, createDetachKey);
    pthread_setspecific(jniDetachKey, env); // detached when the client's thread ends
    return env;
}

/* Called by the X server's renderer on its GL thread when its surface (and EGL context) is created. */
JNIEXPORT void JNICALL
Java_com_factory_gladio_GladioServer_shareCurrentContext(JNIEnv* env, jclass cls) {
    (*env)->GetJavaVM(env, &javaVM);
    if (!serverClass) {
        serverClass = (*env)->NewGlobalRef(env, cls);
        updateWindowContentMethod = (*env)->GetStaticMethodID(env, cls, "updateWindowContent", "(ISS)Z");
        windowSizeMethod = (*env)->GetStaticMethodID(env, cls, "windowSize", "(I)I");
    }
    globalEGLContext = eglGetCurrentContext();
    LOGI("sharing the X server's EGL context %p", globalEGLContext);
}

static bool xserverWindowSize(int windowId, short* width, short* height) {
    JNIEnv* env = serverClass ? threadEnv() : NULL;
    if (!env) return false;
    jint size = (*env)->CallStaticIntMethod(env, serverClass, windowSizeMethod, windowId);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (size <= 0) return false;
    *width = (short)(size >> 16);
    *height = (short)(size & 0xffff);
    return true;
}

static bool xserverUpdateWindowContent(int drawableId, short width, short height) {
    JNIEnv* env = serverClass ? threadEnv() : NULL;
    if (!env) return true;
    jboolean result = (*env)->CallStaticBooleanMethod(env, serverClass, updateWindowContentMethod, drawableId, width, height);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return true;
    }
    return result;
}

static Frame* findFrame(Client* client, int drawable) {
    for (int i = 0; i < client->numFrames; i++) if (client->frames[i].drawable == drawable) return &client->frames[i];
    return NULL;
}

// ------------------------------------------------------------------ called from the GL threads (gl_context.c)

void GladioServer_getWindowSize(int clientFd, int windowId, short* outWidth, short* outHeight) {
    *outWidth = 0;
    *outHeight = 0;
    pthread_mutex_lock(&clientsLock);
    Client* client = findClient(clientFd);
    Frame* frame = client ? findFrame(client, windowId) : NULL;
    if (frame) {
        *outWidth = (short)frame->width;
        *outHeight = (short)frame->height;
    }
    pthread_mutex_unlock(&clientsLock);
    if (!frame) xserverWindowSize(windowId, outWidth, outHeight);
}

GLXContext* GladioServer_getGLXContext(int clientFd, int contextId) {
    pthread_mutex_lock(&clientsLock);
    Client* client = findClient(clientFd);
    GLXContext* context = client ? findEntry(client->glxContexts, client->numGLXContexts, contextId) : NULL;
    pthread_mutex_unlock(&clientsLock);
    return context;
}

static __thread uint32_t* readBuffer = NULL;
static __thread size_t readBufferSize = 0;
static __thread int bgraSupported = -1;

static void deleteBufferObjects(PresentBuffer* buffer) {
    if (buffer->framebuffer && buffer->glContext == eglGetCurrentContext()) glDeleteFramebuffers(1, &buffer->framebuffer);
    if (buffer->texture && buffer->glContext == eglGetCurrentContext()) glDeleteTextures(1, &buffer->texture);
    buffer->framebuffer = 0;
    buffer->texture = 0;
    buffer->glContext = EGL_NO_CONTEXT;
}

static void releaseRetired(Frame* frame) {
    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    for (int i = 0; i < frame->numRetired; i++) {
        PresentBuffer* buffer = &frame->retired[i];
        deleteBufferObjects(buffer);
        if (buffer->image != EGL_NO_IMAGE_KHR) eglDestroyImageKHR(display, buffer->image);
        if (buffer->buffer) AHardwareBuffer_release(buffer->buffer);
    }
    frame->numRetired = 0;
}

/* every 5 s in the game's log (with the next reply): how long the server waited for the GPU to finish a frame */
__thread bool gladio_present_now = false;
__thread int gladio_last_gpu_wait_us = 0;
__thread int gladio_last_blit_us = 0;

static long long nowUs(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec * 1000000ll + t.tv_nsec / 1000;
}

static __thread long long gpuStatsStart = 0, gpuWaitTotal = 0, gpuWaitMax = 0;
static __thread int gpuFrames = 0;

static void countGpuWait(long long micros) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    long long now = t.tv_sec * 1000000ll + t.tv_nsec / 1000;
    if (gpuStatsStart == 0) gpuStatsStart = now;
    gpuFrames++;
    gpuWaitTotal += micros;
    if (micros > gpuWaitMax) gpuWaitMax = micros;
    if (now - gpuStatsStart >= 5000000) {
        // GLADIO_SERVER_STATS (compile time) for the "waited for the GPU" line; quiet in normal play
#ifdef GLADIO_SERVER_STATS
        gladioServerLog("stats: waited for the GPU to finish the previous frame avg %.2f ms, max %.1f ms (%d frames)",
                        gpuWaitTotal / 1000.0 / gpuFrames, gpuWaitMax / 1000.0, gpuFrames);
#endif
        gpuStatsStart = now;
        gpuFrames = 0;
        gpuWaitTotal = gpuWaitMax = 0;
    }
}

/* GPU copy of the frame into the present buffer, queued: the caller fences it and shows it one frame later. */
static void drawIntoPresentBuffer(PresentBuffer* buffer, int width, int height) {
    EGLContext current = eglGetCurrentContext();
    if (buffer->glContext != current) {
        deleteBufferObjects(buffer);
        buffer->glContext = current;
        GLint boundTexture = 0, boundDraw = 0;
        glGetIntegerv(GL_TEXTURE_BINDING_2D, &boundTexture);
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &boundDraw);
        glGenTextures(1, &buffer->texture);
        glBindTexture(GL_TEXTURE_2D, buffer->texture);
        glEGLImageTargetTexture2DOES(GL_TEXTURE_2D, (GLeglImageOES)buffer->image);
        glGenFramebuffers(1, &buffer->framebuffer);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, buffer->framebuffer);
        glFramebufferTexture2D(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, buffer->texture, 0);
        GLenum status = glCheckFramebufferStatus(GL_DRAW_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) LOGE("present buffer framebuffer incomplete: 0x%x", status);
        glBindTexture(GL_TEXTURE_2D, boundTexture);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, boundDraw);
    }

    GLint boundDraw = 0;
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &boundDraw);
    GLboolean scissor = glIsEnabled(GL_SCISSOR_TEST);
    if (scissor) glDisable(GL_SCISSOR_TEST);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, buffer->framebuffer);
    // GL draws bottom-up; X wants the top row first: flipped here, on the GPU (Termux:X11's own "flipped" modifier did not help)
    glBlitFramebuffer(0, 0, width, height, 0, height, width, 0, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, boundDraw);
    if (scissor) glEnable(GL_SCISSOR_TEST);
}

bool GladioServer_updateWindowContent(int clientFd, int drawableId, short width, short height, int bufferIndex, int* outPresentIndex) {
    *outPresentIndex = -1;
    pthread_mutex_lock(&clientsLock);
    Client* client = findClient(clientFd);
    Frame* frame = client ? findFrame(client, drawableId) : NULL;
    if (!frame) {
        pthread_mutex_unlock(&clientsLock);
        // no frame buffers from the client: the window is the app's X server's
        return xserverUpdateWindowContent(drawableId, width, height);
    }
    if (frame->width != width || frame->height != height) {
        pthread_mutex_unlock(&clientsLock);
        return false;
    }
    if (frame->numRetired > 0) releaseRetired(frame);
    if (bufferIndex >= 0 && bufferIndex < frame->numBuffers) {
        // the buffer stays valid: it is only replaced by the client, which waits for this swap first
        PresentBuffer* buffer = &frame->buffers[bufferIndex];
        int previousIndex = frame->pendingIndex;
        EGLSyncKHR previousFence = frame->pendingFence;
        pthread_mutex_unlock(&clientsLock);

        long long blitStart = nowUs();
        drawIntoPresentBuffer(buffer, width, height);
        EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        EGLSyncKHR fence = eglCreateSyncKHR(display, EGL_SYNC_FENCE_KHR, NULL);
        glFlush();
        gladio_last_blit_us = (int)(nowUs() - blitStart);
        gladio_last_gpu_wait_us = 0;

        if (gladio_present_now) {
            // this frame is shown at once: wait until the GPU has drawn it (and drop a frame still pending from before)
            long long t0 = nowUs();
            if (previousFence != EGL_NO_SYNC_KHR) {
                eglClientWaitSyncKHR(display, previousFence, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, EGL_FOREVER_KHR);
                eglDestroySyncKHR(display, previousFence);
            }
            if (fence != EGL_NO_SYNC_KHR) {
                eglClientWaitSyncKHR(display, fence, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, EGL_FOREVER_KHR);
                eglDestroySyncKHR(display, fence);
            }
            gladio_last_gpu_wait_us = (int)(nowUs() - t0);
            countGpuWait(gladio_last_gpu_wait_us);
            *outPresentIndex = bufferIndex;
            pthread_mutex_lock(&clientsLock);
            frame->pendingIndex = -1;
            frame->pendingFence = EGL_NO_SYNC_KHR;
            pthread_mutex_unlock(&clientsLock);
            return true;
        }

        // the previous frame has had a whole frame's time on the GPU: usually its fence has passed already
        if (previousFence != EGL_NO_SYNC_KHR) {
            struct timespec t0, t1;
            clock_gettime(CLOCK_MONOTONIC, &t0);
            eglClientWaitSyncKHR(display, previousFence, EGL_SYNC_FLUSH_COMMANDS_BIT_KHR, EGL_FOREVER_KHR);
            clock_gettime(CLOCK_MONOTONIC, &t1);
            eglDestroySyncKHR(display, previousFence);
            *outPresentIndex = previousIndex;
            gladio_last_gpu_wait_us = (int)((t1.tv_sec - t0.tv_sec) * 1000000ll + (t1.tv_nsec - t0.tv_nsec) / 1000);
            countGpuWait(gladio_last_gpu_wait_us);
        }

        pthread_mutex_lock(&clientsLock);
        frame->pendingIndex = bufferIndex;
        frame->pendingFence = fence;
        pthread_mutex_unlock(&clientsLock);
        return true;
    }
    uint32_t* dst = frame->pixels;
    pthread_mutex_unlock(&clientsLock);
    if (!dst) return true;

    size_t pixels = (size_t)width * height;
    if (readBufferSize < pixels) {
        free(readBuffer);
        readBuffer = malloc(pixels * 4);
        readBufferSize = readBuffer ? pixels : 0;
        if (!readBuffer) return true;
    }

    GLint packBuffer = 0, packAlignment = 4, packRowLength = 0;
    glGetIntegerv(GL_PIXEL_PACK_BUFFER_BINDING, &packBuffer);
    glGetIntegerv(GL_PACK_ALIGNMENT, &packAlignment);
    glGetIntegerv(GL_PACK_ROW_LENGTH, &packRowLength);
    if (packBuffer) glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    glPixelStorei(GL_PACK_ALIGNMENT, 4);
    glPixelStorei(GL_PACK_ROW_LENGTH, 0);
    while (glGetError() != GL_NO_ERROR);

    // X wants BGRX rows from the top; GL gives rows from the bottom
    bool swizzle = false;
    if (bgraSupported != 0) {
        glReadPixels(0, 0, width, height, GL_BGRA_EXT, GL_UNSIGNED_BYTE, readBuffer);
        if (glGetError() != GL_NO_ERROR) bgraSupported = 0;
        else bgraSupported = 1;
    }
    if (bgraSupported == 0) {
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, readBuffer);
        swizzle = true;
    }

    for (int y = 0; y < height; y++) {
        const uint32_t* src = readBuffer + (size_t)(height - 1 - y) * width;
        uint32_t* row = dst + (size_t)y * width;
        if (swizzle) {
            for (int x = 0; x < width; x++) {
                uint32_t p = src[x];
                row[x] = 0xff000000u | ((p & 0xffu) << 16) | (p & 0xff00u) | ((p >> 16) & 0xffu);
            }
        }
        else {
            // opaque: an ARGB window would otherwise show through where the game left alpha below 1
            for (int x = 0; x < width; x++) row[x] = src[x] | 0xff000000u;
        }
    }

    if (packBuffer) glBindBuffer(GL_PIXEL_PACK_BUFFER, packBuffer);
    glPixelStorei(GL_PACK_ALIGNMENT, packAlignment);
    glPixelStorei(GL_PACK_ROW_LENGTH, packRowLength);
    return true;
}

// ------------------------------------------------------------------ the GLX requests of one client

static bool readFully(int fd, void* data, int size) {
    return size <= 0 || sock_read(fd, data, size) == size;
}

static bool skipBytes(int fd, int size) {
    char buffer[256];
    while (size > 0) {
        int chunk = size > (int)sizeof(buffer) ? (int)sizeof(buffer) : size;
        if (!readFully(fd, buffer, chunk)) return false;
        size -= chunk;
    }
    return true;
}

/* 32-byte reply; extra (padded to 4) follows. data[0..23] is what the client parses after the 8-byte header. */
static bool sendReply(int fd, bool success, const int* words, int numWords, const void* extra, int extraSize) {
    char reply[32] = {0};
    reply[0] = success ? 1 : 0;
    int extraWords = (extraSize + 3) / 4;
    *(int*)(reply + 4) = extraWords;
    if (numWords > 6) numWords = 6;
    if (words && numWords > 0) memcpy(reply + 8, words, numWords * 4);
    if (sock_write(fd, reply, 32) != 32) return false;
    if (extraWords > 0) {
        char* padded = calloc(extraWords, 4);
        if (!padded) return false;
        memcpy(padded, extra, extraSize);
        bool ok = sock_write(fd, padded, extraWords * 4) == extraWords * 4;
        free(padded);
        return ok;
    }
    return true;
}

static bool sendString(int fd, const char* string) {
    int length = (int)strlen(string);
    int words[6] = {0, length, 0, 0, 0, 0};
    return sendReply(fd, true, words, 6, string, length);
}

static GLXContext* createGLXContextFor(Client* client, int contextId, int shareId) {
    pthread_mutex_lock(&clientsLock);
    GLXContext* shared = shareId > 0 ? findEntry(client->glxContexts, client->numGLXContexts, shareId) : NULL;
    pthread_mutex_unlock(&clientsLock);

    GLXContext* context = createGLXContext(contextId, shared);
    if (context) {
        pthread_mutex_lock(&clientsLock);
        putEntry(client->glxContexts, &client->numGLXContexts, 64, contextId, context);
        pthread_mutex_unlock(&clientsLock);
    }
    LOGI("createGLXContext id=%d share=%d -> %p", contextId, shareId, context);
    return context;
}

static bool handleSetFrameBuffer(Client* client) {
    int data[3];
    if (!readFully(client->fd, data, sizeof(data))) return false;
    int drawable = data[0], width = data[1], height = data[2];

    int fds[1] = {-1};
    int numFds = 0;
    char byte;
    if (recv_fds(client->fd, fds, &numFds, &byte, 1) < 0 || numFds < 1) return false;

    size_t size = (size_t)width * height * 4;
    void* pixels = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fds[0], 0);
    close(fds[0]);
    bool ok = pixels != MAP_FAILED;

    pthread_mutex_lock(&clientsLock);
    Frame* frame = findFrame(client, drawable);
    if (!frame && client->numFrames < 8) {
        frame = &client->frames[client->numFrames++];
        memset(frame, 0, sizeof(Frame));
    }
    if (frame) {
        if (frame->pixels) munmap(frame->pixels, frame->size);
        for (int i = 0; i < frame->numBuffers && frame->numRetired < MAX_PRESENT_BUFFERS * 2; i++) frame->retired[frame->numRetired++] = frame->buffers[i];
        frame->numBuffers = 0;
        frame->drawable = drawable;
        frame->width = ok ? width : 0;
        frame->height = ok ? height : 0;
        frame->pixels = ok ? pixels : NULL;
        frame->size = ok ? size : 0;
    }
    else if (ok) munmap(pixels, size);
    pthread_mutex_unlock(&clientsLock);

    LOGI("frame buffer for window 0x%x: %dx%d %s", drawable, width, height, ok ? "mapped" : "FAILED");
    return sendReply(client->fd, ok, NULL, 0, NULL, 0);
}

typedef struct BufferHandover {
    AHardwareBuffer* buffer;
    int socket;
} BufferHandover;

/* Termux:X11 writes one byte into the socket it got with the pixmap, then takes the AHardwareBuffer from it. */
static void* handoverThread(void* param) {
    BufferHandover* handover = param;
    char byte;
    if (read(handover->socket, &byte, 1) == 1) {
        int result = AHardwareBuffer_sendHandleToUnixSocket(handover->buffer, handover->socket);
        if (result != 0) LOGE("AHardwareBuffer_sendHandleToUnixSocket: %d", result);
    }
    close(handover->socket);
    AHardwareBuffer_release(handover->buffer);
    free(handover);
    return NULL;
}

/* PRESENT_BUFFERS: [drawable, width, height, count] -> reply [count, stride], then one socket per buffer. */
static bool handlePresentBuffers(Client* client) {
    int data[4];
    if (!readFully(client->fd, data, sizeof(data))) return false;
    int drawable = data[0], width = data[1], height = data[2];
    int count = data[3] < MAX_PRESENT_BUFFERS ? data[3] : MAX_PRESENT_BUFFERS;

    EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(display, NULL, NULL);
    PresentBuffer buffers[MAX_PRESENT_BUFFERS] = {0};
    int sockets[MAX_PRESENT_BUFFERS];
    int created = 0;
    uint32_t stride = 0;
    for (; created < count; created++) {
        // Termux:X11 shows the pixmap as BGRA (X's byte order): an RGBX buffer came out with red and blue swapped
        AHardwareBuffer_Desc desc = {
            .width = width, .height = height, .layers = 1,
            .format = 5 /* AHARDWAREBUFFER_FORMAT_B8G8R8A8_UNORM */,
            .usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE,
        };
        if (AHardwareBuffer_allocate(&desc, &buffers[created].buffer) != 0) {
            LOGE("BGRA AHardwareBuffer not available, RGBX (red and blue will be swapped)");
            desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM;
            if (AHardwareBuffer_allocate(&desc, &buffers[created].buffer) != 0) break;
        }
        AHardwareBuffer_describe(buffers[created].buffer, &desc);
        stride = desc.stride;
        EGLClientBuffer clientBuffer = eglGetNativeClientBufferANDROID(buffers[created].buffer);
        const EGLint attributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
        buffers[created].image = eglCreateImageKHR(display, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attributes);
        if (buffers[created].image == EGL_NO_IMAGE_KHR) {
            AHardwareBuffer_release(buffers[created].buffer);
            break;
        }

        int pair[2];
        if (socketpair(AF_UNIX, SOCK_STREAM, 0, pair) != 0) {
            eglDestroyImageKHR(display, buffers[created].image);
            AHardwareBuffer_release(buffers[created].buffer);
            break;
        }
        BufferHandover* handover = malloc(sizeof(BufferHandover));
        handover->buffer = buffers[created].buffer;
        AHardwareBuffer_acquire(handover->buffer);
        handover->socket = pair[0];
        pthread_t thread;
        pthread_create(&thread, NULL, handoverThread, handover);
        pthread_detach(thread);
        sockets[created] = pair[1];
    }

    pthread_mutex_lock(&clientsLock);
    Frame* frame = findFrame(client, drawable);
    if (!frame && client->numFrames < 8) {
        frame = &client->frames[client->numFrames++];
        memset(frame, 0, sizeof(Frame));
    }
    if (frame) {
        for (int i = 0; i < frame->numBuffers && frame->numRetired < MAX_PRESENT_BUFFERS * 2; i++) frame->retired[frame->numRetired++] = frame->buffers[i];
        frame->drawable = drawable;
        frame->width = width;
        frame->height = height;
        frame->numBuffers = created;
        for (int i = 0; i < created; i++) frame->buffers[i] = buffers[i];
        // a fence of the old buffers is not waited for any more (its frame is dropped)
        if (frame->pendingFence != EGL_NO_SYNC_KHR) eglDestroySyncKHR(display, frame->pendingFence);
        frame->pendingFence = EGL_NO_SYNC_KHR;
        frame->pendingIndex = -1;
    }
    pthread_mutex_unlock(&clientsLock);

    LOGI("present buffers for window 0x%x: %d of %d, %dx%d stride %u", drawable, created, count, width, height, stride);
    int words[2] = {created, (int)stride};
    bool ok = sendReply(client->fd, created > 0, words, 2, NULL, 0);
    if (ok && created > 0) ok = send_fds(client->fd, sockets, created, NULL, 0) >= 0;
    for (int i = 0; i < created; i++) close(sockets[i]);
    return ok;
}

static bool handleRequest(Client* client, int opcode, int length) {
    int fd = client->fd;
    switch (opcode) {
        case OPCODE_CREATE_GL_CONTEXT: {
            int threadId;
            if (!readFully(fd, &threadId, 4)) return false;
            // createGLContext sends the two ring buffer fds to the client itself
            GLContext* context = createGLContext(fd, threadId);
            LOGI("createGLContext thread=%d -> %p", threadId, context);
            if (context) {
                pthread_mutex_lock(&clientsLock);
                putEntry(client->glContexts, &client->numGLContexts, 64, threadId, context);
                pthread_mutex_unlock(&clientsLock);
            }
            return true;
        }
        case OPCODE_DESTROY_GL_CONTEXT: {
            int id;
            if (!readFully(fd, &id, 4)) return false;
            pthread_mutex_lock(&clientsLock);
            GLContext* context = removeEntry(client->glContexts, &client->numGLContexts, id);
            pthread_mutex_unlock(&clientsLock);
            if (context) destroyGLContext(context);
            return sendReply(fd, true, NULL, 0, NULL, 0);
        }
        case OPCODE_CREATE_CONTEXT: {
            char data[17];
            if (!readFully(fd, data, sizeof(data))) return false;
            int contextId = *(int*)(data + 0);
            int shareId = *(int*)(data + 12);
            GLXContext* context = contextId ? createGLXContextFor(client, contextId, shareId) : NULL;
            return sendReply(fd, context != NULL, NULL, 0, NULL, 0);
        }
        case OPCODE_DESTROY_CONTEXT: {
            int id;
            if (!readFully(fd, &id, 4)) return false;
            pthread_mutex_lock(&clientsLock);
            GLXContext* context = removeEntry(client->glxContexts, &client->numGLXContexts, id);
            pthread_mutex_unlock(&clientsLock);
            if (context) destroyGLXContext(context);
            return true;
        }
        case OPCODE_QUERY_VERSION: {
            if (!skipBytes(fd, 8)) return false;
            int words[2] = {1, 4};
            return sendReply(fd, true, words, 2, NULL, 0);
        }
        case OPCODE_QUERY_EXTENSIONS_STRING: {
            if (!skipBytes(fd, 4)) return false;
            return sendString(fd, GLX_EXTENSIONS_STRING);
        }
        case OPCODE_QUERY_SERVER_STRING: {
            int data[2];
            if (!readFully(fd, data, sizeof(data))) return false;
            const char* string = data[1] == GLX_VENDOR ? "Gladio" : (data[1] == GLX_VERSION ? "1.4" : (data[1] == GLX_EXTENSIONS ? GLX_EXTENSIONS_STRING : ""));
            return sendString(fd, string);
        }
        case OPCODE_GET_FB_CONFIGS: {
            if (!skipBytes(fd, 4)) return false;
            static const int properties[] = {
                0x8013, 1,      /* GLX_FBCONFIG_ID */
                8, 8, 9, 8, 10, 8, 11, 8,   /* RED, GREEN, BLUE, ALPHA */
                12, 24,         /* DEPTH */
                13, 8,          /* STENCIL */
                2, 32,          /* BUFFER_SIZE */
                5, 1,           /* DOUBLEBUFFER */
                0x8010, 1,      /* DRAWABLE_TYPE = WINDOW_BIT */
                0x8011, 1,      /* RENDER_TYPE = RGBA_BIT */
            };
            int numProperties = (int)(sizeof(properties) / sizeof(properties[0]) / 2);
            int words[6] = {1, numProperties, 0, 0, 0, 0};
            return sendReply(fd, true, words, 6, properties, sizeof(properties));
        }
        case OPCODE_CREATE_CONTEXT_ATTRIBS_ARB: {
            int head[6];
            if (!readFully(fd, head, sizeof(head))) return false;
            int contextId = head[0];
            int shareId = head[3];
            int numAttribs = head[5];
            int major = 3, minor = 3;
            for (int i = 0; i < numAttribs; i++) {
                int pair[2];
                if (!readFully(fd, pair, sizeof(pair))) return false;
                if (pair[0] == GLX_CONTEXT_MAJOR_VERSION_ARB) major = pair[1];
                else if (pair[0] == GLX_CONTEXT_MINOR_VERSION_ARB) minor = pair[1];
            }
            // the GLES 3.2 driver underneath translates up to desktop GL 3.3
            bool supported = contextId != 0 && (major < 3 || (major == 3 && minor <= 3));
            GLXContext* context = supported ? createGLXContextFor(client, contextId, shareId) : NULL;
            LOGI("createContextAttribs %d.%d -> %s", major, minor, context ? "ok" : "refused");
            return sendReply(fd, context != NULL, NULL, 0, NULL, 0);
        }
        case OPCODE_SET_FRAME_BUFFER:
            return handleSetFrameBuffer(client);
        case OPCODE_PRESENT_BUFFERS:
            return handlePresentBuffers(client);
        default:
            LOGE("unknown request %d (length %d)", opcode, length);
            return skipBytes(fd, (length - 1) * 4);
    }
}

static void destroyClient(Client* client) {
    pthread_mutex_lock(&clientsLock);
    Client** link = &clients;
    while (*link && *link != client) link = &(*link)->next;
    if (*link) *link = client->next;
    pthread_mutex_unlock(&clientsLock);

    for (int i = 0; i < client->numGLContexts; i++) destroyGLContext(client->glContexts[i].value);
    for (int i = 0; i < client->numGLXContexts; i++) destroyGLXContext(client->glxContexts[i].value);
    for (int i = 0; i < client->numFrames; i++) if (client->frames[i].pixels) munmap(client->frames[i].pixels, client->frames[i].size);
    close(client->fd);
    free(client);
}

static void* clientThread(void* param) {
    Client* client = param;
    int fd = client->fd;

    // the connection setup the client sends (12 bytes, no authorization): success, protocol 11.0, nothing more
    char setup[12];
    if (readFully(fd, setup, sizeof(setup))) {
        char reply[8] = {1, 0, 11, 0, 0, 0, 0, 0};
        if (sock_write(fd, reply, sizeof(reply)) == sizeof(reply)) {
            LOGI("client fd=%d connected", fd);
            for (;;) {
                unsigned char header[4];
                if (!readFully(fd, header, sizeof(header))) break;
                int length = *(unsigned short*)(header + 2);
                if (!handleRequest(client, header[1], length)) break;
            }
        }
    }

    LOGI("client fd=%d gone", fd);
    destroyClient(client);
    eglReleaseThread(); // created contexts and images: EGL's thread data released before the thread ends (gl_context.c)
    return NULL;
}

static void* acceptThread(void* param) {
    int serverFd = (int)(intptr_t)param;
    for (;;) {
        int fd = accept(serverFd, NULL, NULL);
        if (fd < 0) {
            if (errno == EINTR) continue;
            LOGE("accept: %s", strerror(errno));
            sleep(1);
            continue;
        }
        Client* client = calloc(1, sizeof(Client));
        client->fd = fd;
        pthread_mutex_lock(&clientsLock);
        client->next = clients;
        clients = client;
        pthread_mutex_unlock(&clientsLock);

        pthread_t thread;
        pthread_create(&thread, NULL, clientThread, client);
        pthread_detach(thread);
    }
    return NULL;
}

/* false and why in error when not listening */
bool GladioServer_listen(const char* path, char* error, size_t errorSize) {
    if (started) return true;
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    struct sockaddr_un address = {0};
    address.sun_family = AF_UNIX;
    strncpy(address.sun_path, path, sizeof(address.sun_path) - 1);
    unlink(path);
    error[0] = 0;
    if (fd < 0) snprintf(error, errorSize, "socket: %s", strerror(errno));
    else if (bind(fd, (struct sockaddr*)&address, sizeof(address)) != 0) snprintf(error, errorSize, "bind %s: %s", path, strerror(errno));
    else if (listen(fd, 8) != 0) snprintf(error, errorSize, "listen: %s", strerror(errno));
    if (error[0]) {
        LOGE("%s", error);
        if (fd >= 0) close(fd);
        return false;
    }
    LOGI("listening on %s", path);
    pthread_t thread;
    pthread_create(&thread, NULL, acceptThread, (void*)(intptr_t)fd);
    pthread_detach(thread);
    started = true;
    return true;
}

/* null when listening, otherwise why not */
JNIEXPORT jstring JNICALL
Java_com_factory_gladio_GladioServer_start(JNIEnv* env, jclass cls, jstring jpath) {
    const char* path = (*env)->GetStringUTFChars(env, jpath, NULL);
    char error[256];
    bool ok = GladioServer_listen(path, error, sizeof(error));
    (*env)->ReleaseStringUTFChars(env, jpath, path);
    return ok ? NULL : (*env)->NewStringUTF(env, error);
}
