#ifndef GLADIO_COMPRESSED_TEXTURE_H
#define GLADIO_COMPRESSED_TEXTURE_H

#include "gladio.h"
#include "thread_pool.h"

// RGTC (BC4/BC5) is NOT part of isCompressedFormat() on purpose: that one drives the "allocate as BGRA8" path in convertTexImageFormat.
// This one is for what the game is told about its textures (GL_TEXTURE_COMPRESSED, sizes, glGetCompressedTexImage).
static inline bool isCompressedFormatForQuery(uint32_t format) {
    switch (format) {
        case GL_COMPRESSED_RGB_S3TC_DXT1_EXT:
        case GL_COMPRESSED_RGBA_S3TC_DXT1_EXT:
        case GL_COMPRESSED_RGBA_S3TC_DXT3_EXT:
        case GL_COMPRESSED_RGBA_S3TC_DXT5_EXT:
        case 0x8DBB: case 0x8DBD: /* RED_RGTC1, RG_RGTC2 */
            return true;
        default:
            return false;
    }
}

static inline int getCompressedImageSize(uint32_t format, int width, int height, int level) {
    width = TEXLEVEL(width >> level, 2) << 2;
    height = TEXLEVEL(height >> level, 2) << 2;
    bool halfSize = format == GL_COMPRESSED_RGB_S3TC_DXT1_EXT || format == GL_COMPRESSED_RGBA_S3TC_DXT1_EXT || format == 0x8DBB;
    return halfSize ? (width * height) / 2 : width * height;
}

extern void compressTexImage2D(uint32_t format, int width, int height, void* imageData, void* compressedData);
extern void* decompressTexImage2D(uint32_t format, int width, int height, void* imageData, ThreadPool* threadPool);

bool isRGTCFormat(uint32_t format);
// The GPU's driver takes this BC format itself (GL_EXT_texture_compression_s3tc / _rgtc, e.g. Adreno): stored compressed,
// not decompressed into BGRA8 (4-8x the memory: 8192 atlases need ~20 GB that way). GLADIO_BC=0: always decompress.
bool isNativeCompressedFormat(uint32_t format);
int compressedLevelSize(uint32_t format, int width, int height);
int rgtcChannels(uint32_t format);
void* decompressRGTC(uint32_t format, int width, int height, const void* imageData);

#endif
