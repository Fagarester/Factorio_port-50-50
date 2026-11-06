#include <android/hardware_buffer.h>

#include "native_handle.h"

// Same helper as in the original libwinlator (gpu_image.c). vortekrenderer declares it as extern and expects a
// definition at link time; it lives here (hidden, private) so vortekrenderer does not depend on libwinlator.so.
extern const native_handle_t* _Nullable AHardwareBuffer_getNativeHandle(const AHardwareBuffer* _Nonnull buffer);

int AHardwareBuffer_getFd(AHardwareBuffer* hardwareBuffer) {
    const native_handle_t* nativeHandle = AHardwareBuffer_getNativeHandle(hardwareBuffer);
    return nativeHandle->numFds > 0 ? nativeHandle->data[0] : -1;
}
