#include "device_memory_api.hpp"
#include <cassert>
#include <cstring>
#include <cstdio>

int main(){
    for(bool modern:{false,true}){
        auto lookup=[modern](const char* name)->void* {
            return !modern&&!std::strcmp(name,sl::deviceMemoryModernSymbol)?nullptr:reinterpret_cast<void*>(1);
        };
        assert(sl::checkDeviceMemoryApi(lookup)==modern);
        for(const char* missing:sl::deviceMemoryRequiredSymbols){
            bool failed=false;
            try{sl::checkDeviceMemoryApi([&](const char* name)->void*{
                return !std::strcmp(name,missing)?nullptr:lookup(name);
            });}catch(const std::runtime_error& e){failed=std::string(e.what()).find(missing)!=std::string::npos;}
            assert(failed);
        }
    }
    std::puts("PASS device-memory preflight: modern/legacy and each missing required export");
}
