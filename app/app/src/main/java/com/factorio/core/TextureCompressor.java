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
                Log.e("Factorio", "texcompress library is not available, textures are copied as they are", e);
            }
        }
        return loaded;
    }

    /**
     * @return 0 the smaller file was written to dst; 1 nothing written (copy the original); less than 0 error
     */
    public static native int shrinkPng(String src, String dst, int ratio, int minColors);
}
