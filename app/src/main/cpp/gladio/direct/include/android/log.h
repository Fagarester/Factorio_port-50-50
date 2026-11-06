#pragma once
#include <stdio.h>
enum { ANDROID_LOG_UNKNOWN, ANDROID_LOG_DEFAULT, ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL };
#define __android_log_print(prio, tag, ...) (fprintf(stderr, "%s: ", tag), fprintf(stderr, __VA_ARGS__), fputc('\n', stderr))
#define __android_log_vprint(prio, tag, fmt, ap) (fprintf(stderr, "%s: ", tag), vfprintf(stderr, fmt, ap), fputc('\n', stderr))
#define __android_log_write(prio, tag, s) fprintf(stderr, "%s: %s\n", tag, s)
