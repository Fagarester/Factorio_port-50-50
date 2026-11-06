#ifndef _GNU_SOURCE
#define _GNU_SOURCE // RTLD_DEEPBIND
#endif
#include <dlfcn.h>
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

// GLADIO_INPROCESS=<libgladio-direct.so>: the server in this process on the phone's driver (libhybris), loaded so that
// its gl* calls go to the driver and not back into this libGL (RTLD_DEEPBIND: its own libraries' symbols first).
// Its handlers keep their own threads (in parallel with the game: v48 megabase 26 fps on the game's threads vs ~40);
// GLADIO_SYNC=1 runs them on the game's threads instead (direct/sync.c), then GLADIO_DIRECT=0 turns off the hand-over.
static void* (*syncAttach)(int threadId) = NULL;
static int (*syncRun)(void* sync) = NULL;
void (*gl_sync_finish)(void* sync) = NULL;
// GLADIO_DIRECT=0: every request through the ring even then (3c off)
static int (*directCall)(void* sync, short requestCode, void* data, int size) = NULL;
static const unsigned char* directTable = NULL;
static int directStart = 0, directCount = 0;
unsigned gl_direct_calls = 0;
int gl_lazy_unit = 1;
// requests that do not depend on the active texture unit: a waiting glActiveTexture is not sent before them (gladio.h)
unsigned char gl_unit_free[1024];
static void initUnitFree(void) {
    static const short codes[] = {
        REQUEST_CODE_GL_DRAW_ELEMENTS,
        REQUEST_CODE_GL_DRAW_ARRAYS,
        REQUEST_CODE_GL_DRAW_ELEMENTS_BASE_VERTEX,
        REQUEST_CODE_GL_DRAW_RANGE_ELEMENTS,
        REQUEST_CODE_GL_DRAW_RANGE_ELEMENTS_BASE_VERTEX,
        REQUEST_CODE_GL_DRAW_ELEMENTS_INSTANCED,
        REQUEST_CODE_GL_DRAW_ARRAYS_INSTANCED,
        REQUEST_CODE_GL_DRAW_ELEMENTS_INSTANCED_BASE_VERTEX,
        REQUEST_CODE_GL_MULTI_DRAW_ELEMENTS_BASE_VERTEX,
        REQUEST_CODE_GL_BIND_BUFFER,
        REQUEST_CODE_GL_BIND_BUFFER_BASE,
        REQUEST_CODE_GL_BIND_BUFFER_RANGE,
        REQUEST_CODE_GL_UNIFORM1I,
        REQUEST_CODE_GL_UNIFORM_BLOCK_BINDING,
        REQUEST_CODE_GL_USE_PROGRAM,
        REQUEST_CODE_GL_BIND_VERTEX_ARRAY,
        REQUEST_CODE_GL_BLEND_EQUATION_SEPARATE,
        REQUEST_CODE_GL_BLEND_FUNC_SEPARATE,
        REQUEST_CODE_GL_BLEND_FUNC,
        REQUEST_CODE_GL_BLEND_EQUATION,
        REQUEST_CODE_GL_BLEND_COLOR,
        REQUEST_CODE_GL_FLUSH_MAPPED_BUFFER_RANGE,
        REQUEST_CODE_GL_BUFFER_SUB_DATA,
        REQUEST_CODE_GL_BUFFER_DATA,
        REQUEST_CODE_GL_SCISSOR,
        REQUEST_CODE_GL_VIEWPORT,
        REQUEST_CODE_GL_COLOR_MASK,
        REQUEST_CODE_GL_DEPTH_MASK,
        REQUEST_CODE_GL_DEPTH_FUNC,
        REQUEST_CODE_GL_STENCIL_FUNC,
        REQUEST_CODE_GL_STENCIL_OP,
        REQUEST_CODE_GL_STENCIL_MASK,
        REQUEST_CODE_GL_VERTEX_ATTRIB_POINTER,
        REQUEST_CODE_GL_VERTEX_ATTRIB_IPOINTER,
        REQUEST_CODE_GL_ENABLE_VERTEX_ATTRIB_ARRAY,
        REQUEST_CODE_GL_DISABLE_VERTEX_ATTRIB_ARRAY,
        REQUEST_CODE_GL_BIND_FRAMEBUFFER,
        REQUEST_CODE_GL_CLEAR,
        REQUEST_CODE_GL_CLEAR_COLOR,
        REQUEST_CODE_GL_UNIFORM1F,
        REQUEST_CODE_GL_UNIFORM2F,
        REQUEST_CODE_GL_UNIFORM3F,
        REQUEST_CODE_GL_UNIFORM4F,
        REQUEST_CODE_GL_UNIFORM1FV,
        REQUEST_CODE_GL_UNIFORM2FV,
        REQUEST_CODE_GL_UNIFORM3FV,
        REQUEST_CODE_GL_UNIFORM4FV,
        REQUEST_CODE_GL_UNIFORM1IV,
        REQUEST_CODE_GL_UNIFORM2I,
        REQUEST_CODE_GL_UNIFORM4I,
        REQUEST_CODE_GL_UNIFORM1UI,
        REQUEST_CODE_GL_UNIFORM_MATRIX2FV,
        REQUEST_CODE_GL_UNIFORM_MATRIX3FV,
        REQUEST_CODE_GL_UNIFORM_MATRIX4FV,
        REQUEST_CODE_GL_VERTEX_ATTRIB_DIVISOR,
        REQUEST_CODE_GL_BIND_SAMPLER,
    };
    for (unsigned i = 0; i < sizeof(codes) / sizeof(codes[0]); i++) gl_unit_free[codes[i] & 1023] = 1;
}
unsigned gl_lazy_units = 0; // glActiveTexture calls made lazy, for the stats line

static bool startInProcessServer(void) {
    static int state = 0; // 1 = running or not wanted, -1 = failed
    if (state != 0) return state > 0;
    state = 1;
    const char* library = getenv("GLADIO_INPROCESS");
    if (!library || !*library) return true;
    state = -1;
    void* handle = dlopen(library, RTLD_NOW | RTLD_LOCAL | RTLD_DEEPBIND);
    if (!handle) {
        println("gladio: in-process server: %s", dlerror());
        return false;
    }
    int (*start)(const char* socketPath, int sync) = dlsym(handle, "gladio_inprocess_start");
    const char* syncEnv = getenv("GLADIO_SYNC");
    bool sync = syncEnv && *syncEnv && atoi(syncEnv) > 0;
    const char* path = getenv("GLADIO_SOCKET");
    if (!start || !path || !*path || start(path, sync) != 0) {
        println("gladio: in-process server did not start (%s)", !start ? "no gladio_inprocess_start" : !path || !*path ? "no GLADIO_SOCKET" : "see above");
        return false;
    }
    if (sync) {
        syncAttach = dlsym(handle, "gladio_sync_attach");
        syncRun = dlsym(handle, "gladio_sync_run");
        gl_sync_finish = dlsym(handle, "gladio_sync_finish");
        if (!syncAttach || !syncRun || !gl_sync_finish) syncAttach = NULL;
        const char* directEnv = getenv("GLADIO_DIRECT");
        const unsigned char* (*table)(int* start, int* count) = dlsym(handle, "gladio_direct_table");
        directCall = dlsym(handle, "gladio_direct_call");
        if (syncAttach && table && directCall && !(directEnv && *directEnv && atoi(directEnv) == 0))
            directTable = table(&directStart, &directCount);
    }
    println("gladio: server in this process (%s), handlers %s%s", library, syncAttach ? "on the game's threads" : "on their own threads",
            directTable ? ", requests handed over directly" : "");
    state = 1;
    return true;
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
    if (syncAttach) {
        context->sync = syncAttach(context->id);
        if (context->sync) {
            context->serverRing->syncRun = context->clientRing->syncRun = syncRun;
            context->serverRing->syncArg = context->clientRing->syncArg = context->sync;
            if (directTable) {
                context->serverRing->directCall = directCall;
                context->serverRing->directTable = directTable;
                context->serverRing->directStart = directStart;
                context->serverRing->directCount = directCount;
            }
        }
        else println("gladio: no in-process handler for thread %d, it runs on its own thread", context->id);
    }
    
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

    // Factory: the Gladio server listens on its own socket (Termux:X11 is the X server, in another process)
    const char* path = getenv("GLADIO_SOCKET");
    strncpy(server_addr.sun_path, path && *path ? path : X11_SERVER_PATH, sizeof(server_addr.sun_path) - 1);

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

int gl_batch_ops = 64; // GLADIO_BATCH=<n> requests per ring write, 0 = off (v29 trace: 50k calls -> ~850 ring writes)
int gl_profile = 0;   // GLADIO_PROFILE=1: frame time trace and the most frequent requests; 2: also the time in Gladio calls
unsigned gl_request_counts[1024];
unsigned gl_roundtrips = 0;
__thread uint64_t gl_call_enter_ns = 0, gl_call_time_ns = 0;
__thread unsigned gl_call_count = 0;
uint64_t gl_inline_bytes = 0;
unsigned gl_roundtrip_codes[1024];
int gl_trace_live = 0; // GLADIO_TRACE=<n>: print the first n calls
int gl_trace_frame = 4000; // GLADIO_TRACE_FRAME: frame whose preceding calls are printed (gameplay, not the exit)
#define GL_TRACE_RING 4000
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

// last n traced calls, printed right when something suspicious happens (the process may be killed before it exits normally)
void gl_trace_dump_recent(unsigned n) {
    if (gl_trace_live <= 0) return;
    unsigned head = traceHead;
    if (n > head) n = head;
    if (n > GL_TRACE_RING) n = GL_TRACE_RING;
    println("gladio-recent: last %u calls", n);
    for (unsigned i = head - n; i < head; i++) println("gladio-recent: #%u %s", i, traceRing[i % GL_TRACE_RING]);
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
        const char* traceFrameEnv = getenv("GLADIO_TRACE_FRAME");
        if (traceFrameEnv && *traceFrameEnv) gl_trace_frame = atoi(traceFrameEnv);
        const char* profileEnv = getenv("GLADIO_PROFILE");
        gl_profile = profileEnv && atoi(profileEnv) > 0 ? atoi(profileEnv) : 0;
        initUnitFree();
        const char* lazyEnv = getenv("GLADIO_LAZY_UNIT");
        if (lazyEnv && *lazyEnv) gl_lazy_unit = atoi(lazyEnv) != 0;
        const char* batchEnv = getenv("GLADIO_BATCH");
        if (batchEnv && *batchEnv) {
            gl_batch_ops = atoi(batchEnv);
            if (gl_batch_ops < 0) gl_batch_ops = 0;
            if (gl_batch_ops > 4096) gl_batch_ops = 4096;
        }
        if (!startInProcessServer()) return false;
        serverFd = gladioServerConnect();
        
        if (!sendX11AuthRequest()) {
            CLOSEFD(serverFd);
            return false;
        }
    }
    return serverFd > 0;    
}