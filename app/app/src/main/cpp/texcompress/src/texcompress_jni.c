#include <jni.h>
#include <stdlib.h>
#include "texcompress.h"

/* com.factorio.core.TextureCompressor.shrinkPng(String src, String dst, int ratio, int minColors) */
JNIEXPORT jint JNICALL
Java_com_factorio_core_TextureCompressor_shrinkPng(JNIEnv *env, jclass clazz, jstring src, jstring dst, jint ratio, jint minColors) {
    const char *s = (*env)->GetStringUTFChars(env, src, NULL);
    const char *d = (*env)->GetStringUTFChars(env, dst, NULL);
    if (!s || !d) {
        if (s) (*env)->ReleaseStringUTFChars(env, src, s);
        if (d) (*env)->ReleaseStringUTFChars(env, dst, d);
        return -1;
    }
    int rc = tex_png_shrink(s, d, ratio, minColors);
    (*env)->ReleaseStringUTFChars(env, src, s);
    (*env)->ReleaseStringUTFChars(env, dst, d);
    return rc;
}
