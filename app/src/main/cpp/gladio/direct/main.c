// Gladio server as a plain glibc program on the phone's GLES driver (libhybris): listens on argv[1].
#include <jni.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
jstring Java_com_factory_gladio_GladioServer_start(JNIEnv *env, jclass cls, jstring path);
static const char *GetStringUTFChars(JNIEnv *e, jstring s, jboolean *c) { return (const char *)s; }
static void ReleaseStringUTFChars(JNIEnv *e, jstring s, const char *c) {}
static jstring NewStringUTF(JNIEnv *e, const char *s) { fprintf(stderr, "start failed: %s\n", s); return (jstring)1; }
int main(int argc, char **argv) {
	static struct JNINativeInterface_ fn; fn.GetStringUTFChars = GetStringUTFChars; fn.ReleaseStringUTFChars = ReleaseStringUTFChars; fn.NewStringUTF = NewStringUTF;
	const struct JNINativeInterface_ *env = &fn;
	if (Java_com_factory_gladio_GladioServer_start((JNIEnv *)&env, NULL, (jstring)argv[1])) return 1;
	for (;;) pause();
}
