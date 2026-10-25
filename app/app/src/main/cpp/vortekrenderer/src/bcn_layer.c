#include <dlfcn.h>
#include <pthread.h>
#include <string.h>

#include "bcn_layer.h"
#include "vulkan_helper.h"
#include "jni_utils.h"

// ---- loader <-> layer interface (vk_layer.h), only the parts needed to chain one layer by hand ----
#define BCN_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO 47
#define BCN_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO 48
#define BCN_LAYER_LINK_INFO 0
#define BCN_LOADER_DATA_CALLBACK 1

typedef VkResult (VKAPI_PTR *PFN_bcnSetInstanceLoaderData)(VkInstance instance, void* object);
typedef VkResult (VKAPI_PTR *PFN_bcnSetDeviceLoaderData)(VkDevice device, void* object);
typedef PFN_vkVoidFunction (VKAPI_PTR *PFN_bcnGetPhysicalDeviceProcAddr)(VkInstance instance, const char* name);

typedef struct BcnInstanceLink {
    struct BcnInstanceLink* pNext;
    PFN_vkGetInstanceProcAddr pfnNextGetInstanceProcAddr;
    PFN_bcnGetPhysicalDeviceProcAddr pfnNextGetPhysicalDeviceProcAddr;
} BcnInstanceLink;

typedef struct BcnDeviceLink {
    struct BcnDeviceLink* pNext;
    PFN_vkGetInstanceProcAddr pfnNextGetInstanceProcAddr;
    PFN_vkGetDeviceProcAddr pfnNextGetDeviceProcAddr;
} BcnDeviceLink;

typedef struct {
    VkStructureType sType;
    const void* pNext;
    int function;
    union {
        BcnInstanceLink* pLayerInfo;
        PFN_bcnSetInstanceLoaderData pfnSetInstanceLoaderData;
        void* reserved[2];
    } u;
} BcnLayerInstanceCreateInfo;

typedef struct {
    VkStructureType sType;
    const void* pNext;
    int function;
    union {
        BcnDeviceLink* pLayerInfo;
        PFN_bcnSetDeviceLoaderData pfnSetDeviceLoaderData;
    } u;
} BcnLayerDeviceCreateInfo;

static pthread_mutex_t bcnMutex = PTHREAD_MUTEX_INITIALIZER;
static bool bcnLoaded = false;
static bool bcnActive = false;
static PFN_vkGetInstanceProcAddr layerGetInstanceProcAddr = NULL;
static PFN_vkGetDeviceProcAddr layerGetDeviceProcAddr = NULL;
// the system driver's entry points, kept as "next" of the layer
static PFN_vkGetInstanceProcAddr nextGetInstanceProcAddr = NULL;
static PFN_vkGetDeviceProcAddr nextGetDeviceProcAddr = NULL;

// Dispatchable objects of the loader start with a pointer to the loader's dispatch table; objects created by the layer must carry the same one.
static VkResult VKAPI_PTR bcnSetInstanceLoaderData(VkInstance instance, void* object) {
    *(void**)object = *(void**)instance;
    return VK_SUCCESS;
}

static VkResult VKAPI_PTR bcnSetDeviceLoaderData(VkDevice device, void* object) {
    *(void**)object = *(void**)device;
    return VK_SUCCESS;
}

bool bcnLayerLoad(const char* path) {
    pthread_mutex_lock(&bcnMutex);
    bool result = bcnLoaded;
    if (!bcnLoaded && path) {
        void* handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        if (!handle) {
            println("vortek: unable to load BCn layer %s: %s", path, dlerror());
        }
        else {
            layerGetInstanceProcAddr = (PFN_vkGetInstanceProcAddr)dlsym(handle, "BCnLayer_GetInstanceProcAddr");
            layerGetDeviceProcAddr = (PFN_vkGetDeviceProcAddr)dlsym(handle, "BCnLayer_GetDeviceProcAddr");
            if (layerGetInstanceProcAddr && layerGetDeviceProcAddr && vulkanWrapper.vkGetInstanceProcAddr) {
                nextGetInstanceProcAddr = vulkanWrapper.vkGetInstanceProcAddr;
                nextGetDeviceProcAddr = vulkanWrapper.vkGetDeviceProcAddr;
                bcnLoaded = result = true;
                println("vortek: BCn layer loaded");
            }
            else println("vortek: BCn layer %s does not export the expected entry points", path);
        }
    }
    pthread_mutex_unlock(&bcnMutex);
    return result;
}

// Called every time the Vulkan wrapper is (re)initialised: its function pointers are fresh then, so a chained layer of a
// previous run must be forgotten (the game can be restarted many times in one process, e.g. by the diagnostics).
void bcnLayerReset(void) {
    pthread_mutex_lock(&bcnMutex);
    bcnLoaded = false;
    bcnActive = false;
    pthread_mutex_unlock(&bcnMutex);
}

bool bcnLayerIsActive(void) {
    return bcnActive;
}

VkResult bcnLayerCreateInstance(const VkInstanceCreateInfo* createInfo, VkInstance* instance) {
    if (!bcnLoaded) return vulkanWrapper.vkCreateInstance(createInfo, NULL, instance);

    PFN_vkCreateInstance layerCreateInstance = (PFN_vkCreateInstance)layerGetInstanceProcAddr(VK_NULL_HANDLE, "vkCreateInstance");
    if (!layerCreateInstance) return vulkanWrapper.vkCreateInstance(createInfo, NULL, instance);

    BcnInstanceLink link = {0};
    link.pfnNextGetInstanceProcAddr = nextGetInstanceProcAddr;

    BcnLayerInstanceCreateInfo linkInfo = {0};
    linkInfo.sType = BCN_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO;
    linkInfo.function = BCN_LAYER_LINK_INFO;
    linkInfo.u.pLayerInfo = &link;

    BcnLayerInstanceCreateInfo callbackInfo = {0};
    callbackInfo.sType = BCN_STRUCTURE_TYPE_LOADER_INSTANCE_CREATE_INFO;
    callbackInfo.pNext = &linkInfo;
    callbackInfo.function = BCN_LOADER_DATA_CALLBACK;
    callbackInfo.u.pfnSetInstanceLoaderData = bcnSetInstanceLoaderData;

    VkInstanceCreateInfo chainedInfo = *createInfo;
    linkInfo.pNext = chainedInfo.pNext;
    chainedInfo.pNext = &callbackInfo;

    VkResult result = layerCreateInstance(&chainedInfo, NULL, instance);
    if (result != VK_SUCCESS) {
        println("vortek: BCn layer vkCreateInstance failed (%d), using the plain driver", result);
        return vulkanWrapper.vkCreateInstance(createInfo, NULL, instance);
    }

    // From now on every lookup goes through the layer first (features, formats, device creation, all commands).
    pthread_mutex_lock(&bcnMutex);
    vulkanWrapper.vkGetInstanceProcAddr = layerGetInstanceProcAddr;
    vulkanWrapper.vkGetDeviceProcAddr = layerGetDeviceProcAddr;
    bcnActive = true;
    pthread_mutex_unlock(&bcnMutex);
    return result;
}

VkResult bcnLayerCreateDevice(VkPhysicalDevice physicalDevice, const VkDeviceCreateInfo* createInfo, VkDevice* device) {
    if (!bcnActive) return vulkanWrapper.vkCreateDevice(physicalDevice, createInfo, NULL, device);

    BcnDeviceLink link = {0};
    link.pfnNextGetInstanceProcAddr = nextGetInstanceProcAddr;
    link.pfnNextGetDeviceProcAddr = nextGetDeviceProcAddr;

    BcnLayerDeviceCreateInfo linkInfo = {0};
    linkInfo.sType = BCN_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO;
    linkInfo.function = BCN_LAYER_LINK_INFO;
    linkInfo.u.pLayerInfo = &link;

    BcnLayerDeviceCreateInfo callbackInfo = {0};
    callbackInfo.sType = BCN_STRUCTURE_TYPE_LOADER_DEVICE_CREATE_INFO;
    callbackInfo.pNext = &linkInfo;
    callbackInfo.function = BCN_LOADER_DATA_CALLBACK;
    callbackInfo.u.pfnSetDeviceLoaderData = bcnSetDeviceLoaderData;

    VkDeviceCreateInfo chainedInfo = *createInfo;
    linkInfo.pNext = chainedInfo.pNext;
    chainedInfo.pNext = &callbackInfo;

    // vulkanWrapper.vkCreateDevice was resolved through the layer (see loadVulkanInstanceFuncs)
    return vulkanWrapper.vkCreateDevice(physicalDevice, &chainedInfo, NULL, device);
}
