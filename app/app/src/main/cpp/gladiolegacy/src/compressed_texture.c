#define STB_DXT_IMPLEMENTATION
#include "stb_dxt.h"

#include "compressed_texture.h"
#include "bc_decoder.h"

void compressTexImage2D(uint32_t format, int width, int height, void* imageData, void* compressedData) {
    static const uint8_t offsets[16][2] = {{0, 0}, {1, 0}, {2, 0}, {3, 0}, {0, 1}, {1, 1}, {2, 1}, {3, 1}, {0, 2}, {1, 2}, {2, 2}, {3, 2}, {0, 3}, {1, 3}, {2, 3}, {3, 3}};
    int blockSize = format == GL_COMPRESSED_RGB_S3TC_DXT1_EXT || format == GL_COMPRESSED_RGBA_S3TC_DXT1_EXT ? 8 : 16;
    int alpha = (format != GL_COMPRESSED_RGB_S3TC_DXT1_EXT && blockSize == 16) ? 1 : 0;

    GLuint* src = imageData;
    GLuint block[16];
    for (int y = 0, x, i; y < height; y += 4) {
        for (x = 0; x < width; x += 4) {
            for (i = 0; i < 16; i++) {
                block[i] = (x+offsets[i][0]) < width && (y+offsets[i][1]) < height ? src[x+offsets[i][0]+(y+offsets[i][1])*width] : 0;
            }
            stb_compress_dxt_block((uint8_t*)compressedData, (const uint8_t*)block, alpha, STB_DXT_NORMAL);
            compressedData += blockSize;
        }
    }
}

void* decompressTexImage2D(uint32_t format, int width, int height, void* imageData, ThreadPool* threadPool) {
    int bcN = 0;
    bool isNoAlphaU = format == GL_COMPRESSED_RGB_S3TC_DXT1_EXT;

    switch (format) {
        case GL_COMPRESSED_RGB_S3TC_DXT1_EXT:
        case GL_COMPRESSED_RGBA_S3TC_DXT1_EXT:
            bcN = 1;
            break;
        case GL_COMPRESSED_RGBA_S3TC_DXT3_EXT:
            bcN = 2;
            break;
        case GL_COMPRESSED_RGBA_S3TC_DXT5_EXT:
            bcN = 3;
            break;
    }

    void* decompressedData = malloc(width * height * 4);
    BCDecoder_decode(imageData, decompressedData, width, height, bcN, isNoAlphaU, threadPool);
    return decompressedData;
}

// ---- RGTC (BC4 / BC5), unsigned: decoded to tightly packed R8 / RG8 ----
static void decodeBC4Block(const uint8_t* b, uint8_t out[16]) {
    uint8_t p[8];
    p[0] = b[0]; p[1] = b[1];
    if (p[0] > p[1]) {
        for (int i = 1; i < 7; i++) p[i + 1] = (uint8_t)(((7 - i) * p[0] + i * p[1]) / 7);
    } else {
        for (int i = 1; i < 5; i++) p[i + 1] = (uint8_t)(((5 - i) * p[0] + i * p[1]) / 5);
        p[6] = 0; p[7] = 255;
    }
    uint64_t bits = 0;
    for (int i = 0; i < 6; i++) bits |= (uint64_t)b[2 + i] << (8 * i);
    for (int i = 0; i < 16; i++) out[i] = p[(bits >> (3 * i)) & 7];
}

bool isRGTCFormat(uint32_t format) {
    return format == 0x8DBB || format == 0x8DBD;
}

int rgtcChannels(uint32_t format) {
    return format == 0x8DBD ? 2 : 1;
}

void* decompressRGTC(uint32_t format, int width, int height, const void* imageData) {
    int channels = rgtcChannels(format);
    int blockBytes = channels * 8;
    uint8_t* dst = calloc((size_t)width * height * channels, 1);
    if (!dst) return NULL;
    const uint8_t* src = imageData;
    int bw = (width + 3) / 4, bh = (height + 3) / 4;
    for (int by = 0; by < bh; by++) {
        for (int bx = 0; bx < bw; bx++) {
            uint8_t px[2][16];
            for (int c = 0; c < channels; c++) decodeBC4Block(src + c * 8, px[c]);
            src += blockBytes;
            for (int i = 0; i < 16; i++) {
                int x = bx * 4 + (i & 3), y = by * 4 + (i >> 2);
                if (x >= width || y >= height) continue;
                for (int c = 0; c < channels; c++) dst[((size_t)y * width + x) * channels + c] = px[c][i];
            }
        }
    }
    return dst;
}
