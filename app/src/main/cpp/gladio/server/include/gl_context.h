#ifndef GLADIO_GL_CONTEXT_H
#define GLADIO_GL_CONTEXT_H

#include "gladio.h"
#include "gl_renderer.h"
#include "gl_texture.h"

typedef struct GLXContext {
    EGLContext eglContext;
    GLRenderer renderer;
} GLXContext;

typedef struct GLContext {
    GLXContext* glxContext;
    int clientFd;
    RingBuffer* clientRing;
    RingBuffer* serverRing;

    pthread_t requestHandlerThread;
    void* sync; // in the game's process: the handler runs on the client's thread (direct/sync.c), no thread of its own
    bool running;
    bool idle; // waiting for the next request (gl_context.c, handleRequestDirect)
    ArrayBuffer inputBuffer;
    ArrayBuffer outputBuffer;

    ThreadPool* threadPool;

    struct {
        GLuint renderbuffer;
        GLuint texture;
    } displayBufAttachment;

    GLuint savedDSATarget;
    GLuint savedDSAId;
} GLContext;

extern GLContext* createGLContext(int clientFd, int threadId);
// set by direct/sync.c when the server is in the game's process: the request handler of the client thread threadId
// becomes a coroutine the client runs itself; false = a thread as usual
extern bool (*gladio_sync_create)(GLContext* context, int threadId, void* (*handler)(void*));
extern void (*gladio_sync_destroy)(GLContext* context);
extern void destroyGLContext(GLContext* context);
// stage 3c (in the game's process, on the client's thread): see gl_context.c
extern const unsigned char* getDirectRequests(int* outStart, int* outCount);
extern bool handleRequestDirect(GLContext* context, short requestCode, void* data, int size);
extern GLXContext* createGLXContext(int contextId, GLXContext* sharedContext);
extern void destroyGLXContext(GLXContext* glxContext);
extern void readCommandBuffer(GLContext* context);
extern void readVertexArrayElement(GLContext* context, int arrayIdx, int elementIdx);
extern bool readUnboundVertexArrays(GLContext* context, GLenum drawMode, int drawCount, void** outIndices, GLenum indexType);
extern const char* getGLExtensions(int* numExtensions);

extern pthread_mutex_t glx_context_mutex;

#define GLX_CONTEXT_LOCK() pthread_mutex_lock(&glx_context_mutex)
#define GLX_CONTEXT_UNLOCK() pthread_mutex_unlock(&glx_context_mutex)

#endif