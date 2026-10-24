#include "gl_context.h"

JNIEXPORT jlong JNICALL
Java_com_factorio_xserver_extensions_LegacyGLXExtension_createGLContext(JNIEnv *env, jobject obj,
                                                                  jint clientFd) {
    GLContext* context = createGLContext(env, obj, clientFd);
    return (jlong)context;
}

JNIEXPORT void JNICALL
Java_com_factorio_xserver_extensions_LegacyGLXExtension_destroyGLContext(JNIEnv *env, jobject obj,
                                                                   jlong contextPtr) {
    destroyGLContext(env, (GLContext*)contextPtr);
}

JNIEXPORT jlong JNICALL
Java_com_factorio_xserver_extensions_LegacyGLXExtension_createGLXContext(JNIEnv *env, jobject obj,
                                                                   jint contextId, jlong sharedContextPtr) {
    GLXContext* context = createGLXContext(contextId, (GLXContext*)sharedContextPtr);
    return (jlong)context;
}

JNIEXPORT void JNICALL
Java_com_factorio_xserver_extensions_LegacyGLXExtension_destroyGLXContext(JNIEnv *env, jobject obj,
                                                                    jlong contextPtr) {
    destroyGLXContext((GLXContext*)contextPtr);
}