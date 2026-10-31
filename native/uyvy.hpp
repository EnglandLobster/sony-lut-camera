#pragma once
#include "core.hpp"
namespace sl {
class YuvLutTable {
public:
    explicit YuvLutTable(size_t budget=32U*1024*1024);
    ~YuvLutTable();
    void init();
    void build(const Lut& lut,int workers);
    void prepare(const Lut& lut,const uint8_t* image,int width,int height,size_t stride,int workers);
    bool ensure(const Lut& lut,unsigned uv,unsigned lumaMask);
    std::vector<uint8_t> snapshot() const{return ready_;}
    const uint64_t* block(unsigned uv) const {return rgb_+size_t(uv)*256;}
    size_t bytes() const{return rgb_?size_t(256)*256*256*8:0;}
    size_t residentBytes() const;
    size_t populatedEntries() const;
    size_t pageBytes() const{return pageBytes_;}
    size_t evictedPages() const{return evictedPages_;}
    size_t sampledPairs() const{return sampledPairs_;}
private:
    YuvLutTable(const YuvLutTable&);
    YuvLutTable& operator=(const YuvLutTable&);
    void populate(const Lut& lut,const std::vector<int>& indices,int workers);
    uint64_t* rgb_;
    std::vector<uint8_t> ready_;
    const size_t pageBytes_,keysPerPage_;
    std::vector<uint8_t> pages_,reserved_;
    size_t usedPages_,limitPages_,reservedPages_,evictedPages_,sampledPairs_;
    pthread_mutex_t locks_[64];
    pthread_mutex_t budgetLock_;
};
// Full-range JPEG YCbCr, packed U Y0 V Y1; only active pixels are changed.
struct PreviewTarget {uint8_t* rgba;int width,height;size_t stride;};
void apply_uyvy(const Lut& lut,uint8_t* data,int width,int height,size_t stride,int workers,bool staged=true,YuvLutTable* table=nullptr,const PreviewTarget* preview=nullptr);
void preview_uyvy(const uint8_t* source,int width,int height,size_t stride,uint8_t* rgba,int outWidth,int outHeight,size_t outStride);
}
