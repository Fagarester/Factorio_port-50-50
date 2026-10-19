#include <jni.h>
#include <stdlib.h>
#include "texcompress.h"

/* com.factorio.core.TextureCompressor.shrinkPng(String src, String dst, int ratio, int minColors, int kmeans, int dither) */
JNIEXPORT jint JNICALL
Java_com_factorio_core_TextureCompressor_shrinkPng(JNIEnv *env, jclass clazz, jstring src, jstring dst, jint ratio, jint minColors, jint kmeans, jint dither) {
    const char *s = (*env)->GetStringUTFChars(env, src, NULL);
    const char *d = (*env)->GetStringUTFChars(env, dst, NULL);
    if (!s || !d) {
        if (s) (*env)->ReleaseStringUTFChars(env, src, s);
        if (d) (*env)->ReleaseStringUTFChars(env, dst, d);
        return -1;
    }
    TexOptions options = {ratio, minColors, kmeans, dither};
    int rc = tex_png_shrink_opts(s, d, &options);
    (*env)->ReleaseStringUTFChars(env, src, s);
    (*env)->ReleaseStringUTFChars(env, dst, d);
    return rc;
}
