#pragma once
#include <cstdlib>
#include <cerrno>
#include <stdexcept>

namespace sl {
typedef int (*PhysicalMemoryInfo)(void*,unsigned*,unsigned*);
typedef const char* (*MemoryProperty)(void*,const char*);

inline unsigned memoryNumber(const char* value,bool address=false) {
    if(!value||!*value)throw std::runtime_error("invalid image memory property");
    // Legacy Sony getProperty formats a 32-bit int with %d, including addresses.
    errno=0;char* end=nullptr;long long n=::strtoll(value,&end,0);
    if(errno||!end||*end||n>(long long)0xffffffffU||n<(address?-2147483648LL:0LL))throw std::runtime_error("invalid image memory property");
    return static_cast<unsigned>(n);
}

inline void imageMemorySpan(void* memory,PhysicalMemoryInfo modern,MemoryProperty property,unsigned& address,unsigned& size) {
    if(modern){
        if(!modern(memory,&address,&size))throw std::runtime_error("invalid image memory span");
    }else{
        // A6000 3.21 / A7 3.20 getProperty exposes the same property IDs 1/2
        // consumed by their own readBuffer -> mapSharedMemory implementation.
        // Parse each value immediately: Sony reuses one static string buffer.
        if(!property)throw std::runtime_error("device memory access unavailable");
        address=memoryNumber(property(memory,"memory-address"),true);
        size=memoryNumber(property(memory,"memory-size"));
    }
    if(!address||!size||size>128U*1024*1024)throw std::runtime_error("invalid image memory span");
}
}
