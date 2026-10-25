#ifndef VORTEK_RESOURCE_MEMORY_H
#define VORTEK_RESOURCE_MEMORY_H

#include <android/hardware_buffer.h>

#include "vortek.h"

typedef enum ResourceMemoryType {
    RESOURCE_MEMORY_TYPE_AUTO,
    RESOURCE_MEMORY_TYPE_OPAQUE_FD,
    RESOURCE_MEMORY_TYPE_DMA_BUF,
    RESOURCE_MEMORY_TYPE_AHARDWAREBUFFER
} ResourceMemoryType;

typedef struct ResourceMemory {
    int fd;
    AHardwareBuffer* hardwareBuffer;
    VkBuffer buffer;
    VkDeviceMemory memory;
    VkDeviceSize allocationSize;
    bool syncUnsupported; // DMA_BUF_IOCTL_SYNC failed once on this fd (opaque fd etc.): do not try again
} ResourceMemory;

extern ResourceMemory* ResourceMemory_allocate(VkContext* context, VkDevice device, VkMemoryAllocateInfo* memoryInfo);
extern void ResourceMemory_free(VkContext* context, VkDevice device, ResourceMemory* resourceMemory);

// CPU cache maintenance for memory that the guest writes through its own mapping and the GPU reads (and back).
// mode 0 = off, 1 = on vkFlushMappedMemoryRanges / vkInvalidateMappedMemoryRanges, 2 = additionally every host-visible block before each vkQueueSubmit.
extern void ResourceMemory_setSyncMode(int mode);
extern int ResourceMemory_getSyncMode(void);
extern void ResourceMemory_syncByMemory(VkDeviceMemory memory, bool invalidate);
extern void ResourceMemory_syncAll(void);
extern int ResourceMemory_getSyncCount(void); // successful ioctl calls, for the report

#endif
