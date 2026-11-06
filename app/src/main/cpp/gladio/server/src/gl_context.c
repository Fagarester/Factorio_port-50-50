#include "gl_context.h"
#include "gl_renderer.h"
#include "gl_dsa.h"
#include "sysvshared_memory.h"
#include "request_handler.h"

EGLContext globalEGLContext = EGL_NO_CONTEXT;

pthread_mutex_t glx_context_mutex = PTHREAD_MUTEX_INITIALIZER;

// Window sizes, frame delivery and GLX contexts live in gladio_server.c (Termux:X11 instead of the Java X server).
#include "gladio_server.h"

static void createDisplayBuffer(GLContext* context) {
    currentRenderer->displayBuffer = GLFramebuffer_create();
    GLFramebuffer_bind(GL_FRAMEBUFFER, currentRenderer->displayBuffer);
    GLFramebuffer_setAttachment(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, context->displayBufAttachment.texture, 0);
    GLFramebuffer_setAttachment(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, context->displayBufAttachment.renderbuffer, 0);
}

static void destroyDisplayBuffer() {
    if (currentRenderer && currentRenderer->displayBuffer > 0) {
        GLuint displayBuffer = currentRenderer->displayBuffer;
        currentRenderer->displayBuffer = 0;
        ARRAYS_FILL(currentRenderer->clientState.framebuffer, MAX_FRAMEBUFFER_TARGETS, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        GLFramebuffer_delete(displayBuffer);
    }
}

static void createDisplayBufAttachment(GLContext* context) {
    short width = currentRenderer->displaySize[0];
    short height = currentRenderer->displaySize[1];

    if (context->displayBufAttachment.texture == 0) {
        glGenTextures(1, &context->displayBufAttachment.texture);
        glBindTexture(GL_TEXTURE_2D, context->displayBufAttachment.texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, PREFERRED_FRAMEBUFFER_FORMAT, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    if (context->displayBufAttachment.renderbuffer == 0) {
        glGenRenderbuffers(1, &context->displayBufAttachment.renderbuffer);
        glBindRenderbuffer(GL_RENDERBUFFER, context->displayBufAttachment.renderbuffer);
        glRenderbufferStorage(GL_RENDERBUFFER, PREFERRED_RENDERBUFFER_FORMAT, width, height);
        glBindRenderbuffer(GL_RENDERBUFFER, 0);
    }
}

static void destroyDisplayBufAttachment(GLContext* context) {
    if (context->displayBufAttachment.texture > 0) {
        glDeleteTextures(1, &context->displayBufAttachment.texture);
        context->displayBufAttachment.texture = 0;
    }

    if (context->displayBufAttachment.renderbuffer > 0) {
        glDeleteRenderbuffers(1, &context->displayBufAttachment.renderbuffer);
        context->displayBufAttachment.renderbuffer = 0;
    }
}

static void setCurrentRenderWindow(GLContext* context, int windowId) {
    if (windowId == 0 || !currentRenderer) return;

    short width = 0;
    short height = 0;
    GladioServer_getWindowSize(context->clientFd, windowId, &width, &height);
    if (width <= 0 || height <= 0) return;

    bool resized = currentRenderer->displayBuffer == 0 ||
                   currentRenderer->displaySize[0] != width ||
                   currentRenderer->displaySize[1] != height;

    if (resized) {
        destroyDisplayBuffer();
        destroyDisplayBufAttachment(context);

        currentRenderer->displaySize[0] = width;
        currentRenderer->displaySize[1] = height;

        createDisplayBufAttachment(context);
        createDisplayBuffer(context);

        ARRAYS_FILL(currentRenderer->clientState.framebuffer, MAX_FRAMEBUFFER_TARGETS, 0);
        GLRenderer_setDrawBuffer(currentRenderer, GL_BACK);

        GLTexture* texture = GLTexture_getBound(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, texture ? texture->id : 0);

        glViewport(0, 0, width, height);
        glScissor(0, 0, width, height);
    }
}

static void swapDisplayBuffers(GLContext* context, int drawableId, int bufferIndex, int* outPresentIndex) {
    if (!currentRenderer) return;
    GLuint framebuffer = currentRenderer->clientState.framebuffer[indexOfGLTarget(GL_FRAMEBUFFER)];
    GLuint drawFramebuffer = currentRenderer->clientState.framebuffer[indexOfGLTarget(GL_DRAW_FRAMEBUFFER)];
    if (framebuffer != drawFramebuffer) GLFramebuffer_bind(GL_FRAMEBUFFER, drawFramebuffer);

    bool result = GladioServer_updateWindowContent(context->clientFd, drawableId, currentRenderer->displaySize[0], currentRenderer->displaySize[1], bufferIndex, outPresentIndex);
    if (result) {
        GLTexture* texture = GLTexture_getBound(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, texture ? texture->id : 0);
    }
    else {
        destroyDisplayBuffer();
        destroyDisplayBufAttachment(context);
        setCurrentRenderWindow(context, drawableId);
    }
}

static bool isCanDrawImmediate(short requestCode) {
    return requestCode == REQUEST_CODE_GL_BEGIN ||
           requestCode == REQUEST_CODE_GL_END ||
           requestCode == REQUEST_CODE_GL_DRAW_ARRAYS ||
           requestCode == REQUEST_CODE_GL_DRAW_ELEMENTS ||
           requestCode == REQUEST_CODE_GL_ENABLE_CLIENT_STATE ||
           requestCode == REQUEST_CODE_GL_DISABLE_CLIENT_STATE ||
           requestCode == REQUEST_CODE_GL_VERTEX_POINTER ||
           requestCode == REQUEST_CODE_GL_COLOR_POINTER ||
           requestCode == REQUEST_CODE_GL_NORMAL_POINTER ||
           requestCode == REQUEST_CODE_GL_TEX_COORD_POINTER ||
           requestCode == REQUEST_CODE_GL_VERTEX4F ||
           requestCode == REQUEST_CODE_GL_COLOR4F ||
           requestCode == REQUEST_CODE_GL_NORMAL3F ||
           requestCode == REQUEST_CODE_GL_TEX_COORD4F ||
           requestCode == REQUEST_CODE_GL_MULTI_TEX_COORD4F ||
           requestCode == REQUEST_CODE_GL_ARRAY_ELEMENT ? false : true;
}

// GLADIO_SERVER_PROFILE=1: time spent in each request's handler (GLES driver calls included), every 5 s to stderr
static int serverProfile = -1;
static uint64_t profileNs[1024], profileCount[1024], profileStart = 0, profileTotal = 0, profileFrames = 0;

static inline uint64_t profileNow(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (uint64_t)t.tv_sec * 1000000000u + t.tv_nsec;
}

static void profileRequest(short requestCode, uint64_t ns) {
    profileNs[requestCode & 1023] += ns;
    profileCount[requestCode & 1023]++;
    profileTotal += ns;
    uint64_t now = profileNow();
    if (!profileStart) profileStart = now;
    if (now - profileStart < 5000000000u) return;
    double frames = profileFrames ? (double)profileFrames : 1.0;
    char top[900] = "";
    for (int n = 0; n < 14; n++) {
        int best = -1;
        for (int i = 0; i < 1024; i++) if (profileNs[i] && (best < 0 || profileNs[i] > profileNs[best])) best = i;
        if (best < 0) break;
        size_t used = strlen(top);
        snprintf(top + used, sizeof(top) - used, " %s=%.2fms/%.0f(%.0fns)", requestCodeToString(best),
                 profileNs[best] / 1e6 / frames, profileCount[best] / frames, (double)profileNs[best] / profileCount[best]);
        profileNs[best] = 0;
    }
    fprintf(stderr, "gladio-server profile: %.0f frames, in handlers %.1f ms per frame:%s\n", frames, profileTotal / 1e6 / frames, top);
    memset(profileNs, 0, sizeof(profileNs));
    memset(profileCount, 0, sizeof(profileCount));
    profileTotal = profileFrames = 0;
    profileStart = now;
}

// GLADIO_TEX_LOG=<n>: the first n texture requests with their ints and the bound texture, to stderr (debugging)
static int texLog = -1;

static void logTextureRequest(GLContext* context, short requestCode) {
    switch (requestCode) {
        case REQUEST_CODE_GL_TEX_IMAGE2D: case REQUEST_CODE_GL_TEX_IMAGE3D: case REQUEST_CODE_GL_COMPRESSED_TEX_IMAGE2D:
        case REQUEST_CODE_GL_COMPRESSED_TEX_SUB_IMAGE2D: case REQUEST_CODE_GL_TEX_SUB_IMAGE2D: case REQUEST_CODE_GL_TEX_SUB_IMAGE3D:
        case REQUEST_CODE_GL_TEX_PARAMETERF: case REQUEST_CODE_GL_TEX_PARAMETERFV: case REQUEST_CODE_GL_GENERATE_MIPMAP:
        case REQUEST_CODE_GL_SAMPLER_PARAMETERF: case REQUEST_CODE_GL_SAMPLER_PARAMETERFV: break;
        default: return;
    }
    texLog--;
    const int* ints = (const int*)(context->inputBuffer.buffer + context->inputBuffer.position);
    int n = (context->inputBuffer.size - context->inputBuffer.position) / 4;
    if (n > 11) n = 11;
    char text[200] = "";
    for (int i = 0; i < n; i++) snprintf(text + strlen(text), sizeof(text) - strlen(text), " %d", ints[i]);
    GLint bound2D = 0, boundArray = 0;
    glGetIntegerv(GL_TEXTURE_BINDING_2D, &bound2D);
    glGetIntegerv(GL_TEXTURE_BINDING_2D_ARRAY, &boundArray);
    fprintf(stderr, "gladio-tex: %s tex2d=%d array=%d:%s\n", requestCodeToString(requestCode), bound2D, boundArray, text);
}

static inline void runHandler(GLContext* context, short requestCode, HandleRequestFunc func) {
    if (texLog < 0) texLog = getenv("GLADIO_TEX_LOG") ? atoi(getenv("GLADIO_TEX_LOG")) : 0;
    if (texLog > 0) logTextureRequest(context, requestCode);
    if (serverProfile < 0) serverProfile = getenv("GLADIO_SERVER_PROFILE") && atoi(getenv("GLADIO_SERVER_PROFILE")) > 0;
    if (!serverProfile) {
        func(context);
        return;
    }
    uint64_t start = profileNow();
    func(context);
    profileRequest(requestCode, profileNow() - start);
}

// the first GL errors go to the game's log (see gladioServerLog); checked only early, glGetError after every request is not free
// GLADIO_SERVER_ERRORS=1: after every request for the whole game (slow), with the request's name
static void checkGLErrorEarly(short requestCode) {
    static int checksLeft = -1;
    static int errorsLeft = 40;
    if (checksLeft < 0) {
        const char* always = getenv("GLADIO_SERVER_ERRORS");
        checksLeft = always && atoi(always) > 0 ? INT32_MAX : 30000;
        if (checksLeft == INT32_MAX) errorsLeft = 200;
    }
    if (checksLeft > 0 && errorsLeft > 0) {
        checksLeft--;
        GLenum error = glGetError();
        if (error != GL_NO_ERROR) {
            errorsLeft--;
            gladioServerLog("glError 0x%x after request %s (%d)", error, requestCodeToString(requestCode), (int)requestCode);
        }
    }
}

// Stage 3c: requests whose handler takes everything from the request itself (no pixels read from the ring after it,
// no big reply written into the ring) and that are not immediate mode (glBegin/glEnd keep pointers into the request).
// 1 = the queued immediate mode drawing goes first (as in the loop), 2 = not (draws, client arrays: isCanDrawImmediate)
static unsigned char directRequests[REQUEST_CODE_GL_CALL_COUNT];

static void initDirectRequests(void) {
    static const HandleRequestFunc ringReaders[] = {
        gd_handle_glCompressedTexImage2D, gd_handle_glCompressedTexSubImage2D, gd_handle_glDrawPixels, gd_handle_glEnd,
        gd_handle_glTexImage2D, gd_handle_glTexImage3D, gd_handle_glTexSubImage2D, gd_handle_glTexSubImage3D,
        gd_handle_glReadPixels, gd_handle_glGetTexImage, gd_handle_glGetCompressedTexImage,
    };
    for (int i = 0; i < REQUEST_CODE_GL_CALL_COUNT; i++) {
        short requestCode = REQUEST_CODE_GL_CALL_START + i;
        HandleRequestFunc func = getHandleRequestFunc(requestCode);
        bool direct = func != NULL &&
                      requestCode != REQUEST_CODE_GL_BEGIN && requestCode != REQUEST_CODE_GL_END &&
                      requestCode != REQUEST_CODE_GL_VERTEX4F && requestCode != REQUEST_CODE_GL_COLOR4F &&
                      requestCode != REQUEST_CODE_GL_NORMAL3F && requestCode != REQUEST_CODE_GL_TEX_COORD4F &&
                      requestCode != REQUEST_CODE_GL_MULTI_TEX_COORD4F && requestCode != REQUEST_CODE_GL_ARRAY_ELEMENT;
        for (int j = 0; direct && j < (int)(sizeof(ringReaders) / sizeof(ringReaders[0])); j++) if (func == ringReaders[j]) direct = false;
        directRequests[i] = !direct ? 0 : isCanDrawImmediate(requestCode) ? 1 : 2;
    }
}

const unsigned char* getDirectRequests(int* outStart, int* outCount) {
    static pthread_once_t once = PTHREAD_ONCE_INIT;
    pthread_once(&once, initDirectRequests);
    *outStart = REQUEST_CODE_GL_CALL_START;
    *outCount = REQUEST_CODE_GL_CALL_COUNT;
    return directRequests;
}

/* A request the client hands over on this thread instead of through the ring; false = not now (send it the usual way). */
bool handleRequestDirect(GLContext* context, short requestCode, void* data, int size) {
    int index = requestCode - REQUEST_CODE_GL_CALL_START;
    if (!context->idle || RingBuffer_size(context->serverRing) != 0) return false;
    if (index < 0 || index >= REQUEST_CODE_GL_CALL_COUNT || !directRequests[index]) return false;
    if (directRequests[index] == 1) GLRenderer_drawImmediate(currentRenderer); // as the loop does
    ArrayBuffer request = context->inputBuffer;
    context->inputBuffer = (ArrayBuffer){.size = size, .position = 0, .capacity = size, .buffer = data};
    runHandler(context, requestCode, getHandleRequestFunc(requestCode));
    context->inputBuffer = request;
    checkGLErrorEarly(requestCode);
    return true;
}

static void* requestHandlerThread(void* param) {
    GLContext* context = param;
    short requestCode;

    while (context->running) {
        // idle: between requests, waiting for the next one (only then the client may hand one over directly, 3c)
        context->idle = true;
        bool waited = RingBuffer_waitForRead(context->serverRing, HEADER_SIZE);
        context->idle = false;
        if (!waited || !gl_recv(context->serverRing, &requestCode, &context->inputBuffer)) break;

        if (isCanDrawImmediate(requestCode)) GLRenderer_drawImmediate(currentRenderer);

        switch (requestCode) {
            case REQUEST_CODE_SET_CURRENT_RENDER_WINDOW: {
                int windowId = ArrayBuffer_getInt(&context->inputBuffer);
                int contextId = ArrayBuffer_getInt(&context->inputBuffer);
                GLXContext* glxContext = GladioServer_getGLXContext(context->clientFd, contextId);

                if (glxContext && context->glxContext != glxContext) {
                    destroyDisplayBuffer();
                    eglMakeCurrent(eglGetDisplay(EGL_DEFAULT_DISPLAY), EGL_NO_SURFACE, EGL_NO_SURFACE, glxContext->eglContext);
                    context->glxContext = glxContext;
                    currentRenderer = &glxContext->renderer;
                    GLRenderer_resetFrameCount(currentRenderer);
                }

                setCurrentRenderWindow(context, windowId);
                {
                    static int helloSent = 0;
                    if (helloSent++ < 2) gladioServerLog("server channel ok; GLES: %s / %s", (const char*)glGetString(GL_VERSION), (const char*)glGetString(GL_RENDERER));
                }
                gl_send(context->clientRing, REQUEST_CODE_SET_CURRENT_RENDER_WINDOW, NULL, 0);
                break;
            }
            case REQUEST_CODE_SWAP_DISPLAY_BUFFERS: {
                int drawableId = ArrayBuffer_getInt(&context->inputBuffer);
                // Factory: the client's free present buffer (AHardwareBuffer), -1 = shared memory frame
                int bufferIndex = context->inputBuffer.position + 4 <= context->inputBuffer.size ? ArrayBuffer_getInt(&context->inputBuffer) : -1;
                int flags = context->inputBuffer.position + 4 <= context->inputBuffer.size ? ArrayBuffer_getInt(&context->inputBuffer) : 0;
                gladio_present_now = (flags & 1) != 0;
                currentRenderer->frameCount++;
                // the reply carries the present buffer whose frame is finished (the one before this, or this one with
                // "present now"), -1 = none; then the frame time trace: server time since the last swap, of it waiting
                // for requests, GPU fence wait, present copy
                static __thread uint64_t lastSwapUs = 0;
                struct timespec now;
                clock_gettime(CLOCK_MONOTONIC, &now);
                uint64_t swapUs = (uint64_t)now.tv_sec * 1000000u + now.tv_nsec / 1000;
                int reply[5] = {-1, lastSwapUs ? (int)(swapUs - lastSwapUs) : 0, (int)ring_read_wait_us, 0, 0};
                uint64_t swapStart = serverProfile > 0 ? profileNow() : 0;
                swapDisplayBuffers(context, drawableId, bufferIndex, &reply[0]);
                if (serverProfile > 0) {
                    profileFrames++;
                    profileRequest(REQUEST_CODE_SWAP_DISPLAY_BUFFERS, profileNow() - swapStart);
                }
                reply[3] = gladio_last_gpu_wait_us;
                reply[4] = gladio_last_blit_us;
                gl_send(context->clientRing, REQUEST_CODE_SWAP_DISPLAY_BUFFERS, reply, sizeof(reply));
                clock_gettime(CLOCK_MONOTONIC, &now);
                lastSwapUs = (uint64_t)now.tv_sec * 1000000u + now.tv_nsec / 1000;
                ring_read_wait_us = 0;
                break;
            }
            default: {
                if (requestCode >= REQUEST_CODE_GL_DSA_START && requestCode < REQUEST_CODE_GL_CALL_START) {
                    handleDSARequest(context, requestCode);
                    break;
                }

#if IS_DEBUG_ENABLED(DEBUG_MODE_HANDLE_REQUEST)
                println("handleRequest name=%s size=%d", requestCodeToString(requestCode), context->inputBuffer.size);
#endif

                HandleRequestFunc handleRequestFunc = getHandleRequestFunc(requestCode);
                if (handleRequestFunc) runHandler(context, requestCode, handleRequestFunc);
                break;
            }
        }

#if IS_DEBUG_ENABLED(DEBUG_MODE_GL_ERROR)
        GLenum error = glGetError();
        if (error != GL_NO_ERROR) println("gladio: glError %s %x", requestCodeToString(requestCode), error);
#else
        checkGLErrorEarly(requestCode);
#endif
    }

    destroyDisplayBuffer();
    destroyDisplayBufAttachment(context);
    if (context->glxContext) {
        eglMakeCurrent(eglGetDisplay(EGL_DEFAULT_DISPLAY), EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        context->glxContext = NULL;
    }
    // the EGL library's per-thread data freed now, while the thread is whole: ANGLE through libhybris crashed in its
    // own thread-exit callback (egl::ReleaseThread) when this thread ended (the first GL context, destroyed at startup)
    eglReleaseThread();
    return NULL;
}

bool (*gladio_sync_create)(GLContext* context, int threadId, void* (*handler)(void*)) = NULL;
void (*gladio_sync_destroy)(GLContext* context) = NULL;

GLContext* createGLContext(int clientFd, int threadId) {
    GLContext* context = calloc(1, sizeof(GLContext));
    context->clientFd = clientFd;

    int shmFds[2];
    shmFds[0] = ashmemCreateRegion("gl-server-ring", RingBuffer_getSHMemSize(SERVER_RING_BUFFER_SIZE));
    shmFds[1] = ashmemCreateRegion("gl-client-ring", RingBuffer_getSHMemSize(CLIENT_RING_BUFFER_SIZE));

    context->serverRing = RingBuffer_create(shmFds[0], SERVER_RING_BUFFER_SIZE);
    if (!context->serverRing) goto error;

    context->clientRing = RingBuffer_create(shmFds[1], CLIENT_RING_BUFFER_SIZE);
    if (!context->clientRing) goto error;

    context->threadPool = ThreadPool_init(THREAD_POOL_NUM_THREADS);
    context->running = true;
    // before the fds go out: the client looks the coroutine up as soon as it has them
    if (!(gladio_sync_create && gladio_sync_create(context, threadId, requestHandlerThread)))
        pthread_create(&context->requestHandlerThread, NULL, requestHandlerThread, context);

    int result = send_fds(clientFd, shmFds, 2, NULL, 0);
    if (result < 0) {
        // the handler waits for requests that never come
        destroyGLContext(context);
        CLOSEFD(shmFds[0]);
        CLOSEFD(shmFds[1]);
        return NULL;
    }

    CLOSEFD(shmFds[0]);
    CLOSEFD(shmFds[1]);
    return context;

error:
    CLOSEFD(shmFds[0]);
    CLOSEFD(shmFds[1]);
    MEMFREE(context);
    return NULL;
}

void destroyGLContext(GLContext* context) {
    context->running = false;

    if (context->requestHandlerThread) {
        RingBuffer_setStatus(context->serverRing, RING_STATUS_EXIT);
        RingBuffer_setStatus(context->clientRing, RING_STATUS_EXIT);
        pthread_join(context->requestHandlerThread, NULL);

        ThreadPool_destroy(context->threadPool);
        context->threadPool = NULL;

        context->requestHandlerThread = 0;
        RingBuffer_free(context->serverRing);
        RingBuffer_free(context->clientRing);
    }
    else if (context->sync) {
        // the client finished the coroutine on its own thread (its EGL context is current there), see direct/sync.c
        gladio_sync_destroy(context);
        ThreadPool_destroy(context->threadPool);
        context->threadPool = NULL;
        RingBuffer_free(context->serverRing);
        RingBuffer_free(context->clientRing);
    }


    ArrayBuffer_free(&context->inputBuffer);
    ArrayBuffer_free(&context->outputBuffer);

    free(context);
}

GLXContext* createGLXContext(int contextId, GLXContext* sharedContext) {
    static const EGLint confAttribList[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    static const EGLint ctxAttribList[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    EGLBoolean success;

    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (!eglDisplay) return NULL;

    EGLint major, minor;
    success = eglInitialize(eglDisplay, &major, &minor);
    if (!success) return NULL;

    int numConfigs;
    EGLConfig eglConfig;
    success = eglChooseConfig(eglDisplay, confAttribList, &eglConfig, 1, &numConfigs);
    if (!success || numConfigs != 1) return NULL;

    EGLContext eglContext = eglCreateContext(eglDisplay, eglConfig, sharedContext ? sharedContext->eglContext : globalEGLContext, ctxAttribList);

    GLXContext* context = calloc(1, sizeof(GLXContext));
    context->eglContext = eglContext;
    context->renderer.contextId = contextId;
    GLVertexArrayObject_setBound(&context->renderer.clientState, 0);
    GLClientState_init(&context->renderer.clientState, sharedContext ? &sharedContext->renderer.clientState : NULL);

    GLX_CONTEXT_LOCK();
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, context->eglContext);
    GLRenderer_initOnEGLContext(&context->renderer);
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    GLX_CONTEXT_UNLOCK();
    return context;
}

void destroyGLXContext(GLXContext* context) {
    if (!context) return;
    // The full teardown (GLClientState_destroy + GLRenderer_destroy) aborted the whole app with a Scudo "misaligned pointer when deallocating"
    // from killAllConnections when the game was left. Only the GPU side is released here; the small CPU-side structures are left to the
    // process (they are not shared with other contexts and the leak is bounded by the number of GL contexts a game session creates).
    GLX_CONTEXT_LOCK();
    EGLDisplay eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, context->eglContext);
    glDeleteBuffers(ARRAY_SIZE(context->renderer.bufferIds), context->renderer.bufferIds);
    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    GLX_CONTEXT_UNLOCK();
    eglDestroyContext(eglDisplay, context->eglContext);
}

static void internalReadVertexArrayElement(GLContext* context, int arrayIdx, int elementIdx, ArrayBuffer* dstBuffer, float* constValue) {
#define PARSE_VALUESF(glType, divVal) \
    const glType* values = srcValues; \
    for (int i = 0; i < vertexAttrib->size; i++) dstValues[i] = arrayIdx == COLOR_ARRAY_INDEX ? (float)values[i] / divVal : (float)values[i]

    GLClientState* clientState = &currentRenderer->clientState;
    if (!clientState->vao->attribs[arrayIdx].state) {
        if (constValue && dstBuffer->position > 0) {
            if (arrayIdx == NORMAL_ARRAY_INDEX) {
                ArrayBuffer_putBytes(dstBuffer, constValue, 3 * sizeof(float));
            }
            else ArrayBuffer_putBytes(dstBuffer, constValue, 4 * sizeof(float));
        }
        return;
    }

    GLVertexAttrib* vertexAttrib = &clientState->vao->attribs[arrayIdx];

    float* dstValues = NULL;
    if (arrayIdx == NORMAL_ARRAY_INDEX) {
        dstValues = ArrayBuffer_putFloat3(dstBuffer, 0.0f, 0.0f, 1.0f);
    }
    else dstValues = ArrayBuffer_putFloat4(dstBuffer, 0.0f, 0.0f, 0.0f, 1.0f);
    dstBuffer->position++;

    void* srcValues;
    if (vertexAttrib->pointer) {
        srcValues = vertexAttrib->pointer + (elementIdx - clientState->indexStart) * vertexAttrib->stride;
    }
    else srcValues = ArrayBuffer_getBytes(&context->inputBuffer, vertexAttrib->stride);

    switch (vertexAttrib->type) {
        case GL_FLOAT: {
            memcpy(dstValues, srcValues, vertexAttrib->size * sizeof(float));
            break;
        }
        case GL_HALF_FLOAT: {
            const GLhalf* values = srcValues;
            _Float16 value;
            for (int i = 0; i < vertexAttrib->size; i++) {
                memcpy(&value, &values[i], sizeof(value));
                dstValues[i] = (float)value;
            }
            break;
        }
        case GL_BYTE: {
            PARSE_VALUESF(GLbyte, INT8_MAX);
            break;
        }
        case GL_UNSIGNED_BYTE: {
            PARSE_VALUESF(GLubyte, UINT8_MAX);
            break;
        }
        case GL_SHORT: {
            PARSE_VALUESF(GLshort, INT16_MAX);
            break;
        }
        case GL_UNSIGNED_SHORT: {
            PARSE_VALUESF(GLushort, UINT16_MAX);
            break;
        }
        case GL_INT: {
            PARSE_VALUESF(GLint, (double)INT32_MAX);
            break;
        }
        case GL_UNSIGNED_INT: {
            PARSE_VALUESF(GLuint, (double)UINT32_MAX);
            break;
        }
        case GL_DOUBLE: {
            PARSE_VALUESF(GLdouble, 1);
            break;
        }
    }

#undef PARSE_VALUESF
}

void readCommandBuffer(GLContext* context) {
    if (ArrayBuffer_available(&context->inputBuffer) == 0) {
        GLRenderer_endImmediate(currentRenderer);
        return;
    }

    GLenum mode = ArrayBuffer_getInt(&context->inputBuffer);
    int indexStart = ArrayBuffer_getInt(&context->inputBuffer);
    int indexCount = ArrayBuffer_getInt(&context->inputBuffer);
    int bufferSize = ArrayBuffer_getInt(&context->inputBuffer);

    if (bufferSize == 0) return;

    GLClientState* clientState = &currentRenderer->clientState;
    clientState->indexStart = indexStart;
    if (indexCount > 0) {
        for (int i = 0; i < clientState->vao->maxEnabledAttribs; i++) {
            if (clientState->vao->attribs[i].state) {
                clientState->vao->attribs[i].pointer = context->inputBuffer.buffer + context->inputBuffer.position;
                context->inputBuffer.position += indexCount * clientState->vao->attribs[i].stride;
            }
        }
    }
    else ArrayBuffer_rewind(&context->inputBuffer);

    ENSURE_ARRAY_CAPACITY(context->inputBuffer.size + bufferSize, context->inputBuffer.capacity, context->inputBuffer.buffer, 1);
    RingBuffer_read(context->serverRing, context->inputBuffer.buffer + context->inputBuffer.position, bufferSize);
    context->inputBuffer.size += bufferSize;

    GLRenderer_beginImmediate(currentRenderer, mode);
    while (ArrayBuffer_available(&context->inputBuffer) > 0) {
        short requestCode = ArrayBuffer_getShort(&context->inputBuffer);
        HandleRequestFunc handleRequestFunc = getHandleRequestFunc(requestCode);
        if (!handleRequestFunc) break;
        handleRequestFunc(context);
    }
    GLRenderer_endImmediate(currentRenderer);

    for (int i = 0; i < clientState->vao->maxEnabledAttribs; i++) {
        if (clientState->vao->attribs[i].state) clientState->vao->attribs[i].pointer = NULL;
    }
    clientState->indexStart = 0;
}

void readVertexArrayElement(GLContext* context, int arrayIdx, int elementIdx) {
    switch (arrayIdx) {
        case POSITION_ARRAY_INDEX:
            internalReadVertexArrayElement(context, arrayIdx, elementIdx, &currentRenderer->geometry.vertices, NULL);
            break;
        case COLOR_ARRAY_INDEX:
            internalReadVertexArrayElement(context, arrayIdx, elementIdx, &currentRenderer->geometry.colors, currentRenderer->state.color);
            break;
        case NORMAL_ARRAY_INDEX:
            internalReadVertexArrayElement(context, arrayIdx, elementIdx, &currentRenderer->geometry.normals, currentRenderer->state.normal);
            break;
        default:
            if (arrayIdx >= TEXCOORD_ARRAY_INDEX) {
                int index = arrayIdx - TEXCOORD_ARRAY_INDEX;
                internalReadVertexArrayElement(context, arrayIdx, elementIdx, &currentRenderer->geometry.texCoords[index], currentRenderer->state.texCoords[index]);
            }
            break;
    }
}

bool readUnboundVertexArrays(GLContext* context, GLenum drawMode, int drawCount, void** outIndices, GLenum indexType) {
    if (indexType != GL_NONE) {
        if (GLBuffer_getBound(GL_ELEMENT_ARRAY_BUFFER)) {
            *outIndices = (void*)(uint64_t)ArrayBuffer_getInt(&context->inputBuffer);
        }
        else *outIndices = ArrayBuffer_getBytes(&context->inputBuffer, drawCount * sizeofGLType(indexType));
    }

    if (ArrayBuffer_available(&context->inputBuffer) == 0) return false;
    bool meshCreated = false;

    GLClientState* clientState = &currentRenderer->clientState;
    for (int i = 0, j; i < clientState->vao->maxEnabledAttribs; i++) {
        bool legacyEnabledWithProgram = GLClientState_isLegacyEnabledWithProgram(clientState, i);
        if (clientState->vao->attribs[i].state == VERTEX_ATTRIB_ENABLED || legacyEnabledWithProgram) {
            GLVertexAttrib* vertexAttrib = &clientState->vao->attribs[i];
            int size = MIN(4, vertexAttrib->size);

            int byteCount = ArrayBuffer_getInt(&context->inputBuffer);
            void* pointer = ArrayBuffer_getBytes(&context->inputBuffer, byteCount);

            GLBuffer* oldArrayBuffer = NULL;
            if (vertexAttrib->size == GL_BGRA) {
                uint64_t offset = (uint64_t)vertexAttrib->pointer;
                swapPixelsRedBlue(pointer + offset, vertexAttrib->stride, byteCount - offset);
                oldArrayBuffer = GLBuffer_getBound(GL_ARRAY_BUFFER);
                if (clientState->vao->bgraBuffer == 0) glGenBuffers(1, &clientState->vao->bgraBuffer);
                glBindBuffer(GL_ARRAY_BUFFER, clientState->vao->bgraBuffer);
                glBufferData(GL_ARRAY_BUFFER, byteCount, pointer, GL_DYNAMIC_DRAW);
                pointer = vertexAttrib->pointer;
            }

            int location = i;
            if (legacyEnabledWithProgram || clientState->arbProgram[0]) {
                if (clientState->program) {
                    location = clientState->program->location.attributes[i];
                }
                else location = clientState->arbProgram[0]->material->location.attributes[i];
                GLRenderer_enableVertexAttribute(currentRenderer, location);
            }

            glVertexAttribPointer(location, size, vertexAttrib->type, vertexAttrib->normalized, vertexAttrib->stride, pointer);
            if (oldArrayBuffer) glBindBuffer(GL_ARRAY_BUFFER, oldArrayBuffer->id);
        }
        else if (clientState->vao->attribs[i].state == VERTEX_ATTRIB_LEGACY_ENABLED) {
            if (!meshCreated) {
                GLRenderer_beginImmediate(currentRenderer, drawMode);
                meshCreated = true;
            }

            for (j = 0; j < drawCount; j++) {
                readVertexArrayElement(context, i, -1);
                if (i == POSITION_ARRAY_INDEX) {
                    GLRenderer_addArrayElement(currentRenderer, currentRenderer->geometry.vertices.position-1);
                }
            }
        }
    }
    if (meshCreated) GLRenderer_endImmediate(currentRenderer);
    return meshCreated;
}

const char* getGLExtensions(int* outNumExtensions) {
    static int numExtensions = 0;
    static const char* extensionNames = "GL_EXT_abgr GL_ARB_shadow GL_ARB_window_pos GL_EXT_packed_pixels GL_ARB_vertex_buffer_object GL_ARB_vertex_array_object GL_ARB_texture_border_clamp GL_ARB_texture_env_add GL_EXT_texture_env_add GL_EXT_draw_range_elements GL_EXT_bgra GL_ARB_texture_compression GL_EXT_texture_compression_s3tc GL_EXT_texture_compression_dxt1 GL_EXT_texture_compression_dxt3 GL_EXT_texture_compression_dxt5 GL_ARB_point_parameters GL_EXT_point_parameters GL_EXT_texture_edge_clamp GL_EXT_multi_draw_arrays GL_ARB_multisample GL_EXT_polygon_offset GL_ARB_draw_elements_base_vertex GL_ARB_texture_rectangle GL_EXT_vertex_array GL_ARB_vertex_array_bgra GL_ARB_texture_non_power_of_two GL_EXT_blend_color GL_EXT_blend_minmax GL_EXT_blend_equation_separate GL_EXT_blend_func_separate GL_EXT_blend_subtract GL_EXT_texture_filter_anisotropic GL_ARB_texture_mirrored_repeat GL_ARB_point_sprite GL_ARB_texture_cube_map GL_EXT_texture_cube_map GL_EXT_texture_rg GL_ARB_texture_rg GL_EXT_texture_float GL_ARB_texture_float GL_EXT_texture_half_float GL_EXT_color_buffer_float GL_EXT_color_buffer_half_float GL_EXT_depth_texture GL_ARB_depth_texture GL_ARB_depth_clamp GL_ARB_ES2_compatibility GL_ARB_fragment_shader GL_ARB_vertex_shader GL_ARB_shading_language_100 GL_ARB_draw_instanced GL_EXT_draw_instanced GL_ARB_instanced_arrays GL_EXT_instanced_arrays GL_ARB_framebuffer_object GL_EXT_framebuffer_object GL_EXT_packed_depth_stencil GL_EXT_framebuffer_blit GL_ARB_draw_buffers GL_ARB_internalformat_query GL_ARB_internalformat_query2 GL_ARB_map_buffer_range GL_ARB_draw_buffers_blend GL_ARB_multitexture GL_ARB_texture_env_combine GL_EXT_texture_env_combine GL_ARB_texture_env_dot3 GL_EXT_texture_env_dot3 GL_ARB_shader_objects GL_ARB_vertex_program GL_ARB_fragment_program GL_ARB_buffer_storage GL_EXT_buffer_storage GL_ARB_sync GL_ARB_sampler_objects GL_ARB_texture_multisample GL_ARB_color_buffer_float GL_ARB_occlusion_query GL_ARB_occlusion_query2 GL_EXT_direct_state_access GL_ARB_uniform_buffer_object GL_ARB_timer_query GL_EXT_timer_query GL_ARB_texture_swizzle GL_ARB_copy_buffer GL_ARB_depth_buffer_float GL_ARB_sample_shading GL_ARB_tessellation_shader GL_ARB_derivative_control GL_ARB_compute_shader GL_EXT_gpu_program_parameters";

    if (numExtensions == 0) {
        char* ptr = (char*)extensionNames;
        while ((ptr = strchr(ptr, ' '))) {
            while (*ptr == ' ') ptr++;
            numExtensions++;
        }
    }

    if (outNumExtensions) *outNumExtensions = numExtensions;
    return extensionNames;
}