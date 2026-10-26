#include <sys/socket.h>
#include <sys/un.h>
#include <errno.h>
#include <sys/mman.h>

#include "gladio.h"
#include "gl_context.h"

int serverFd = -1;
ArrayBuffer outputBuffer = {0};
ArrayBuffer inputBuffer = {0};
SparseArray glxContexts = {0};
thread_local GLContext* currentGLContext = NULL;

static SparseArray cachedStrings = {0};
extern int gladioDebug;

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
    if (!indices || count <= 0) return;
    switch (indexType) {
        case GL_UNSIGNED_BYTE: {
            const GLubyte* values = indices;
            for (int i = 0; i < count; i++) {
                range[0] = MIN(values[i] + basevertex, range[0]);
                range[1] = MAX(values[i] + basevertex, range[1]);
            }
            break;
        }
        case GL_UNSIGNED_SHORT: {
            const GLushort* values = indices;
            for (int i = 0; i < count; i++) {
                range[0] = MIN(values[i] + basevertex, range[0]);
                range[1] = MAX(values[i] + basevertex, range[1]);
            }
            break;
        }
        case GL_UNSIGNED_INT: {
            const GLuint* values = indices;
            for (int i = 0; i < count; i++) {
                range[0] = MIN(values[i] + basevertex, range[0]);
                range[1] = MAX(values[i] + basevertex, range[1]);
            }
            break;
        }
    }
}

#define UNBOUND_ARRAY_MAX_BYTES (256 * 1024 * 1024)

// Where the data of a vertex attribute lives and how many bytes of it may be read.
// avail = -1: plain client memory, its size is unknown (the application owns it).
// For a mapped VBO the size is known, so a draw can never read past the end of the mapping.
static bool getAttribSource(GLClientState* clientState, const GLVertexAttrib* attrib, const uint8_t** base, int64_t* avail) {
    *avail = -1;
    if (attrib->bufferId) {
        GLBuffer* buffer = SparseArray_get(clientState->buffers, attrib->bufferId);
        if (!buffer || !buffer->mappedData || !attrib->pointer) return false;
        *base = attrib->pointer;
        *avail = (int64_t)buffer->size - ((const uint8_t*)attrib->pointer - (const uint8_t*)buffer->mappedData);
        return *avail > 0;
    }
    if (!attrib->pointer) return false;
    *base = attrib->pointer;
    return true;
}

// Generic attribute (the server reads "int byteCount" + data). The data must cover vertices 0..lastVertex.
// The old code sent (lastVertex+1)*stride bytes, i.e. stride-elemSize bytes PAST the end of the application's array
// (page fault when the array ends at a page boundary, typical for big malloc/mmap blocks). Now the last vertex only
// contributes its own element. byteCount 0 = "no readable data", the server skips the attribute.
static void putGenericAttrib(GLClientState* clientState, int i, int64_t lastVertex) {
    const GLVertexAttrib* attrib = &clientState->vao->attribs[i];
    const uint8_t* base = NULL;
    int64_t avail = -1;
    int64_t bytes = 0;

    if (lastVertex >= 0 && getAttribSource(clientState, attrib, &base, &avail)) {
        int64_t stride = attrib->stride;
        int64_t elem = attrib->elemBytes > 0 ? attrib->elemBytes : stride;
        bytes = lastVertex * stride + elem;
        if (attrib->offsetOnServer) bytes += attrib->bufferOffset;
        if (avail >= 0 && bytes > avail) bytes = avail;
        if (attrib->offsetOnServer && bytes <= (int64_t)attrib->bufferOffset) bytes = 0;
        if (bytes < 0 || bytes > UNBOUND_ARRAY_MAX_BYTES) {
            if (gladioDebug) fprintf(stderr, "gladio: attrib %d: bad size %lld (last=%lld stride=%lld), skipped\n", i, (long long)bytes, (long long)lastVertex, (long long)stride);
            bytes = 0;
        }
    }
    else if (gladioDebug && lastVertex >= 0) {
        fprintf(stderr, "gladio: attrib %d: no readable source (ptr=%p buffer=%u), skipped\n", i, attrib->pointer, attrib->bufferId);
    }

    ArrayBuffer_putInt(&outputBuffer, (int)bytes);
    if (bytes > 0) ArrayBuffer_putBytes(&outputBuffer, base, (int)bytes);
}

// Legacy attribute: the server reads exactly one stride per drawn element. Zeros when the data is not readable,
// the stream has to stay in step with the server.
static void putLegacyElement(GLClientState* clientState, int i, int64_t index) {
    const GLVertexAttrib* attrib = &clientState->vao->attribs[i];
    const uint8_t* base = NULL;
    int64_t avail = -1;
    int stride = attrib->stride;
    if (stride <= 0) return;
    if (index >= 0 && getAttribSource(clientState, attrib, &base, &avail) && (avail < 0 || (index + 1) * stride <= avail)) {
        ArrayBuffer_putBytes(&outputBuffer, base + index * stride, stride);
    }
    else ArrayBuffer_putBytes(&outputBuffer, NULL, stride);
}

void writeUnboundVertexArrays(GLint first, GLsizei count, const void* indices, GLenum indexType, GLint basevertex) {
    GLClientState* clientState = currentGLContext->clientState;
    GLVertexArrayObject* vao = clientState->vao;

    if (indexType != GL_NONE) {
        GLBuffer* elementArrayBuffer = GLBuffer_getBound(GL_ELEMENT_ARRAY_BUFFER);
        if (elementArrayBuffer) {
            // the server needs the offset; the data are read here. Never trust the mapping blindly.
            uint64_t offset = (uint64_t)indices;
            ArrayBuffer_putInt(&outputBuffer, (int)offset);
            int64_t needed = (int64_t)offset + (int64_t)MAX(count, 0) * sizeofGLType(indexType);
            if (elementArrayBuffer->mappedData && needed <= elementArrayBuffer->size) indices = (const char*)elementArrayBuffer->mappedData + offset;
            else indices = NULL;
        }
        else if (indices && count > 0) ArrayBuffer_putBytes(&outputBuffer, indices, count * sizeofGLType(indexType));
        else if (count > 0) ArrayBuffer_putBytes(&outputBuffer, NULL, count * sizeofGLType(indexType));

        int64_t lastVertex = -1;
        bool rangeKnown = false;
        for (int i = 0; i < vao->maxEnabledAttribs; i++) {
            if (!vao->attribs[i].state) continue;
            if (vao->attribs[i].state == VERTEX_ATTRIB_ENABLED || GLClientState_isLegacyEnabledWithProgram(clientState, i)) {
                if (!rangeKnown) {
                    GLuint range[2];
                    getRangeIndices(indices, count, indexType, basevertex, range);
                    lastVertex = indices && count > 0 ? (int64_t)range[1] : -1;
                    rangeKnown = true;
                }
                putGenericAttrib(clientState, i, lastVertex);
            }
            else if (vao->attribs[i].state == VERTEX_ATTRIB_LEGACY_ENABLED) {
                for (int j = 0; j < count; j++) {
                    int64_t index = -1;
                    if (indices) {
                        switch (indexType) {
                            case GL_UNSIGNED_BYTE:  index = ((const GLubyte*)indices)[j] + (int64_t)basevertex; break;
                            case GL_UNSIGNED_SHORT: index = ((const GLushort*)indices)[j] + (int64_t)basevertex; break;
                            case GL_UNSIGNED_INT:   index = ((const GLuint*)indices)[j] + (int64_t)basevertex; break;
                            default: index = 0; break;
                        }
                    }
                    putLegacyElement(clientState, i, index);
                }
            }
        }
    }
    else {
        for (int i = 0; i < vao->maxEnabledAttribs; i++) {
            if (!vao->attribs[i].state) continue;
            // same condition as the server (readUnboundVertexArrays). The old code used "ENABLED && program", so a legacy
            // array with a program bound was written as legacy here but read as generic there: the stream went out of step.
            if (vao->attribs[i].state == VERTEX_ATTRIB_ENABLED || GLClientState_isLegacyEnabledWithProgram(clientState, i)) {
                putGenericAttrib(clientState, i, count > 0 ? (int64_t)first + count - 1 : -1);
            }
            else if (vao->attribs[i].state == VERTEX_ATTRIB_LEGACY_ENABLED) {
                for (int j = 0; j < count; j++) putLegacyElement(clientState, i, (int64_t)first + j);
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

int gl_batch_ops = 32;
int gladioDebug = 0;

bool gladioInitOnce() {
    if (serverFd == -1) {
        gladioDebug = getenv("GLADIO_DEBUG") != NULL;
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