#include "texcompress.h"

#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

#define MAX_PIXELS (20u * 1024u * 1024u)   /* bigger images are copied as they are (memory) */
#define MIN_FILE_SIZE 4096                 /* tiny files are not worth it */
#define BIN_COUNT (1u << 19)               /* r5 g5 b5 a4 */

typedef struct { uint8_t *d; size_t n, cap; } Buf;

static int buf_reserve(Buf *b, size_t extra) {
    if (b->n + extra <= b->cap) return 0;
    size_t cap = b->cap ? b->cap : 4096;
    while (cap < b->n + extra) cap *= 2;
    uint8_t *p = (uint8_t *)realloc(b->d, cap);
    if (!p) return -1;
    b->d = p; b->cap = cap;
    return 0;
}
static int buf_put(Buf *b, const void *p, size_t n) {
    if (buf_reserve(b, n)) return -1;
    memcpy(b->d + b->n, p, n); b->n += n;
    return 0;
}
static int buf_put32(Buf *b, uint32_t v) {
    uint8_t t[4] = {(uint8_t)(v >> 24), (uint8_t)(v >> 16), (uint8_t)(v >> 8), (uint8_t)v};
    return buf_put(b, t, 4);
}
static uint32_t be32(const uint8_t *p) { return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | p[3]; }

static int put_chunk(Buf *b, const char *type, const uint8_t *data, uint32_t len) {
    if (buf_put32(b, len) || buf_put(b, type, 4)) return -1;
    if (len && buf_put(b, data, len)) return -1;
    uLong crc = crc32(0L, Z_NULL, 0);
    crc = crc32(crc, (const Bytef *)type, 4);
    if (len) crc = crc32(crc, data, len);
    return buf_put32(b, (uint32_t)crc);
}

static uint8_t *read_file(const char *path, size_t *len) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    if (fseek(f, 0, SEEK_END) != 0) { fclose(f); return NULL; }
    long size = ftell(f);
    if (size <= 0 || fseek(f, 0, SEEK_SET) != 0) { fclose(f); return NULL; }
    uint8_t *data = (uint8_t *)malloc((size_t)size);
    if (!data) { fclose(f); return NULL; }
    if (fread(data, 1, (size_t)size, f) != (size_t)size) { free(data); fclose(f); return NULL; }
    fclose(f);
    *len = (size_t)size;
    return data;
}

/* ------------------------------------------------------------------ decode */

static int paeth(int a, int b, int c) {
    int p = a + b - c, pa = abs(p - a), pb = abs(p - b), pc = abs(p - c);
    return (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
}

static int unfilter(uint8_t *raw, uint32_t w, uint32_t h, int bpp) {
    size_t stride = (size_t)w * bpp;
    for (uint32_t y = 0; y < h; y++) {
        uint8_t *row = raw + (size_t)y * (stride + 1);
        int ft = row[0];
        uint8_t *cur = row + 1;
        const uint8_t *prev = y ? raw + (size_t)(y - 1) * (stride + 1) + 1 : NULL;
        switch (ft) {
            case 0: break;
            case 1: for (size_t i = bpp; i < stride; i++) cur[i] = (uint8_t)(cur[i] + cur[i - bpp]); break;
            case 2: if (prev) for (size_t i = 0; i < stride; i++) cur[i] = (uint8_t)(cur[i] + prev[i]); break;
            case 3:
                for (size_t i = 0; i < stride; i++) {
                    int left = i >= (size_t)bpp ? cur[i - bpp] : 0, up = prev ? prev[i] : 0;
                    cur[i] = (uint8_t)(cur[i] + ((left + up) >> 1));
                }
                break;
            case 4:
                for (size_t i = 0; i < stride; i++) {
                    int left = i >= (size_t)bpp ? cur[i - bpp] : 0, up = prev ? prev[i] : 0;
                    int ul = (prev && i >= (size_t)bpp) ? prev[i - bpp] : 0;
                    cur[i] = (uint8_t)(cur[i] + paeth(left, up, ul));
                }
                break;
            default: return -1;
        }
    }
    return 0;
}

/* Decodes to a malloc'ed RGBA buffer. Returns NULL when the format is not supported. */
static uint8_t *decode_png(const uint8_t *file, size_t len, uint32_t *pw, uint32_t *ph) {
    static const uint8_t sig[8] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    if (len < 33 || memcmp(file, sig, 8) != 0) return NULL;

    uint32_t w = 0, h = 0;
    int depth = 0, ctype = -1, interlace = 0;
    uint8_t plte[768]; int nplte = 0;
    uint8_t trns[256]; int ntrns = 0;
    int key_valid = 0; uint8_t key[3] = {0, 0, 0};
    Buf idat = {0, 0, 0};
    int ok = 0, have_ihdr = 0;

    size_t pos = 8;
    while (pos + 12 <= len) {
        uint32_t clen = be32(file + pos);
        const uint8_t *type = file + pos + 4;
        if ((size_t)clen > len - pos - 12) goto out;
        const uint8_t *data = file + pos + 8;
        if (!memcmp(type, "IHDR", 4)) {
            if (clen != 13) goto out;
            w = be32(data); h = be32(data + 4);
            depth = data[8]; ctype = data[9]; interlace = data[12];
            have_ihdr = 1;
        } else if (!memcmp(type, "PLTE", 4)) {
            if (clen > 768 || clen % 3) goto out;
            memcpy(plte, data, clen); nplte = (int)(clen / 3);
        } else if (!memcmp(type, "tRNS", 4)) {
            if (ctype == 3) { ntrns = clen > 256 ? 256 : (int)clen; memcpy(trns, data, ntrns); }
            else if (ctype == 0 && clen >= 2) { key_valid = 1; key[0] = data[1]; if (data[0]) key_valid = 0; }
            else if (ctype == 2 && clen >= 6) { key_valid = 1; key[0] = data[1]; key[1] = data[3]; key[2] = data[5]; if (data[0] || data[2] || data[4]) key_valid = 0; }
        } else if (!memcmp(type, "IDAT", 4)) {
            if (buf_put(&idat, data, clen)) goto out;
        } else if (!memcmp(type, "IEND", 4)) {
            break;
        }
        pos += 12 + (size_t)clen;
    }

    if (!have_ihdr || w == 0 || h == 0 || depth != 8 || interlace != 0 || idat.n == 0) goto out;
    if ((uint64_t)w * h > MAX_PIXELS) goto out;
    int bpp;
    switch (ctype) { case 0: bpp = 1; break; case 2: bpp = 3; break; case 3: bpp = 1; break; case 4: bpp = 2; break; case 6: bpp = 4; break; default: goto out; }
    if (ctype == 3 && nplte == 0) goto out;

    size_t stride = (size_t)w * bpp;
    size_t rawlen = (size_t)h * (stride + 1);
    size_t alloc = ctype == 6 ? rawlen : rawlen; /* RGBA of type 6 is built in place */
    uint8_t *raw = (uint8_t *)malloc(alloc);
    if (!raw) goto out;

    z_stream zs; memset(&zs, 0, sizeof(zs));
    if (inflateInit(&zs) != Z_OK) { free(raw); goto out; }
    zs.next_in = idat.d; zs.avail_in = (uInt)idat.n;
    zs.next_out = raw; zs.avail_out = (uInt)rawlen;
    int zr = inflate(&zs, Z_FINISH);
    size_t produced = zs.total_out;
    inflateEnd(&zs);
    if (zr != Z_STREAM_END || produced != rawlen) { free(raw); goto out; }
    if (unfilter(raw, w, h, bpp) != 0) { free(raw); goto out; }

    uint8_t *rgba;
    if (ctype == 6) {
        for (uint32_t y = 0; y < h; y++) memmove(raw + (size_t)y * stride, raw + (size_t)y * (stride + 1) + 1, stride);
        rgba = raw;
    } else {
        rgba = (uint8_t *)malloc((size_t)w * h * 4);
        if (!rgba) { free(raw); goto out; }
        for (uint32_t y = 0; y < h; y++) {
            const uint8_t *src = raw + (size_t)y * (stride + 1) + 1;
            uint8_t *dst = rgba + (size_t)y * w * 4;
            for (uint32_t x = 0; x < w; x++, dst += 4) {
                switch (ctype) {
                    case 0: dst[0] = dst[1] = dst[2] = src[x]; dst[3] = (key_valid && src[x] == key[0]) ? 0 : 255; break;
                    case 2: {
                        const uint8_t *p = src + x * 3;
                        dst[0] = p[0]; dst[1] = p[1]; dst[2] = p[2];
                        dst[3] = (key_valid && p[0] == key[0] && p[1] == key[1] && p[2] == key[2]) ? 0 : 255;
                        break;
                    }
                    case 3: {
                        int i = src[x];
                        if (i >= nplte) { free(raw); free(rgba); goto out; }
                        dst[0] = plte[i * 3]; dst[1] = plte[i * 3 + 1]; dst[2] = plte[i * 3 + 2];
                        dst[3] = i < ntrns ? trns[i] : 255;
                        break;
                    }
                    default: /* 4: gray + alpha */
                        dst[0] = dst[1] = dst[2] = src[x * 2]; dst[3] = src[x * 2 + 1]; break;
                }
            }
        }
        free(raw);
    }
    *pw = w; *ph = h;
    ok = 1;
    free(idat.d);
    return rgba;
out:
    (void)ok;
    free(idat.d);
    return NULL;
}

/* ---------------------------------------------------------------- quantize */

typedef struct { uint32_t cnt; uint32_t key; float c[4]; } Ent;
typedef struct { int lo, hi; uint64_t cnt; float range; int chan; } Box;

static const float CHAN_W[4] = {1.0f, 1.1f, 0.8f, 1.0f};
static __thread int g_chan;

static int cmp_ent(const void *a, const void *b) {
    float x = ((const Ent *)a)->c[g_chan], y = ((const Ent *)b)->c[g_chan];
    return (x > y) - (x < y);
}

static inline uint32_t bin_key(const uint8_t *p) {
    return ((uint32_t)(p[0] >> 3) << 14) | ((uint32_t)(p[1] >> 3) << 9) | ((uint32_t)(p[2] >> 3) << 4) | (uint32_t)(p[3] >> 4);
}

static void box_eval(Box *b, const Ent *e) {
    float mn[4] = { 1e9f, 1e9f, 1e9f, 1e9f }, mx[4] = { -1e9f, -1e9f, -1e9f, -1e9f };
    uint64_t cnt = 0;
    for (int i = b->lo; i < b->hi; i++) {
        cnt += e[i].cnt;
        for (int k = 0; k < 4; k++) {
            if (e[i].c[k] < mn[k]) mn[k] = e[i].c[k];
            if (e[i].c[k] > mx[k]) mx[k] = e[i].c[k];
        }
    }
    b->cnt = cnt; b->range = 0.0f; b->chan = 0;
    for (int k = 0; k < 4; k++) {
        float r = (mx[k] - mn[k]) * CHAN_W[k];
        if (r > b->range) { b->range = r; b->chan = k; }
    }
}

static const float KW[4] = {1.0f, 1.4f, 0.7f, 1.0f};

/* weighted distance between a pixel color `a` and a palette color `b`; color errors count less on faint pixels */
static inline float color_dist(const float *a, const float *b) {
    float dr = a[0] - b[0], dg = a[1] - b[1], db = a[2] - b[2], da = a[3] - b[3];
    float aw = 0.25f + 0.75f * (a[3] * (1.0f / 255.0f));
    return aw * (KW[0] * dr * dr + KW[1] * dg * dg + KW[2] * db * db) + KW[3] * da * da;
}

/* Builds a palette of at most `ncolors` entries (index 0 is transparent when `reserve_transparent`).
 * `lookup` (BIN_COUNT bytes) gets the palette index of every used bin. Returns the palette size. */
static int build_palette(Ent *e, int nent, int ncolors, int reserve_transparent, int kmeans_iters, uint8_t pal[256][4], uint8_t *lookup) {
    int base = reserve_transparent ? 1 : 0;
    if (reserve_transparent) { pal[0][0] = pal[0][1] = pal[0][2] = pal[0][3] = 0; }
    int maxbox = ncolors - base;
    if (maxbox < 1) maxbox = 1;
    if (nent == 0) return base ? base : 1;

    Box *boxes = (Box *)calloc((size_t)maxbox, sizeof(Box));
    if (!boxes) return -1;
    int nbox = 1;
    boxes[0].lo = 0; boxes[0].hi = nent;
    box_eval(&boxes[0], e);

    while (nbox < maxbox) {
        int best = -1; double bscore = 0.0;
        for (int i = 0; i < nbox; i++) {
            if (boxes[i].hi - boxes[i].lo < 2 || boxes[i].range <= 0.0f) continue;
            double score = (double)boxes[i].cnt * boxes[i].range;
            if (score > bscore) { bscore = score; best = i; }
        }
        if (best < 0) break;
        Box *b = &boxes[best];
        g_chan = b->chan;
        qsort(e + b->lo, (size_t)(b->hi - b->lo), sizeof(Ent), cmp_ent);
        uint64_t half = b->cnt / 2, acc = 0;
        int mid = b->lo;
        while (mid < b->hi - 1) { acc += e[mid].cnt; mid++; if (acc >= half) break; }
        if (mid <= b->lo) mid = b->lo + 1;
        if (mid >= b->hi) mid = b->hi - 1;
        boxes[nbox].lo = mid; boxes[nbox].hi = b->hi;
        b->hi = mid;
        box_eval(&boxes[best], e);
        box_eval(&boxes[nbox], e);
        nbox++;
    }

    float cent[256][4];
    for (int i = 0; i < nbox; i++) {
        double s[4] = {0, 0, 0, 0}; double total = 0;
        for (int j = boxes[i].lo; j < boxes[i].hi; j++) {
            for (int k = 0; k < 4; k++) s[k] += (double)e[j].c[k] * e[j].cnt;
            total += e[j].cnt;
        }
        for (int k = 0; k < 4; k++) cent[i][k] = (float)(total > 0 ? s[k] / total : 0);
        for (int j = boxes[i].lo; j < boxes[i].hi; j++) lookup[e[j].key] = (uint8_t)(base + i);
    }
    free(boxes);

    /* k-means (Lloyd) refinement: move every color to the middle of the pixels that are closest to it */
    if (kmeans_iters > 0 && nbox > 1) {
        for (int it = 0; it < kmeans_iters; it++) {
            double sum[256][4]; double tot[256];
            memset(sum, 0, sizeof(sum)); memset(tot, 0, sizeof(tot));
            for (int j = 0; j < nent; j++) {
                int bi = 0; float bd = 3.0e38f;
                for (int i = 0; i < nbox; i++) {
                    float d = color_dist(e[j].c, cent[i]);
                    if (d < bd) { bd = d; bi = i; }
                }
                lookup[e[j].key] = (uint8_t)(base + bi);
                for (int k = 0; k < 4; k++) sum[bi][k] += (double)e[j].c[k] * e[j].cnt;
                tot[bi] += e[j].cnt;
            }
            for (int i = 0; i < nbox; i++) {
                if (tot[i] <= 0) continue;
                for (int k = 0; k < 4; k++) cent[i][k] = (float)(sum[i][k] / tot[i]);
            }
        }
        /* final assignment with the final colors */
        for (int j = 0; j < nent; j++) {
            int bi = 0; float bd = 3.0e38f;
            for (int i = 0; i < nbox; i++) {
                float d = color_dist(e[j].c, cent[i]);
                if (d < bd) { bd = d; bi = i; }
            }
            lookup[e[j].key] = (uint8_t)(base + bi);
        }
    }

    for (int i = 0; i < nbox; i++) {
        for (int k = 0; k < 4; k++) {
            float v = cent[i][k];
            pal[base + i][k] = (uint8_t)(v < 0 ? 0 : (v > 255 ? 255 : (int)(v + 0.5f)));
        }
        if (pal[base + i][3] == 0) pal[base + i][3] = 1; /* index 0 is the only fully transparent entry */
    }
    return base + nbox;
}

/* ------------------------------------------------------------------ encode */

static int encode_indexed(const uint8_t *idx, uint32_t w, uint32_t h, uint8_t pal[256][4], int npal, int level, Buf *out) {
    int depth = npal <= 2 ? 1 : (npal <= 4 ? 2 : (npal <= 16 ? 4 : 8));
    size_t rb = ((size_t)w * depth + 7) / 8;
    uint8_t *raw = (uint8_t *)malloc((size_t)h * (rb + 1));
    uint8_t *packed = (uint8_t *)malloc(rb * 2 + 8);
    if (!raw || !packed) { free(raw); free(packed); return -1; }
    uint8_t *prevp = packed + rb;           /* previous packed row */
    uint8_t *cand[5];
    uint8_t *work = (uint8_t *)malloc(rb * 5);
    if (!work) { free(raw); free(packed); return -1; }
    for (int f = 0; f < 5; f++) cand[f] = work + rb * f;
    memset(prevp, 0, rb);

    for (uint32_t y = 0; y < h; y++) {
        const uint8_t *src = idx + (size_t)y * w;
        memset(packed, 0, rb);
        if (depth == 8) memcpy(packed, src, w);
        else for (uint32_t x = 0; x < w; x++) packed[(x * depth) >> 3] |= (uint8_t)(src[x] << (8 - depth - ((x * depth) & 7)));

        /* the filter that yields the most zero bytes wins; ties go to the lowest filter id */
        size_t bestz = 0; int bestf = 0;
        for (int f = 0; f < 5; f++) {
            uint8_t *o = cand[f]; size_t zeros = 0;
            for (size_t i = 0; i < rb; i++) {
                int a = i ? packed[i - 1] : 0, b = prevp[i], c = i ? prevp[i - 1] : 0, v = packed[i], r;
                switch (f) {
                    case 0: r = v; break;
                    case 1: r = v - a; break;
                    case 2: r = v - b; break;
                    case 3: r = v - ((a + b) >> 1); break;
                    default: r = v - paeth(a, b, c); break;
                }
                o[i] = (uint8_t)r;
                if (!o[i]) zeros++;
            }
            if (f == 0 || zeros > bestz) { bestz = zeros; bestf = f; }
        }
        uint8_t *dst = raw + (size_t)y * (rb + 1);
        dst[0] = (uint8_t)bestf;
        memcpy(dst + 1, cand[bestf], rb);
        memcpy(prevp, packed, rb);
    }
    free(work); free(packed);

    z_stream zs; memset(&zs, 0, sizeof(zs));
    if (deflateInit2(&zs, level, Z_DEFLATED, 15, 9, Z_DEFAULT_STRATEGY) != Z_OK) { free(raw); return -1; }
    size_t rawlen = (size_t)h * (rb + 1);
    uLong bound = deflateBound(&zs, (uLong)rawlen);
    uint8_t *z = (uint8_t *)malloc(bound);
    if (!z) { deflateEnd(&zs); free(raw); return -1; }
    zs.next_in = raw; zs.avail_in = (uInt)rawlen; zs.next_out = z; zs.avail_out = (uInt)bound;
    int zr = deflate(&zs, Z_FINISH);
    size_t zlen = zs.total_out;
    deflateEnd(&zs);
    free(raw);
    if (zr != Z_STREAM_END) { free(z); return -1; }

    static const uint8_t sig[8] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    uint8_t ihdr[13] = {(uint8_t)(w >> 24), (uint8_t)(w >> 16), (uint8_t)(w >> 8), (uint8_t)w,
                        (uint8_t)(h >> 24), (uint8_t)(h >> 16), (uint8_t)(h >> 8), (uint8_t)h, (uint8_t)depth, 3, 0, 0, 0};
    uint8_t plte[768], trns[256]; int ntrns = 0;
    for (int i = 0; i < npal; i++) {
        plte[i * 3] = pal[i][0]; plte[i * 3 + 1] = pal[i][1]; plte[i * 3 + 2] = pal[i][2];
        trns[i] = pal[i][3];
        if (pal[i][3] != 255) ntrns = i + 1;
    }
    int rc = 0;
    if (buf_put(out, sig, 8) || put_chunk(out, "IHDR", ihdr, 13) || put_chunk(out, "PLTE", plte, (uint32_t)npal * 3)) rc = -1;
    if (!rc && ntrns > 0 && put_chunk(out, "tRNS", trns, (uint32_t)ntrns)) rc = -1;
    if (!rc && (put_chunk(out, "IDAT", z, (uint32_t)zlen) || put_chunk(out, "IEND", NULL, 0))) rc = -1;
    free(z);
    return rc;
}

/* nearest palette entry of an arbitrary color (index 0 is skipped when it is the transparent entry) */
static int nearest_color(const float *c, uint8_t pal[256][4], int npal, int first) {
    int bi = first; float bd = 3.0e38f;
    for (int i = first; i < npal; i++) {
        float p[4] = {(float)pal[i][0], (float)pal[i][1], (float)pal[i][2], (float)pal[i][3]};
        float d = color_dist(c, p);
        if (d < bd) { bd = d; bi = i; }
    }
    return bi;
}

/* Floyd-Steinberg error diffusion of the RGB error; `strength` 0..1 */
static int dither_pixels(const uint8_t *rgba, uint32_t w, uint32_t h, uint8_t pal[256][4], int npal, int has_transparent, float strength, uint8_t *idx) {
    int first = has_transparent ? 1 : 0;
    if (first >= npal) first = 0;
    uint16_t *cache = (uint16_t *)malloc(BIN_COUNT * sizeof(uint16_t));
    float *err = (float *)calloc((size_t)(w + 2) * 3 * 2, sizeof(float));
    if (!cache || !err) { free(cache); free(err); return -1; }
    memset(cache, 0xFF, BIN_COUNT * sizeof(uint16_t));
    float *cur = err, *next = err + (size_t)(w + 2) * 3;

    for (uint32_t y = 0; y < h; y++) {
        memset(next, 0, (size_t)(w + 2) * 3 * sizeof(float));
        for (uint32_t x = 0; x < w; x++) {
            const uint8_t *p = rgba + ((size_t)y * w + x) * 4;
            size_t o = (size_t)(x + 1) * 3;
            if (p[3] == 0) { idx[(size_t)y * w + x] = 0; continue; }
            float c[4];
            for (int k = 0; k < 3; k++) {
                float v = (float)p[k] + cur[o + k];
                c[k] = v < 0 ? 0 : (v > 255 ? 255 : v);
            }
            c[3] = (float)p[3];
            uint8_t q[4] = {(uint8_t)(c[0] + 0.5f), (uint8_t)(c[1] + 0.5f), (uint8_t)(c[2] + 0.5f), p[3]};
            uint32_t key = bin_key(q);
            int n = cache[key];
            if (n == 0xFFFF) { n = nearest_color(c, pal, npal, first); cache[key] = (uint16_t)n; }
            idx[(size_t)y * w + x] = (uint8_t)n;
            float e[3];
            for (int k = 0; k < 3; k++) e[k] = (c[k] - (float)pal[n][k]) * strength;
            for (int k = 0; k < 3; k++) {
                cur[o + 3 + k] += e[k] * (7.0f / 16.0f);
                next[o - 3 + k] += e[k] * (3.0f / 16.0f);
                next[o + k] += e[k] * (5.0f / 16.0f);
                next[o + 3 + k] += e[k] * (1.0f / 16.0f);
            }
        }
        float *tmp = cur; cur = next; next = tmp;
    }
    free(cache); free(err);
    return 0;
}

/* One full attempt with `ncolors` colors. */
static int make_candidate(const uint8_t *rgba, uint32_t w, uint32_t h, Ent *ents, int nent, int has_transparent,
                          int ncolors, int kmeans_iters, int dither, int level, Buf *out) {
    uint8_t pal[256][4];
    uint8_t *lookup = (uint8_t *)calloc(BIN_COUNT, 1);
    if (!lookup) return -1;
    int npal = build_palette(ents, nent, ncolors, has_transparent, kmeans_iters, pal, lookup);
    if (npal < 1) { free(lookup); return -1; }

    size_t npx = (size_t)w * h;
    uint8_t *idx = (uint8_t *)malloc(npx);
    if (!idx) { free(lookup); return -1; }
    if (dither > 0 && npal > 1) {
        if (dither_pixels(rgba, w, h, pal, npal, has_transparent, (float)dither / 100.0f, idx) != 0) { free(lookup); free(idx); return -1; }
    } else {
        for (size_t i = 0; i < npx; i++) {
            const uint8_t *p = rgba + i * 4;
            idx[i] = p[3] == 0 ? 0 : lookup[bin_key(p)];
        }
    }
    free(lookup);
    int rc = encode_indexed(idx, w, h, pal, npal, level, out);
    free(idx);
    return rc;
}

int tex_png_shrink_opts(const char *src, const char *dst, const TexOptions *opts) {
    int ratio = opts->ratio < 2 ? 2 : opts->ratio;
    int min_colors = opts->min_colors < 4 ? 4 : (opts->min_colors > 256 ? 256 : opts->min_colors);
    int kmeans = opts->kmeans_iters < 0 ? 0 : (opts->kmeans_iters > 8 ? 8 : opts->kmeans_iters);
    int dither = opts->dither < 0 ? 0 : (opts->dither > 100 ? 100 : opts->dither);

    size_t flen = 0;
    uint8_t *file = read_file(src, &flen);
    if (!file) return -1;
    if (flen < MIN_FILE_SIZE) { free(file); return TEX_SKIP_SMALL; }

    uint32_t w, h;
    uint8_t *rgba = decode_png(file, flen, &w, &h);
    free(file);
    if (!rgba) return TEX_SKIP_FORMAT;

    int result = TEX_SKIP_FORMAT;
    Ent *ents = NULL;
    Buf cand[5]; memset(cand, 0, sizeof(cand));
    Buf refined; memset(&refined, 0, sizeof(refined));
    uint32_t *cnt = (uint32_t *)calloc(BIN_COUNT, sizeof(uint32_t));
    double *sum = (double *)calloc((size_t)BIN_COUNT * 4, sizeof(double));
    if (!cnt || !sum) { result = -1; goto done; }

    size_t npx = (size_t)w * h;
    int has_transparent = 0;
    for (size_t i = 0; i < npx; i++) {
        const uint8_t *p = rgba + i * 4;
        if (p[3] == 0) { has_transparent = 1; continue; }
        uint32_t k = bin_key(p);
        cnt[k]++;
        double *s = sum + (size_t)k * 4;
        s[0] += p[0]; s[1] += p[1]; s[2] += p[2]; s[3] += p[3];
    }
    int nent = 0;
    for (uint32_t k = 0; k < BIN_COUNT; k++) if (cnt[k]) nent++;
    ents = (Ent *)malloc(sizeof(Ent) * (size_t)(nent ? nent : 1));
    if (!ents) { result = -1; goto done; }
    int n = 0;
    for (uint32_t k = 0; k < BIN_COUNT; k++) {
        if (!cnt[k]) continue;
        ents[n].cnt = cnt[k]; ents[n].key = k;
        for (int c = 0; c < 4; c++) ents[n].c[c] = (float)(sum[(size_t)k * 4 + c] / cnt[k]);
        n++;
    }
    free(cnt); cnt = NULL; free(sum); sum = NULL;

    /* candidate palette sizes, biggest first */
    int sizes[5], ns = 0;
    for (int s = 256; s >= min_colors && ns < 5; s /= 2) sizes[ns++] = s;
    if (ns == 0) sizes[ns++] = min_colors;
    int done_flag[5] = {0, 0, 0, 0, 0};
    int best_ok = -1, lo = 0, hi = ns - 1;
    int level = 6;
    /* the search uses plain median cut (fast); the chosen size is refined afterwards */
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        if (make_candidate(rgba, w, h, ents, nent, has_transparent, sizes[mid], 0, 0, level, &cand[mid]) != 0) { result = -1; goto done; }
        done_flag[mid] = 1;
        if (cand[mid].n * (size_t)ratio <= flen) { best_ok = mid; hi = mid - 1; } else lo = mid + 1;
    }
    int pick = best_ok;
    if (pick < 0) {
        pick = ns - 1;
        if (!done_flag[pick] && make_candidate(rgba, w, h, ents, nent, has_transparent, sizes[pick], 0, 0, level, &cand[pick]) != 0) { result = -1; goto done; }
    }

    Buf *best = &cand[pick];
    if (kmeans > 0 || dither > 0) {
        /* Refined (and dithered) palettes are better at the same size, so first try bigger palettes while the
         * wanted ratio still holds; if even the chosen size breaks the ratio (dithering makes files bigger)
         * go down, never below min_colors. */
        int p2 = pick, have = 0;
        while (p2 >= 0 && p2 < ns) {
            Buf tmp; memset(&tmp, 0, sizeof(tmp));
            if (make_candidate(rgba, w, h, ents, nent, has_transparent, sizes[p2], kmeans, dither, level, &tmp) != 0) { free(tmp.d); result = -1; goto done; }
            int meets = tmp.n * (size_t)ratio <= flen;
            if (meets) {
                free(refined.d); refined = tmp; have = 1;
                if (p2 == 0) break;
                p2--;                       /* try a bigger palette */
            } else if (!have && p2 < ns - 1) {
                free(tmp.d);
                p2++;                       /* chosen size is too big: go down */
            } else {
                if (!have) { refined = tmp; have = 1; } else free(tmp.d); /* floor reached or bigger palette breaks the ratio */
                break;
            }
        }
        if (have) best = &refined;
    }

    /* not worth the quality loss: keep the original when the gain is below ~1.67x */
    if (best->n * 5 > flen * 3) { result = TEX_SKIP_GAIN; goto done; }
    {
        FILE *f = fopen(dst, "wb");
        if (!f) { result = -1; goto done; }
        size_t wr = fwrite(best->d, 1, best->n, f);
        int cl = fclose(f);
        if (wr != best->n || cl != 0) { remove(dst); result = -1; goto done; }
        result = TEX_OK;
    }
done:
    for (int i = 0; i < 5; i++) free(cand[i].d);
    free(refined.d);
    free(cnt); free(sum); free(ents); free(rgba);
    return result;
}

int tex_png_shrink(const char *src, const char *dst, int ratio, int min_colors) {
    TexOptions o = {ratio, min_colors, 2, 0};
    return tex_png_shrink_opts(src, dst, &o);
}
