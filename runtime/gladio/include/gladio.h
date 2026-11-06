#define HEADER_SIZE 6

#define DEBUG_MODE_GL_ERROR (1<<0)
#define DEBUG_MODE_SHADER_INFO (1<<1)
#define DEBUG_MODE_TEXTURE_FORMAT (1<<2)
#define DEBUG_MODE_HANDLE_REQUEST (1<<3)
#define DEBUG_MODE (0) // FIXME 0=DISABLE or (FIRST | SECOND)
#define IS_DEBUG_ENABLED(type) ((DEBUG_MODE & type) != 0)

#define GLX_EXT_MAJOR_OPCODE -106
#define SERVER_RING_BUFFER_SIZE 67108864
#define CLIENT_RING_BUFFER_SIZE 33554432
#define THREAD_POOL_NUM_THREADS 4
#define PIXEL_READ_CACHE_SKIP_FRAMES 3
#define SKIP_GL_FINISH 1
#define X11_SERVER_PATH "/data/data/com.winlator/files/rootfs/tmp/.X11-unix/X0"

#define GL_STRING_VERSION "3.3"
#define GL_STRING_RENDERER "Gladio"
#define GL_STRING_SHADING_LANGUAGE_VERSION "3.30"

#include "winlator.h"

#ifdef __ANDROID__
#define GL_SERVER 1
#endif

#define MAX_LIGHTS 4
#define MAX_TEXTURES 8
#define MAX_TEXTURE_TARGETS 4
#define MAX_FRAMEBUFFER_TARGETS 3
#define MAX_BUFFER_TARGETS 6
#define MAX_ARB_PROGRAM_TARGETS 2
#define MAX_FB_COLOR_ATTACHMENTS 8
#define MIN_VERTEX_ATTRIBS 3
#define MAX_GENERIC_VERTEX_ATTRIBS 16
#define VERTEX_ATTRIB_COUNT (MIN_VERTEX_ATTRIBS + MAX_TEXTURES + MAX_GENERIC_VERTEX_ATTRIBS)

#define GL_SEND_CHECKED(requestCode, outputBuffer, bufferSize, ...) \
    do { \
        if (!gl_send(currentGLContext->serverRing, requestCode, outputBuffer, bufferSize)) { \
            static int sendFailures = 0; \
            if (sendFailures++ < 10) println("gladio: [thread %d] %s: request %d could not be sent (ring exit flag: %d)", currentThreadId(), __func__, (int)(requestCode), RingBuffer_hasStatus(currentGLContext->serverRing, RING_STATUS_EXIT) ? 1 : 0); \
            GL_CALL_UNLOCK(); \
            return __VA_OPT__(GL_FALSE); \
        } \
    } \
    while (0)

#define GL_RECV_CHECKED(...) \
    do { \
        if (!gl_recv(currentGLContext->clientRing, NULL, &inputBuffer)) { \
            static int recvFailures = 0; \
            if (recvFailures++ < 10) println("gladio: [thread %d] %s: the reply could not be read (ring exit flag: %d)", currentThreadId(), __func__, RingBuffer_hasStatus(currentGLContext->clientRing, RING_STATUS_EXIT) ? 1 : 0); \
            GL_CALL_UNLOCK(); \
            return __VA_OPT__(GL_FALSE); \
        } \
    } \
    while (0)

#ifndef GLADIO_H
#define GLADIO_H

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <unistd.h>
#include <pthread.h>

#include "request_codes.h"
#include "socket_utils.h"
#include "arrays.h"
#include "ring_buffer.h"
#include "image_utils.h"
#include "texture_utils.h"
#include "time_utils.h"
#include "string_utils.h"
#include "events.h"

typedef struct GLClientState GLClientState;

#ifdef GL_SERVER

#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>

#define GL_GLEXT_PROTOTYPES 1
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <GLES3/gl3.h>
#include <GLES3/gl31.h>
#include <GLES3/gl32.h>
#include <GLES3/gl3ext.h>

typedef struct GLRenderer GLRenderer;
typedef struct GLContext GLContext;
typedef struct ShaderMaterial ShaderMaterial;

typedef struct JMethods {
    JavaVM* jvm;
    JNIEnv* env;
    jobject obj;
    jmethodID getWindowSize;
    jmethodID clearWindowContent;
    jmethodID updateWindowContent;
    jmethodID getGLXContextPtr;
} JMethods;

#else // GL_SERVER
#include "GL/gl.h"
#include "GL/glext.h"
#include "GL/glx.h"
#include "gl_dsa.h"
#endif

// ---- Command batching -------------------------------------------------------------------------------------------------
// Instead of two ring writes (+ two atomic tail updates) per GL call, small requests are collected in a per-ring buffer and
// published with ONE ring write. The byte stream the server reads is identical (header, payload, header, payload ...), so the
// server needs no changes. The batch is published when: N requests are queued (GLADIO_BATCH, default 0 = off, e.g. 32), the buffer
// is full, a big payload follows, the client waits for a reply (gl_recv), or on glFlush / glFinish / SwapBuffers.
#define GL_BATCH_CAPACITY (256 * 1024)
#define GL_BATCH_MAX_ITEM (32 * 1024)
extern int gl_batch_ops;
extern int gl_profile;
extern unsigned gl_request_counts[1024];
extern uint64_t gl_inline_bytes;
extern unsigned gl_direct_calls; // requests handed straight to the in-process handler (3c), for the stats line // buffer data sent along with requests, for the stats line
// GLADIO_TRACE=N: print the first N buffer / draw related calls live (default 120) and keep the last 400 in memory;
// the last 400 are printed as 'gladio-tail:' when the process exits (e.g. after an X server IO error). 0 = off.
extern int gl_trace_live;
extern int gl_trace_frame;
extern void gl_trace_write(const char* fmt, ...);
extern void gl_trace_dump_recent(unsigned n);
// off (GLADIO_TRACE unset): not even the arguments are worked out (some look up bound buffers)
#define GL_TRACE(...) do { if (__builtin_expect(gl_trace_live > 0, 0)) gl_trace_write(__VA_ARGS__); } while (0)

static inline bool gl_flush(RingBuffer* ring) {
    if (!ring || ring->batchSize == 0) return true;
    uint32_t size = ring->batchSize;
    ring->batchSize = 0;
    ring->batchCount = 0;
    return RingBuffer_write(ring, ring->batch, size);
}

static inline bool gl_batch_append(RingBuffer* ring, const void* data, uint32_t size) {
    if (ring->batchSize + size > GL_BATCH_CAPACITY && !gl_flush(ring)) return false;
    memcpy(ring->batch + ring->batchSize, data, size);
    ring->batchSize += size;
    return true;
}

static inline bool gl_send(RingBuffer* ring, short requestCode, void* data, int size);

// Lazy glActiveTexture (GLADIO_LAZY_UNIT=0: off): Factorio switches units ~3 times per draw, often only to find the
// texture already bound (the bind is then not sent). The unit change waits here until a request that may depend on it;
// requests that never do (draws, buffers, uniforms, blending, glBindSampler with its own unit ...: gl_unit_free, main.c)
// pass it. A later glActiveTexture replaces a waiting one. One switch costs ~0.4 us in the driver.
extern int gl_lazy_unit;
extern unsigned char gl_unit_free[1024];
extern unsigned gl_lazy_units;

static inline bool gl_send_pending_unit(RingBuffer* ring, short requestCode) {
    if (__builtin_expect(ring->pendingActiveTexture == 0, 1) || gl_unit_free[requestCode & 1023]) return true;
    GLenum texture = ring->pendingActiveTexture;
    ring->pendingActiveTexture = 0;
    return gl_send(ring, REQUEST_CODE_GL_ACTIVE_TEXTURE, &texture, sizeof(GLenum));
}

static inline bool gl_send(RingBuffer* ring, short requestCode, void* data, int size) {
    if (!gl_send_pending_unit(ring, requestCode)) return false;
    if (gl_profile) gl_request_counts[requestCode & 1023]++;
    // stage 3c: the server's handler is in this process on this thread and between requests: it takes this one at once,
    // from here (no copy into the batch and ring, no switch to its stack). The queued requests go first.
    if (ring->directTable && (unsigned)(requestCode - ring->directStart) < (unsigned)ring->directCount &&
        ring->directTable[requestCode - ring->directStart] && size >= 0 && gl_flush(ring) &&
        ring->directCall(ring->syncArg, requestCode, data, size)) {
        ring->lastCode = requestCode;
        gl_direct_calls++;
        return true;
    }
    char header[HEADER_SIZE];
    *(short*)(header + 0) = requestCode;
    *(int*)(header + 2) = size;
    ring->lastCode = requestCode;

    if (gl_batch_ops > 0 && size >= 0 && size <= GL_BATCH_MAX_ITEM) {
        if (!ring->batch) ring->batch = malloc(GL_BATCH_CAPACITY);
        if (ring->batch) {
            if (!gl_batch_append(ring, header, HEADER_SIZE)) return false;
            if (size > 0 && !gl_batch_append(ring, data, size)) return false;
            if (++ring->batchCount >= (uint32_t)gl_batch_ops) return gl_flush(ring);
            return true;
        }
    }

    if (!gl_flush(ring)) return false;
    bool result = RingBuffer_write(ring, header, HEADER_SIZE);
    if (!result) return false;

    if (size > 0) {
        result = RingBuffer_write(ring, data, size);
        if (!result) return false;
    }
    return true;
}

// A request whose payload is params followed by bulk data (one header for both), e.g. vertex data sent along with
// glBufferSubData so the server takes it from the request instead of the shared buffer and no reply has to be awaited.
static inline bool gl_send_with_data(RingBuffer* ring, short requestCode, void* params, int paramsSize, const void* data, uint32_t dataSize) {
    if (!gl_send_pending_unit(ring, requestCode)) return false;
    if (gl_profile) gl_request_counts[requestCode & 1023]++;
    char header[HEADER_SIZE];
    *(short*)(header + 0) = requestCode;
    *(int*)(header + 2) = paramsSize + (int)dataSize;
    ring->lastCode = requestCode;

    if (gl_batch_ops > 0 && paramsSize + dataSize <= GL_BATCH_MAX_ITEM) {
        if (!ring->batch) ring->batch = malloc(GL_BATCH_CAPACITY);
        if (ring->batch) {
            gl_inline_bytes += dataSize;
            if (!gl_batch_append(ring, header, HEADER_SIZE) || !gl_batch_append(ring, params, paramsSize)) return false;
            if (dataSize > 0 && !gl_batch_append(ring, data, dataSize)) return false;
            if (++ring->batchCount >= (uint32_t)gl_batch_ops) return gl_flush(ring);
            return true;
        }
    }

    gl_inline_bytes += dataSize;
    if (!gl_flush(ring)) return false;
    if (!RingBuffer_write(ring, header, HEADER_SIZE) || !RingBuffer_write(ring, params, paramsSize)) return false;
    return dataSize == 0 || RingBuffer_write(ring, data, dataSize);
}

// bigger uploads keep the old way (server copies from the shared buffer, client waits for it)
#define GL_INLINE_DATA_MAX (8 * 1024 * 1024)
// glBufferData without a reply: a 4th int says where the contents are
#define BUFFER_DATA_NONE 1
#define BUFFER_DATA_INLINE 2
#define BUFFER_DATA_ZERO 3 // new storage filled with zeros
// replies the client waited for, for the stats line
extern unsigned gl_roundtrips;
extern unsigned gl_roundtrip_codes[1024]; // per request code

extern bool gl_isReadable(const void* address, int64_t size);
extern void gl_write_raw_checked(RingBuffer* ring, const void* data, int size, int requestCode);

// Raw bytes that follow a request header (texture pixels, glEnd command data ...). Keeps the order with batched requests.
static inline bool gl_write_raw(RingBuffer* ring, const void* data, uint32_t size) {
    if (size == 0) return true;
    if (ring->batch && size <= GL_BATCH_MAX_ITEM) return gl_batch_append(ring, data, size);
    if (!gl_flush(ring)) return false;
    // more than the ring holds (a texture over 4096x4096 uncompressed): in parts, which the server gathers
    for (uint32_t part = ring->bufferSize / 2; size > part; size -= part, data = (const char*)data + part)
        if (!RingBuffer_write(ring, data, part)) return false;
    return RingBuffer_write(ring, data, size);
}

static inline bool gl_recv(RingBuffer* ring, short* outRequestCode, ArrayBuffer* data) {
    if (ring->peer && !gl_flush(ring->peer)) return false; // the reply belongs to requests that may still be queued
    gl_roundtrips++;
    if (ring->peer) gl_roundtrip_codes[ring->peer->lastCode & 1023]++;
    char header[HEADER_SIZE] = {0};
    bool result = RingBuffer_read(ring, header, HEADER_SIZE);
    if (!result) return false;

    if (outRequestCode != NULL) *outRequestCode = *(short*)(header + 0);
    uint32_t rawSize = *(uint32_t*)(header + 2);
    int size = (int)(rawSize & 0x7fffffffu);

    if (ring->peer && ring->peer->lastCode != *(short*)(header + 0)) {
        // a reply that does not belong to the last request: the reply queue is out of sync (an earlier request got no reply read, or an extra one was sent)
        static int mismatches = 0;
        if (mismatches++ < 20) {
            println("gladio: reply mismatch: last request %d, reply code %d (size %d)", (int)ring->peer->lastCode, (int)*(short*)(header + 0), size);
            if (mismatches == 1) gl_trace_dump_recent(80);
        }
    }

    if (rawSize & 0x80000000u) {
        // the server attached a text for the game's log: [uint16 length][text]
        uint16_t textLength = 0;
        if (!RingBuffer_read(ring, &textLength, 2)) return false;
        char text[520] = {0};
        uint16_t keep = textLength < sizeof(text) - 1 ? textLength : sizeof(text) - 1;
        if (!RingBuffer_read(ring, text, keep)) return false;
        for (int skip = textLength - keep; skip > 0; skip--) { char dummy; if (!RingBuffer_read(ring, &dummy, 1)) return false; }
        println("gladio-server: %s", text);
        size -= 2 + textLength;
        if (size < 0) size = 0;
    }

    ArrayBuffer_rewind(data);

    if (size > 0) {
        ENSURE_ARRAY_CAPACITY(size, data->capacity, data->buffer, 1);
        result = RingBuffer_read(ring, data->buffer, size);
        if (!result) return false;
        data->size = size;
    }
    return true;
}

// GLX requests travel over the socket, GL requests over the ring: publish the queued GL requests first so the server sees them in order
extern void gl_flush_current(void);

static inline bool glx_send(int fd, char opcode, void* data, int size) {
    gl_flush_current();
    char header[4];
    header[0] = GLX_EXT_MAJOR_OPCODE;
    header[1] = opcode;
    *(short*)(header + 2) = size / 4 + 1;

    bool result = sock_write(fd, header, 4) == 4;
    if (!result) return false;

    if (size > 0) {
        result = sock_write(fd, data, size) == size;
        if (!result) return false;
    }
    return true;
}

static inline bool glx_recv(int fd, ArrayBuffer* data) {
    char header[8] = {0};
    bool result = sock_read(fd, header, sizeof(header)) == sizeof(header);
    if (!result) return false;
    int replyLength = *(int*)(header + 4);

    int size = 24 + replyLength * 4;
    ArrayBuffer_rewind(data);

    ENSURE_ARRAY_CAPACITY(size, data->capacity, data->buffer, 1);
    result = sock_read(fd, data->buffer, size) == size;
    if (!result || header[0] != 1) return false;

    data->size = size;
    return true;
}

static inline int getGLCallParamsCount(GLenum pname, bool* isColor) {
    if (isColor) *isColor = false;
    switch (pname) {
        case GL_PATCH_DEFAULT_INNER_LEVEL:
            return 2;
        case GL_SPOT_DIRECTION:
        case GL_POINT_DISTANCE_ATTENUATION:
            return 3;
        case GL_POSITION:
        case GL_PATCH_DEFAULT_OUTER_LEVEL:
            return 4;
        case GL_AMBIENT:
        case GL_DIFFUSE:
        case GL_SPECULAR:
        case GL_EMISSION:
        case GL_AMBIENT_AND_DIFFUSE:
        case GL_TEXTURE_ENV_COLOR:
        case GL_FOG_COLOR:
        case GL_TEXTURE_BORDER_COLOR:
            if (isColor) *isColor = true;
            return 4;
        default:
            return 1;
    }
}

#define PARSE_PARAMSFV(pname, params) \
    bool isColor; \
    int paramCount = getGLCallParamsCount(pname, &isColor); \
    GLfloat paramsf[paramCount]; \
    for (int p = 0; p < paramCount; p++) paramsf[p] = (isColor ? (GLfloat)params[p] / (double)INT32_MAX : (GLfloat)params[p])

#define PARSE_PARAMSIFV(pname, params) \
    int paramCount = getGLCallParamsCount(pname, NULL); \
    GLfloat paramsf[paramCount]; \
    for (int p = 0; p < paramCount; p++) paramsf[p] = ((GLfloat)params[p])

#define PARSE_MATRIXF(md) \
    GLfloat mf[16]; \
    for (int p = 0; p < 16; p++) (mf[p] = (GLfloat)md[p])

#define GL_SEND_TEXIMAGE(requestCode, format, type, width, height, depth, imageData, compressedSize) \
    do { \
        GLClientState* clientState = currentGLContext->clientState; \
        GLBuffer* pixelUnpackBuffer = GLBuffer_getBound(GL_PIXEL_UNPACK_BUFFER); \
        GL_TRACE("texupload req=%d fmt=0x%x %dx%dx%d csize=%d data=%p pbo=%u", (int)(requestCode), (unsigned)(format), (int)(width), (int)(height), (int)(depth), (int)(compressedSize), (const void*)(imageData), pixelUnpackBuffer ? pixelUnpackBuffer->id : 0); \
        int dataSize = compressedSize; \
        if (compressedSize == 0) { \
            if (clientState->pixelStore.unpackRowLength > 0) width = clientState->pixelStore.unpackRowLength; \
            if (clientState->pixelStore.unpackImageHeight > 0) height = clientState->pixelStore.unpackImageHeight; \
            dataSize = imageData && !pixelUnpackBuffer ? computeTexImageDataSize(format, type, width, height, depth) : 0; \
            ArrayBuffer_putInt(&outputBuffer, dataSize); \
        } \
        else ArrayBuffer_putInt(&outputBuffer, compressedSize); \
        if (pixelUnpackBuffer) ArrayBuffer_putInt(&outputBuffer, (uint64_t)imageData); \
        GL_SEND_CHECKED(requestCode, outputBuffer.buffer, outputBuffer.size); \
        if (pixelUnpackBuffer) { \
            GL_RECV_CHECKED(); \
        } \
        else if (dataSize > 0) gl_write_raw_checked(currentGLContext->serverRing, imageData, dataSize, requestCode); \
    } \
    while (0)

static inline int sizeofGLType(GLenum type) {
    switch (type) {
        case GL_BYTE:
        case GL_UNSIGNED_BYTE:
            return sizeof(GLbyte);
        case GL_SHORT:
        case GL_UNSIGNED_SHORT:
            return sizeof(GLshort);
        case GL_INT:
        case GL_UNSIGNED_INT:
            return sizeof(GLint);
        case GL_HALF_FLOAT:
            return sizeof(GLhalf);
        case GL_FLOAT:
            return sizeof(GLfloat);
        case GL_DOUBLE:
            return sizeof(GLdouble);
        default:
            return 0;
    }
}

static inline uint8_t indexOfGLTarget(GLenum target) {
    switch (target) {
        case GL_TEXTURE_2D:
        case GL_FRAMEBUFFER:
        case GL_VERTEX_PROGRAM_ARB:
        case GL_VERTEX_PROGRAM_TWO_SIDE:
        case GL_ARRAY_BUFFER:
        case GL_TEXTURE_1D:
        case GL_TEXTURE_RECTANGLE:
            return 0;
        case GL_TEXTURE_3D:
        case GL_DRAW_FRAMEBUFFER:
        case GL_FRAGMENT_PROGRAM_ARB:
        case GL_ELEMENT_ARRAY_BUFFER:
            return 1;
        case GL_TEXTURE_CUBE_MAP:
        case GL_READ_FRAMEBUFFER:
        case GL_PIXEL_PACK_BUFFER:
            return 2;
        case GL_PIXEL_UNPACK_BUFFER:
        case GL_TEXTURE_2D_ARRAY:
        case GL_TEXTURE_1D_ARRAY:
            return 3;
        case GL_UNIFORM_BUFFER:
            return 4;
        case GL_TEXTURE_BUFFER:
            return 5;
        default:
            if (target >= GL_TEXTURE_CUBE_MAP_POSITIVE_X && target <= GL_TEXTURE_CUBE_MAP_NEGATIVE_Z) return 2;
            println("gladio: unimplemented gl target %x", target);
            return -1;
    }
}

#endif