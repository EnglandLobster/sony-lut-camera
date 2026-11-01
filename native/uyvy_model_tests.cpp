#include "uyvy.hpp"
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <vector>

static void row(uint8_t* p,int width,int y){
    for(int x=0;x<width;x+=2){
        // Repeatable broad chroma/luma demand exercises budget exhaustion.
        unsigned v=(unsigned(x/2)*2654435761U+unsigned(y)*2246822519U);
        p[x*2]=uint8_t(v>>8);p[x*2+1]=uint8_t(v>>16);
        p[x*2+2]=uint8_t(v>>24);p[x*2+3]=uint8_t(v);
    }
}
int main(int argc,char** argv){try{
    if(argc!=6)throw std::runtime_error("usage: model-tests LUT width height cacheMiB workers");
    int width=std::atoi(argv[2]),height=std::atoi(argv[3]),budget=std::atoi(argv[4]),workers=std::atoi(argv[5]);
    if(width<2||width%2||height<1||width>10000||height>10000||budget<0||budget>32||workers<1||workers>4)
        throw std::runtime_error("invalid fixture parameters");
    const size_t stride=size_t(width)*2+18,guard=64;
    std::vector<uint8_t> image(stride*height+2*guard,0xa7),reference(size_t(width)*2);
    for(int y=0;y<height;y++)row(image.data()+guard+stride*y,width,y);
    sl::Lut lut;lut.load(argv[1]);sl::YuvLutTable cache(size_t(budget)*1024*1024);cache.init();
    double begin=sl::milliseconds();
    cache.prepare(lut,image.data()+guard,width,height,stride,workers);
    sl::apply_uyvy(lut,image.data()+guard,width,height,stride,workers,true,&cache);
    double processing=sl::milliseconds()-begin;
    // Check every pixel against the exact uncached implementation, retaining
    // only one reference row rather than a second full-resolution image.
    for(int y=0;y<height;y++){
        row(reference.data(),width,y);
        sl::apply_uyvy(lut,reference.data(),width,1,reference.size(),1);
        const uint8_t* actual=image.data()+guard+stride*y;
        if(std::memcmp(actual,reference.data(),reference.size()))throw std::runtime_error("exact pixel mismatch");
        for(size_t x=reference.size();x<stride;x++)if(actual[x]!=0xa7)throw std::runtime_error("row padding overwritten");
    }
    for(size_t i=0;i<guard;i++)if(image[i]!=0xa7||image[image.size()-1-i]!=0xa7)throw std::runtime_error("guard overwritten");
    if(cache.residentBytes()>size_t(budget)*1024*1024)throw std::runtime_error("cache budget exceeded");
    std::printf("{\"width\":%d,\"height\":%d,\"workers\":%d,\"cache_budget_mib\":%d,\"cache_resident_bytes\":%zu,\"image_bytes\":%zu,\"host_processing_ms\":%.2f,\"exact_pixels\":true,\"guards\":true}\n",
        width,height,workers,budget,cache.residentBytes(),image.size(),processing);
    return 0;
}catch(const std::exception& error){std::fprintf(stderr,"FAIL %s\n",error.what());return 1;}}
