#ifndef TEXCOMPRESS_H
#define TEXCOMPRESS_H

/*
 * Lossy PNG shrinker: 8-bit PNG (RGBA/RGB/gray/gray+alpha/palette, not interlaced) -> palette PNG
 * with fewer colors (median cut + k-means refinement, optional Floyd-Steinberg dithering;
 * fully transparent pixels keep their own palette entry).
 *
 * ratio      desired ratio original_size / new_size (e.g. 2 or 5)
 * min_colors the palette is never smaller than this (4..256), even if the ratio is not reached
 */

#define TEX_OK            0   /* the smaller file was written to dst */
#define TEX_SKIP_FORMAT   1   /* not an 8-bit non-interlaced PNG, or too big: copy the original */
#define TEX_SKIP_SMALL    2   /* file is tiny: copy the original */
#define TEX_SKIP_GAIN     3   /* gain would be below ~1.67x: copy the original */
/* < 0: error, nothing written */

typedef struct {
    int ratio;
    int min_colors;
    int kmeans_iters;   /* 0 = median cut only */
    int dither;         /* 0..100, strength of Floyd-Steinberg error diffusion (0 = off) */
} TexOptions;

int tex_png_shrink_opts(const char *src, const char *dst, const TexOptions *opts);
int tex_png_shrink(const char *src, const char *dst, int ratio, int min_colors);

#endif
