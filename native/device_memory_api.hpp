#pragma once
#include <stdexcept>
#include <string>

namespace sl {
// Shared by the runtime preflight and the offline firmware symbol audit.
static const char* const deviceMemoryRequiredSymbols[] = {
    "_ZN6scalar18DeviceMemoryDiadem11getPropertyEPKc",
    "_ZN6scalar13CameraSeqUtil11getInstanceEv",
    "_ZN6scalar13CameraSeqUtil15mapSharedMemoryEjjPPvS2_PjPi",
    "_ZN6scalar13CameraSeqUtil17unmapSharedMemoryEPvji"
};
static const char* const deviceMemoryModernSymbol =
    "_ZN6scalar18DeviceMemoryDiadem21getPhysicalMemoryInfoERjS1_";

template<class Lookup> bool checkDeviceMemoryApi(Lookup lookup) {
    for(const char* name:deviceMemoryRequiredSymbols)
        if(!lookup(name))throw std::runtime_error(std::string("device memory API missing: ")+name);
    return lookup(deviceMemoryModernSymbol)!=nullptr;
}
}
