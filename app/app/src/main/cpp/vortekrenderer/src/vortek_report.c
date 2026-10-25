#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>
#include <string.h>

#include "vulkan_helper.h"
#include "jni_utils.h"

// One-shot diagnostic report of the system Vulkan driver (what Vortek really sits on). Built from a throw-away
// instance created straight on the plain driver, so it describes the driver and not the BCn layer.

typedef struct {
    char* data;
    size_t size;
    size_t capacity;
} ReportBuffer;

static void reportf(ReportBuffer* buf, const char* fmt, ...) {
    char line[1024];
    va_list args;
    va_start(args, fmt);
    int length = vsnprintf(line, sizeof(line), fmt, args);
    va_end(args);
    if (length <= 0) return;
    if (length >= (int)sizeof(line)) length = sizeof(line) - 1;
    if (buf->size + length + 1 > buf->capacity) {
        size_t capacity = (buf->capacity ? buf->capacity * 2 : 4096) + length;
        char* data = realloc(buf->data, capacity);
        if (!data) return;
        buf->data = data;
        buf->capacity = capacity;
    }
    memcpy(buf->data + buf->size, line, length);
    buf->size += length;
    buf->data[buf->size] = '\0';
}

static void reportFormat(ReportBuffer* buf, PFN_vkGetPhysicalDeviceFormatProperties getFormatProperties, VkPhysicalDevice device, const char* name, VkFormat format) {
    VkFormatProperties props = {0};
    getFormatProperties(device, format, &props);
    reportf(buf, "  %-26s optimal=0x%08x linear=0x%08x buffer=0x%08x\n", name, props.optimalTilingFeatures, props.linearTilingFeatures, props.bufferFeatures);
}

static char* buildReport(void) {
    ReportBuffer buf = {0};
    PFN_vkGetInstanceProcAddr gipa = vulkanWrapper.vkGetInstanceProcAddr;
    if (!gipa || !vulkanWrapper.vkCreateInstance) {
        reportf(&buf, "Vulkan wrapper is not initialised\n");
        return buf.data;
    }

    uint32_t instanceVersion = VK_API_VERSION_1_0;
    if (vulkanWrapper.vkEnumerateInstanceVersion) vulkanWrapper.vkEnumerateInstanceVersion(&instanceVersion);
    reportf(&buf, "Vulkan instance version: %u.%u.%u\n", VK_VERSION_MAJOR(instanceVersion), VK_VERSION_MINOR(instanceVersion), VK_VERSION_PATCH(instanceVersion));

    VkApplicationInfo appInfo = {0};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.apiVersion = instanceVersion >= VK_API_VERSION_1_1 ? VK_API_VERSION_1_1 : VK_API_VERSION_1_0;
    VkInstanceCreateInfo createInfo = {0};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = vulkanWrapper.vkCreateInstance(&createInfo, NULL, &instance);
    if (result != VK_SUCCESS || !instance) {
        reportf(&buf, "vkCreateInstance failed: %d\n", result);
        return buf.data;
    }

    PFN_vkEnumeratePhysicalDevices enumerateDevices = (PFN_vkEnumeratePhysicalDevices)gipa(instance, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceProperties getProperties = (PFN_vkGetPhysicalDeviceProperties)gipa(instance, "vkGetPhysicalDeviceProperties");
    PFN_vkGetPhysicalDeviceFeatures getFeatures = (PFN_vkGetPhysicalDeviceFeatures)gipa(instance, "vkGetPhysicalDeviceFeatures");
    PFN_vkGetPhysicalDeviceMemoryProperties getMemory = (PFN_vkGetPhysicalDeviceMemoryProperties)gipa(instance, "vkGetPhysicalDeviceMemoryProperties");
    PFN_vkGetPhysicalDeviceFormatProperties getFormat = (PFN_vkGetPhysicalDeviceFormatProperties)gipa(instance, "vkGetPhysicalDeviceFormatProperties");
    PFN_vkEnumerateDeviceExtensionProperties enumerateExtensions = (PFN_vkEnumerateDeviceExtensionProperties)gipa(instance, "vkEnumerateDeviceExtensionProperties");
    PFN_vkDestroyInstance destroyInstance = (PFN_vkDestroyInstance)gipa(instance, "vkDestroyInstance");

    uint32_t deviceCount = 0;
    if (enumerateDevices && getProperties && getFeatures && getMemory && getFormat && enumerateExtensions &&
        enumerateDevices(instance, &deviceCount, NULL) == VK_SUCCESS && deviceCount > 0) {
        if (deviceCount > 4) deviceCount = 4;
        VkPhysicalDevice devices[4];
        enumerateDevices(instance, &deviceCount, devices);

        for (uint32_t i = 0; i < deviceCount; i++) {
            VkPhysicalDevice device = devices[i];
            VkPhysicalDeviceProperties props = {0};
            getProperties(device, &props);
            reportf(&buf, "\n[Device %u] %s\n", i, props.deviceName);
            reportf(&buf, "  type=%d vendorID=0x%x deviceID=0x%x apiVersion=%u.%u.%u driverVersion=0x%x\n", props.deviceType, props.vendorID, props.deviceID,
                    VK_VERSION_MAJOR(props.apiVersion), VK_VERSION_MINOR(props.apiVersion), VK_VERSION_PATCH(props.apiVersion), props.driverVersion);

            const VkPhysicalDeviceLimits* limits = &props.limits;
            reportf(&buf, "  limits: maxImageDimension2D=%u maxImageArrayLayers=%u maxStorageBufferRange=%u maxUniformBufferRange=%u\n",
                    limits->maxImageDimension2D, limits->maxImageArrayLayers, limits->maxStorageBufferRange, limits->maxUniformBufferRange);
            reportf(&buf, "  limits: optimalBufferCopyOffsetAlignment=%llu optimalBufferCopyRowPitchAlignment=%llu nonCoherentAtomSize=%llu minMemoryMapAlignment=%zu bufferImageGranularity=%llu\n",
                    (unsigned long long)limits->optimalBufferCopyOffsetAlignment, (unsigned long long)limits->optimalBufferCopyRowPitchAlignment,
                    (unsigned long long)limits->nonCoherentAtomSize, limits->minMemoryMapAlignment, (unsigned long long)limits->bufferImageGranularity);
            reportf(&buf, "  limits: minTexelBufferOffsetAlignment=%llu minUniformBufferOffsetAlignment=%llu minStorageBufferOffsetAlignment=%llu maxSamplerAnisotropy=%.1f\n",
                    (unsigned long long)limits->minTexelBufferOffsetAlignment, (unsigned long long)limits->minUniformBufferOffsetAlignment,
                    (unsigned long long)limits->minStorageBufferOffsetAlignment, limits->maxSamplerAnisotropy);

            VkPhysicalDeviceFeatures f = {0};
            getFeatures(device, &f);
            reportf(&buf, "  features: BC=%u ETC2=%u ASTC_LDR=%u depthClamp=%u samplerAnisotropy=%u independentBlend=%u fragmentStoresAndAtomics=%u\n",
                    f.textureCompressionBC, f.textureCompressionETC2, f.textureCompressionASTC_LDR, f.depthClamp, f.samplerAnisotropy, f.independentBlend, f.fragmentStoresAndAtomics);
            reportf(&buf, "  features: shaderStorageImageExtendedFormats=%u shaderStorageImageWriteWithoutFormat=%u shaderInt16=%u shaderFloat64=%u geometryShader=%u tessellationShader=%u logicOp=%u\n",
                    f.shaderStorageImageExtendedFormats, f.shaderStorageImageWriteWithoutFormat, f.shaderInt16, f.shaderFloat64, f.geometryShader, f.tessellationShader, f.logicOp);

            VkPhysicalDeviceMemoryProperties memory = {0};
            getMemory(device, &memory);
            for (uint32_t h = 0; h < memory.memoryHeapCount; h++)
                reportf(&buf, "  heap %u: %llu MB flags=0x%x\n", h, (unsigned long long)(memory.memoryHeaps[h].size >> 20), memory.memoryHeaps[h].flags);
            for (uint32_t t = 0; t < memory.memoryTypeCount; t++)
                reportf(&buf, "  memoryType %u: heap=%u flags=0x%x\n", t, memory.memoryTypes[t].heapIndex, memory.memoryTypes[t].propertyFlags);

            reportf(&buf, "  format features (hex VkFormatFeatureFlags):\n");
            reportFormat(&buf, getFormat, device, "R8G8B8A8_UNORM", VK_FORMAT_R8G8B8A8_UNORM);
            reportFormat(&buf, getFormat, device, "B8G8R8A8_UNORM", VK_FORMAT_B8G8R8A8_UNORM);
            reportFormat(&buf, getFormat, device, "R8_UNORM", VK_FORMAT_R8_UNORM);
            reportFormat(&buf, getFormat, device, "R16G16B16A16_SFLOAT", VK_FORMAT_R16G16B16A16_SFLOAT);
            reportFormat(&buf, getFormat, device, "BC1_RGBA_UNORM", VK_FORMAT_BC1_RGBA_UNORM_BLOCK);
            reportFormat(&buf, getFormat, device, "BC3_UNORM", VK_FORMAT_BC3_UNORM_BLOCK);
            reportFormat(&buf, getFormat, device, "BC7_UNORM", VK_FORMAT_BC7_UNORM_BLOCK);
            reportFormat(&buf, getFormat, device, "ETC2_R8G8B8A8_UNORM", VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK);
            reportFormat(&buf, getFormat, device, "ASTC_4x4_UNORM", VK_FORMAT_ASTC_4x4_UNORM_BLOCK);
            reportFormat(&buf, getFormat, device, "D32_SFLOAT", VK_FORMAT_D32_SFLOAT);
            reportFormat(&buf, getFormat, device, "D24_UNORM_S8_UINT", VK_FORMAT_D24_UNORM_S8_UINT);
            reportFormat(&buf, getFormat, device, "D32_SFLOAT_S8_UINT", VK_FORMAT_D32_SFLOAT_S8_UINT);
            reportFormat(&buf, getFormat, device, "X8_D24_UNORM_PACK32", VK_FORMAT_X8_D24_UNORM_PACK32);

            uint32_t extensionCount = 0;
            enumerateExtensions(device, NULL, &extensionCount, NULL);
            VkExtensionProperties* extensions = extensionCount ? calloc(extensionCount, sizeof(VkExtensionProperties)) : NULL;
            if (extensions && enumerateExtensions(device, NULL, &extensionCount, extensions) == VK_SUCCESS) {
                reportf(&buf, "  device extensions (%u):\n", extensionCount);
                for (uint32_t e = 0; e < extensionCount; e++) reportf(&buf, "    %s\n", extensions[e].extensionName);
            }
            free(extensions);
        }
    }
    else reportf(&buf, "no physical devices\n");

    if (destroyInstance) destroyInstance(instance, NULL);
    return buf.data;
}

JNIEXPORT jstring JNICALL
Java_com_factorio_xenvironment_components_VortekRendererComponent_getVulkanReport(JNIEnv *env, jobject obj) {
    char* report = buildReport();
    jstring result = (*env)->NewStringUTF(env, report ? report : "(no report)");
    free(report);
    return result;
}
