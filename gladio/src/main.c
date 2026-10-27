#include <sys/socket.h>
#include <sys/un.h>
#include <errno.h>
#include <sys/mman.h>

#include <stdarg.h>
#include "gladio.h"
#include "gl_context.h"

int serverFd = -1;
ArrayBuffer outputBuffer = {0};
ArrayBuffer inputBuffer = {0};
SparseArray glxContexts = {0};
thread_local GLContext* currentGLContext = NULL;

static SparseArray cachedStrings = {0};

char* getCachedString(int name) {
    return SparseArray_get(&cachedStrings, name);
}

char* putCachedString(int name, char* string, int length) {
    char* cachedString = malloc(length + 1);
    memcpy(cachedString, string, length);
    cachedString[length] = '\0';
    SparseArray_put(&cachedStrings, name, cachedString);
    return cachedString;
}

void getRangeIndices(const void* indices, GLsizei count, GLenum indexType, GLint basevertex, GLuint* range) {
    range[0] = INT32_MAX;
    range[1] = 0;
    switch (indexType) {
        case GL_UNSIGNED_BYTE: {
            GLubyte* values = indices;
            for (int i = 0; i < count; i++) {
                range[0] = MIN(values[i] + basevertex, range[0]);
                range[1] = MAX(values[i] + basevertex, range[1]);
            }
            break;
        }
        case GL_UNSIGNED_SHORT: {
            GLushort* values = indices;
            for (int i = 0; i < count; i++) {
                range[0] = MIN(values[i] + basevertex, range[0]);
                range[1] = MAX(values[i] + basevertex, range[1]);
            }
            break;
        }
        case GL_UNSIGNED_INT: {
            GLuint* values = indices;
            for (int i = 0; i < count; i++) {
                range[0] = MIN(values[i] + basevertex, range[0]);
                range[1] = MAX(values[i] + basevertex, range[1]);
            }
            break;
        }
    }
}

void writeUnboundVertexArrays(GLint first, GLsizei count, const void* indices, GLenum indexType, GLint basevertex) {
    GLClientState* clientState = currentGLContext->clientState;
    if (indexType != GL_NONE) {
        GLBuffer* elementArrayBuffer = GLBuffer_getBound(GL_ELEMENT_ARRAY_BUFFER);
        if (elementArrayBuffer) {
            ArrayBuffer_putInt(&outputBuffer, (uint64_t)indices);
            indices = elementArrayBuffer->mappedData + (uint64_t)indices;
        }
        else ArrayBuffer_putBytes(&outputBuffer, indices, count * sizeofGLType(indexType));

        int indexCount = 0;
        for (int i = 0, j, index; i < clientState->vao->maxEnabledAttribs; i++) {
            GLVertexAttrib* attrib = &clientState->vao->attribs[i];
            int64_t limit;
            void* data = attrib->state ? GLVertexAttrib_resolve(clientState, attrib, &limit) : NULL;
            if (attrib->state && (data || attrib->bufferId)) {
                short stride = attrib->stride;
                if (attrib->state == VERTEX_ATTRIB_ENABLED || GLClientState_isLegacyEnabledWithProgram(clientState, i)) {
                    if (indexCount == 0) {
                        GLuint range[2];
                        getRangeIndices(indices, count, indexType, basevertex, range);
                        indexCount = range[1] + 1;
                    }
                    ArrayBuffer_putInt(&outputBuffer, indexCount * stride);
                    GLVertexAttrib_putBytes(&outputBuffer, data, 0, (int64_t)indexCount * stride, limit);
                }
                else if (attrib->state == VERTEX_ATTRIB_LEGACY_ENABLED) {
                    for (j = 0; j < count; j++) {
                        switch (indexType) {
                            case GL_UNSIGNED_BYTE:
                                index = ((GLubyte*)indices)[j] + basevertex;
                                break;
                            case GL_UNSIGNED_SHORT:
                                index = ((GLushort*)indices)[j] + basevertex;
                                break;
                            case  GL_UNSIGNED_INT:
                                index = ((GLuint*)indices)[j] + basevertex;
                                break;
                            default:
                                index = 0;
                                break;
                        }

                        GLVertexAttrib_putBytes(&outputBuffer, data, (int64_t)index * stride, stride, limit);
                    }
                }
            }
        }
    }
    else {
        for (int i = 0; i < clientState->vao->maxEnabledAttribs; i++) {
            GLVertexAttrib* attrib = &clientState->vao->attribs[i];
            int64_t limit;
            void* data = attrib->state ? GLVertexAttrib_resolve(clientState, attrib, &limit) : NULL;
            if (attrib->state && (data || attrib->bufferId)) {
                bool legacyEnabledWithProgram = attrib->state == VERTEX_ATTRIB_ENABLED && clientState->program > 0;
                short stride = attrib->stride;
                if (attrib->state == VERTEX_ATTRIB_ENABLED || legacyEnabledWithProgram) {
                    ArrayBuffer_putInt(&outputBuffer, (first + count) * stride);
                    GLVertexAttrib_putBytes(&outputBuffer, data, 0, (int64_t)(first + count) * stride, limit);
                }
                else if (attrib->state == VERTEX_ATTRIB_LEGACY_ENABLED) {
                    GLVertexAttrib_putBytes(&outputBuffer, data, (int64_t)first * stride, (int64_t)count * stride, limit);
                }
            }
        }
    }
}

bool setupRingBuffers(GLContext* context) {
    if (context->serverRing && context->clientRing) return true;

    int shmFds[2] = {-1, -1};
    int numFds;
    recv_fds(serverFd, shmFds, &numFds, NULL, 0);
    if (numFds != 2) goto error;
    
    context->serverRing = RingBuffer_create(shmFds[0], SERVER_RING_BUFFER_SIZE);
    if (!context->serverRing) goto error;
    
    context->clientRing = RingBuffer_create(shmFds[1], CLIENT_RING_BUFFER_SIZE);
    if (!context->clientRing) goto error;
    context->clientRing->peer = context->serverRing;
    
    CLOSEFD(shmFds[0]);
    CLOSEFD(shmFds[1]);
    return true;
    
error:
    if (context->serverRing) {
        RingBuffer_free(context->serverRing);
        context->serverRing = NULL;
    }
    if (context->clientRing) {
        RingBuffer_free(context->clientRing);
        context->clientRing = NULL;
    }
    CLOSEFD(shmFds[0]);
    CLOSEFD(shmFds[1]);    
    return false;
}

static int gladioServerConnect() {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    struct sockaddr_un server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sun_family = AF_LOCAL;

    strncpy(server_addr.sun_path, X11_SERVER_PATH, sizeof(server_addr.sun_path) - 1);

    int res;
    do {
        res = 0;
        if (connect(fd, (struct sockaddr*)&server_addr, sizeof(struct sockaddr_un)) < 0) res = -errno;
    } 
    while (res == -EINTR);    

    if (res < 0) {
        close(fd);
        return -1;
    }

    return fd;
}

static bool sendX11AuthRequest() {
    char requestData[12] = {0};
    *(short*)(requestData + 2) = 11;
    
    int result;
    result = sock_write(serverFd, requestData, 12);
    if (result != 12) return false;
    
    char replyData[8] = {0};
    result = sock_read(serverFd, replyData, 8);
    if (result != 8) return false;
    
    int additionalDataLength = *(short*)(replyData + 6) * 4;
    char additionalData[additionalDataLength];
    result = sock_read(serverFd, additionalData, additionalDataLength);
    if (result != additionalDataLength) return false;
    
    return true;
}

void gl_flush_current(void) {
    if (currentGLContext && currentGLContext->serverRing) gl_flush(currentGLContext->serverRing);
}

int gl_batch_ops = 0; // batching is opt-in for now: GLADIO_BATCH=32
int gl_trace_live = 120;
#define GL_TRACE_RING 400
#define GL_TRACE_LINE 230
static char traceRing[GL_TRACE_RING][GL_TRACE_LINE];
static unsigned traceHead = 0;
static bool traceDumped = false;

void gl_trace_write(const char* fmt, ...) {
    if (gl_trace_live <= 0) return;
    unsigned slot = __atomic_fetch_add(&traceHead, 1, __ATOMIC_RELAXED);
    char* line = traceRing[slot % GL_TRACE_RING];
    va_list args;
    va_start(args, fmt);
    vsnprintf(line, GL_TRACE_LINE, fmt, args);
    va_end(args);
    if (slot < (unsigned)gl_trace_live) println("%s", line);
}

static void gl_trace_dump(void) {
    if (gl_trace_live <= 0 || traceDumped) return;
    traceDumped = true;
    unsigned head = traceHead;
    unsigned count = head < GL_TRACE_RING ? head : GL_TRACE_RING;
    println("gladio-tail: last %u of %u calls before exit", count, head);
    for (unsigned i = head - count; i < head; i++) println("gladio-tail: #%u %s", i, traceRing[i % GL_TRACE_RING]);
}

bool gladioInitOnce() {
    if (serverFd == -1) {
        const char* traceEnv = getenv("GLADIO_TRACE");
        if (traceEnv && *traceEnv) gl_trace_live = MAX(0, atoi(traceEnv));
        if (gl_trace_live > 0) atexit(gl_trace_dump);
        const char* batchEnv = getenv("GLADIO_BATCH");
        if (batchEnv && *batchEnv) {
            gl_batch_ops = atoi(batchEnv);
            if (gl_batch_ops < 0) gl_batch_ops = 0;
            if (gl_batch_ops > 4096) gl_batch_ops = 4096;
        }
        serverFd = gladioServerConnect();
        
        if (!sendX11AuthRequest()) {
            CLOSEFD(serverFd);
            return false;
        }
    }
    return serverFd > 0;    
}