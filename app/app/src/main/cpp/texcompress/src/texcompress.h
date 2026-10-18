#ifndef TEXCOMPRESS_H
#define TEXCOMPRESS_H

/*
 * Lossy PNG shrinker: 8-bit PNG (RGBA/RGB/gray/gray+alpha/palette, not interlaced) -> palette PNG
 * with fewer colors (median cut, fully transparent pixels keep their own palette entry).
 *
 * ratio      desired ratio original_size / new_size (e.g. 5 or 10)
 * min_colors the palette is never smaller than this (16..256), even if the ratio is not reached
 *
 * Returns  0 the smaller file was written to dst
 *          1 nothing written (unsupported format, too small, or no real gain): copy the original
 *         <0 error (nothing written)
 */
int tex_png_shrink(const char *src, const char *dst, int ratio, int min_colors);

#endif
