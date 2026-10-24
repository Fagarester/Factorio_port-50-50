#pragma once

#include <stdbool.h>
#include "vulkan/vulkan.h"

// Optional GPU BCn emulation: loads the (bionic) Vulkan layer libbcn_layer.so inside the Vortek server process and
// chains it between Vortek and the system Vulkan driver (Mali has no native BC textures). When the layer is not loaded,
// or anything fails, every call falls through to the plain driver and the CPU texture decoder keeps working as before.
extern bool bcnLayerLoad(const char* path);
extern bool bcnLayerIsActive(void);
extern VkResult bcnLayerCreateInstance(const VkInstanceCreateInfo* createInfo, VkInstance* instance);
extern VkResult bcnLayerCreateDevice(VkPhysicalDevice physicalDevice, const VkDeviceCreateInfo* createInfo, VkDevice* device);
