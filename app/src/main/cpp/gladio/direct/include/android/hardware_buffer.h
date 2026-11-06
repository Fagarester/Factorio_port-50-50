#pragma once
#include <stdint.h>
typedef struct AHardwareBuffer AHardwareBuffer;
typedef struct AHardwareBuffer_Desc { uint32_t width, height, layers, format; uint64_t usage; uint32_t stride, rfu0; uint64_t rfu1; } AHardwareBuffer_Desc;
enum { AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM = 1, AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM = 2 };
#define AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN 3ULL
#define AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN (3ULL << 4)
#define AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE (1ULL << 8)
#define AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT (1ULL << 9)
int AHardwareBuffer_allocate(const AHardwareBuffer_Desc *desc, AHardwareBuffer **out);
void AHardwareBuffer_acquire(AHardwareBuffer *b);
void AHardwareBuffer_release(AHardwareBuffer *b);
void AHardwareBuffer_describe(const AHardwareBuffer *b, AHardwareBuffer_Desc *desc);
int AHardwareBuffer_lock(AHardwareBuffer *b, uint64_t usage, int32_t fence, const void *rect, void **out);
int AHardwareBuffer_unlock(AHardwareBuffer *b, int32_t *fence);
int AHardwareBuffer_sendHandleToUnixSocket(const AHardwareBuffer *b, int fd);
int AHardwareBuffer_recvHandleFromUnixSocket(int fd, AHardwareBuffer **out);
