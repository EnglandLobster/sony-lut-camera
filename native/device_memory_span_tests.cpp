#include "device_memory_span.hpp"
#include <cassert>
#include <cstring>
#include <cstdio>
#include <initializer_list>

static char shared[64];
static const char* sizeValue="48000000";
static const char* addressValue="-2128396288"; // 0x81234000, Sony's signed %d
static int calls=0;
static const char* property(void*,const char* key){
    ++calls;std::strcpy(shared,std::strcmp(key,"memory-address")==0?addressValue:sizeValue);return shared;
}
static int modern(void*,unsigned* a,unsigned* s){*a=0x81234000;*s=48000000;return 1;}
static int failed(void*,unsigned*,unsigned*){return 0;}
int main(){
    unsigned a=0,s=0;
    sl::imageMemorySpan(nullptr,nullptr,property,a,s);assert(a==0x81234000&&s==48000000&&calls==2);
    addressValue="0x81234000";sl::imageMemorySpan(nullptr,nullptr,property,a,s);assert(a==0x81234000);
    calls=0;sl::imageMemorySpan(nullptr,modern,property,a,s);assert(a==0x81234000&&s==48000000&&calls==0);
    try{sl::imageMemorySpan(nullptr,failed,property,a,s);assert(false);}catch(const std::runtime_error&){}assert(calls==0);
    for(const char* bad:{"0","134217729","4294967296","-1","12junk",""}){
        sizeValue=bad;try{sl::imageMemorySpan(nullptr,nullptr,property,a,s);assert(false);}catch(const std::runtime_error&){}
    }
    try{sl::imageMemorySpan(nullptr,nullptr,nullptr,a,s);assert(false);}catch(const std::runtime_error&){}
    std::puts("PASS modern/legacy memory span, shared buffer, failure and bounds");
}
