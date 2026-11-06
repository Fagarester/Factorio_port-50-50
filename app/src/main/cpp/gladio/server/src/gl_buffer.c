#include "gl_buffer.h"
#include "gl_context.h"
#include "sysvshared_memory.h"

// Only the GL_ELEMENT_ARRAY_BUFFER binding belongs to the VAO (real OpenGL semantics); everything else is context state, kept in defaultVAO.buffer[]
static GLBuffer** boundSlot(GLClientState* clientState, GLenum target) {
    GLVertexArrayObject* owner = target == GL_ELEMENT_ARRAY_BUFFER ? clientState->vao : &clientState->defaultVAO;
    return &owner->buffer[indexOfGLTarget(target)];
}

GLBuffer* GLBuffer_getBound(GLenum target) {
    GLClientState* clientState = &currentRenderer->clientState;
    return *boundSlot(clientState, target);
}

void GLBuffer_bind(GLenum target, GLuint id) {
    GLClientState* clientState = &currentRenderer->clientState;
    if (id == 0) {
        *boundSlot(clientState, target) = NULL;
        if (target != GL_PIXEL_UNPACK_BUFFER) glBindBuffer(target, id);
        return;
    }
    GLX_CONTEXT_LOCK();
    GLBuffer* buffer = SparseArray_get(clientState->buffers, id);
    if (!buffer) {
        buffer = calloc(1, sizeof(GLBuffer));
        buffer->id = id;
        SparseArray_put(clientState->buffers, id, buffer);
    }
    GLX_CONTEXT_UNLOCK();
    *boundSlot(clientState, target) = buffer;
    if (target != GL_PIXEL_UNPACK_BUFFER) glBindBuffer(target, id);
}

static void unmapBuffer(GLBuffer* buffer) {
    if (buffer->mappedData) {
        munmap(buffer->mappedData, buffer->size);
        buffer->mappedData = NULL;
        buffer->size = 0;
    }
}

int GLBuffer_allocateMemory(GLBuffer* buffer, int size) {
    int fd = ashmemCreateRegion("gl-buffer", size);
    if (fd < 0) return -1;

    unmapBuffer(buffer);
    void* mappedData = mmap(NULL, size, PROT_WRITE | PROT_READ, MAP_SHARED, fd, 0);
    if (mappedData == MAP_FAILED) return -1;

    buffer->mappedData = mappedData;
    buffer->size = size;
    return fd;
}

void GLBuffer_delete(GLuint id) {
    GLClientState* clientState = &currentRenderer->clientState;
    GLX_CONTEXT_LOCK();
    GLBuffer* buffer = SparseArray_get(clientState->buffers, id);
    if (buffer) {
        for (int i = 0; i < clientState->vertexArrays.size; i++) {
            GLVertexArrayObject* vbo = clientState->vertexArrays.entries[i].value;
            for (int j = 0; j < MAX_BUFFER_TARGETS; j++) if (vbo->buffer[j] == buffer) vbo->buffer[j] = NULL;
        }
        for (int i = 0; i < MAX_BUFFER_TARGETS; i++) {
            if (clientState->defaultVAO.buffer[i] == buffer) clientState->defaultVAO.buffer[i] = NULL;
        }

        unmapBuffer(buffer);
        MEMFREE(buffer);
        SparseArray_remove(clientState->buffers, id);
    }
    glDeleteBuffers(1, &id);
    GLX_CONTEXT_UNLOCK();
}

void GLBuffer_onDestroy(GLClientState* clientState) {
    for (int i = clientState->buffers->size-1; i >= 0; i--) {
        GLBuffer* buffer = clientState->buffers->entries[i].value;
        unmapBuffer(buffer);
        MEMFREE(buffer);
        SparseArray_removeAt(clientState->buffers, i);
    }
}