#ifndef GLADIO_GL_BUFFER_H
#define GLADIO_GL_BUFFER_H

#include <sys/mman.h>

#include "GL/gl.h"

typedef struct GLBuffer {
    GLuint id;
    GLenum type;
    void* mappedData;
    int size;
    int mapOffset;
    int mapLength;
    int access;
    int usage;
    short drawStride;
    bool mapped;
    int sentinelFrom; // [sentinelFrom, size) of the shared copy holds BUFFER_SENTINEL words, size = none
} GLBuffer;

// Factorio maps its 1 MB vertex stream from the write position to the END of the buffer, writes a few hundred bytes and
// unmaps: without knowing where the writes ended the whole rest went to the server each time (30-80 MB per frame).
// The not yet written part is filled with this word (a NaN); at unmap the data ends where 16 of them in a row start.
#define BUFFER_SENTINEL 0x7FBADA55u
#define BUFFER_SENTINEL_RUN 16
extern void GLBuffer_fillSentinel(GLBuffer* buffer);
extern int GLBuffer_writtenEnd(GLBuffer* buffer, int offset, int length);

extern GLBuffer* GLBuffer_getBound(GLenum target);
extern void GLBuffer_setBound(GLenum target, GLuint id);
extern GLenum GLBuffer_getType(GLuint id);
extern bool GLBuffer_mapMemory(GLBuffer* buffer, int fd, int size);
extern void GLBuffer_delete(GLuint id);
extern void GLBuffer_onDestroy(GLClientState* clientState);
extern void GLBuffer_getParamsv(GLenum target, GLenum pname, GLint* params);

extern void flushMappedPersistentBuffers(GLint first, GLsizei count, const void* indices, GLenum indexType, GLint basevertex);

#endif