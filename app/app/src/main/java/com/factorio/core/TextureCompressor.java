package com.factorio.core;

import android.util.Log;

/** JNI wrapper of cpp/texcompress (PNG -> palette PNG with fewer colors). */
public final class TextureCompressor {
    private static boolean tried;
    private static boolean loaded;

    private TextureCompressor() {}

    public static synchronized boolean isAvailable() {
        if (!tried) {
            tried = true;
            try {
                System.loadLibrary("texcompress");
                loaded = true;
            }
            catch (Throwable e) {
                ;
            }
        }
        return loaded;
    }

    public static final int OK = 0;            // the smaller file was written to dst
    public static final int SKIP_FORMAT = 1;   // not an 8-bit non-interlaced PNG (or too big): copy the original
    public static final int SKIP_SMALL = 2;    // tiny file: copy the original
    public static final int SKIP_GAIN = 3;     // gain would be below ~1.67x: copy the original
    // less than 0: error, nothing written

    /**
     * @param ratio     wanted original size / new size
     * @param minColors the palette is never smaller than this
     * @param kmeans    palette refinement passes (0 = off)
     * @param dither    0..100, error diffusion strength (0 = off)
     */
    public static native int shrinkPng(String src, String dst, int ratio, int minColors, int kmeans, int dither);
}
