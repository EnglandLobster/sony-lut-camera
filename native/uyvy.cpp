#include "uyvy.hpp"
#include <algorithm>
#include <exception>
#include <cstdio>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>
#include <stdexcept>

namespace sl { namespace {
size_t systemPageBytes(){
    long bytes=sysconf(_SC_PAGESIZE);
    if(bytes<4096||bytes>65536||(bytes&(bytes-1)))throw std::runtime_error("unsupported cache page size");
    return size_t(bytes);
}
inline uint8_t clip(int x){return uint8_t(x<0?0:x>255?255:x);}
uint64_t pack(const uint8_t* rgb){
    int r=rgb[0],g=rgb[1],b=rgb[2];unsigned y=clip((19595*r+38470*g+7471*b+32768)>>16);
    unsigned u=unsigned(-11059*r-21709*g+32768*b+0x800000),v=unsigned(32768*r-27439*g-5329*b+0x800000);
    return uint64_t(y)|(uint64_t(u)<<8)|(uint64_t(v)<<32);
}
// Keep the rare, arithmetic-heavy fallback out of the ARM lookup loop.
__attribute__((noinline)) void uncachedPair(const Lut& lut,const uint8_t* p,uint64_t& a,uint64_t& b){
    int u=int(p[0])-128,v=int(p[2])-128;
    int r=(91881*v+32768)>>16,g=(-22554*u-46802*v+32768)>>16,blue=(116130*u+32768)>>16;
    uint8_t rgb[6];for(int k=0;k<2;k++){int y=p[k*2+1];rgb[k*3]=clip(y+r);rgb[k*3+1]=clip(y+g);rgb[k*3+2]=clip(y+blue);}
    lut.apply(rgb,2);a=pack(rgb);b=pack(rgb+3);
}
__attribute__((noinline)) void missedPair(const Lut& lut,YuvLutTable& table,uint8_t* ready,uint8_t* denied,
                                         const uint8_t* p,unsigned uv,unsigned mask,unsigned y0,unsigned y1,uint64_t& a,uint64_t& b){
    if(!denied[uv]){
        if(table.ensure(lut,uv,mask)){
            ready[uv]|=mask;const uint64_t* block=table.block(uv);a=block[y0];b=block[y1];return;
        }
        denied[uv]=1;
    }
    uncachedPair(lut,p,a,b);
}
void cachedRow(const Lut& lut,YuvLutTable& table,uint8_t* ready,uint8_t* denied,uint8_t* row,int width){
    for(uint8_t* p=row,*end=row+size_t(width)*2;p!=end;p+=4){
        if(end-p>32){
            const uint8_t* next=p+32;const uint64_t* future=table.block(unsigned(next[0])*256+next[2]);
            __builtin_prefetch(future+next[1],0,1);__builtin_prefetch(future+next[3],0,1);
        }
        // One word per packed pair avoids four byte loads/stores on Cortex-A5.
        // memcpy preserves the contract for unaligned rows and strict aliasing.
        uint32_t input;memcpy(&input,p,sizeof(input));
#if __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
        input=__builtin_bswap32(input);
#endif
        const unsigned uv=((input&255)<<8)|((input>>16)&255);
        const unsigned y0=(input>>8)&255,y1=input>>24;
        // Calculate exact values only for the 32-level ranges actually used.
        // Many chroma keys in a detailed scene cover a narrow luma interval.
        const unsigned mask=(1U<<(y0>>5))|(1U<<(y1>>5));
        uint64_t a,b;
        if((ready[uv]&mask)==mask){const uint64_t* block=table.block(uv);a=block[y0];b=block[y1];}
        else missedPair(lut,table,ready,denied,p,uv,mask,y0,y1,a,b);
        // Cast before shifting: on 32-bit ARM the low word already contains
        // Y8 + Cb24, and the high word contains only Cr24. Avoid wide shifts
        // and rebuilding the same UV address for the two luma samples.
        const unsigned u=(uint32_t(a)>>8)+(uint32_t(b)>>8);
        const unsigned v=uint32_t(a>>32)+uint32_t(b>>32);
        uint32_t output=(uint32_t(uint8_t(a))<<8)|(uint32_t(uint8_t(b))<<24)
            |uint32_t(clip((u+65536)>>17))|(uint32_t(clip((v+65536)>>17))<<16);
#if __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
        output=__builtin_bswap32(output);
#endif
        memcpy(p,&output,sizeof(output));
    }
}
void previewRow(const uint8_t* row,uint8_t* out,int outWidth,const int* sourceX){
    for(int x=0;x<outWidth;x++){
        int sx=sourceX[x];const uint8_t* p=row+(sx&~1)*2;
        int yy=p[(sx&1)*2+1],u=int(p[0])-128,v=int(p[2])-128;
        out[x*4]=clip(yy+((91881*v+32768)>>16));
        out[x*4+1]=clip(yy+((-22554*u-46802*v+32768)>>16));
        out[x*4+2]=clip(yy+((116130*u+32768)>>16));out[x*4+3]=255;
    }
}
struct Work {const Lut* lut;uint8_t* data;int width,begin,end;size_t stride;bool staged;YuvLutTable* table;const uint8_t* snapshot;const PreviewTarget* preview;const int* previewX;int height;char error[160];};
void* run(void* opaque){Work& w=*static_cast<Work*>(opaque);
    try{std::vector<uint8_t> rgb(size_t(w.width)*3),cached(w.staged?size_t(w.width)*2:0);
        std::vector<uint8_t> localReady,denied;if(w.table){localReady.assign(w.snapshot,w.snapshot+65536);denied.assign(65536,0);}
        int previewY=w.preview?(w.begin*w.preview->height+w.height-1)/w.height:0;
        for(int y=w.begin;y<w.end;++y){uint8_t* device=w.data+size_t(y)*w.stride;uint8_t* row=w.staged?cached.data():device;
            if(w.staged)memcpy(row,device,size_t(w.width)*2);
            if(w.table){cachedRow(*w.lut,*w.table,localReady.data(),denied.data(),row,w.width);}else{
            for(int x=0;x<w.width;x+=2){const uint8_t* p=row+x*2;
                int u=int(p[0])-128,v=int(p[2])-128;
                int r=(91881*v+32768)>>16,g=(-22554*u-46802*v+32768)>>16,b=(116130*u+32768)>>16;
                for(int k=0;k<2;++k){int yy=p[k*2+1];uint8_t* q=rgb.data()+(x+k)*3;q[0]=clip(yy+r);q[1]=clip(yy+g);q[2]=clip(yy+b);}
            }
            w.lut->apply(rgb.data(),w.width);
            for(int x=0;x<w.width;x+=2){uint8_t* p=row+x*2;int us=0,vs=0;
                for(int k=0;k<2;++k){const uint8_t* q=rgb.data()+(x+k)*3;int r=q[0],g=q[1],b=q[2];
                    p[k*2+1]=clip((19595*r+38470*g+7471*b+32768)>>16);
                    us+=-11059*r-21709*g+32768*b;vs+=32768*r-27439*g-5329*b;
                }
                p[0]=clip(((us+65536)>>17)+128);p[2]=clip(((vs+65536)>>17)+128);
            }
            }
            if(w.preview&&previewY<w.preview->height&&previewY*w.height/w.preview->height==y){
                previewRow(row,w.preview->rgba+size_t(previewY)*w.preview->stride,w.preview->width,w.previewX);previewY++;
            }
            if(w.staged)memcpy(device,row,size_t(w.width)*2);
        }
    }catch(const std::exception& e){snprintf(w.error,sizeof(w.error),"%s",e.what());}
    catch(...){snprintf(w.error,sizeof(w.error),"UYVY worker failed");}return nullptr;
}
struct Build {const Lut* lut;uint64_t* table;uint8_t* ready;const int* indices;int begin,end;unsigned mask;char error[160];};
void* build(void* opaque){Build& b=*static_cast<Build*>(opaque);try{
    for(int i=b.begin;i<b.end;i++){int uv=b.indices[i];
        int u=(uv>>8)-128,v=(uv&255)-128;
        int r=(91881*v+32768)>>16,g=(-22554*u-46802*v+32768)>>16,blue=(116130*u+32768)>>16;
        uint8_t rgb[32*3];unsigned missing=b.mask&~b.ready[uv];
        for(unsigned bit=0;bit<8;bit++)if(missing&(1U<<bit)){
            int first=bit*32;
            for(int j=0;j<32;j++){int y=first+j;rgb[j*3]=clip(y+r);rgb[j*3+1]=clip(y+g);rgb[j*3+2]=clip(y+blue);}
            b.lut->apply(rgb,32);
            for(int j=0;j<32;j++)b.table[size_t(uv)*256+first+j]=pack(rgb+j*3);
        }
        b.ready[uv]|=missing;
    }
}catch(const std::exception& e){snprintf(b.error,sizeof(b.error),"%s",e.what());}catch(...){snprintf(b.error,sizeof(b.error),"YUV table build failed");}return nullptr;}
}
YuvLutTable::YuvLutTable(size_t budget):rgb_(nullptr),ready_(65536,0),
    pageBytes_(systemPageBytes()),keysPerPage_(pageBytes_/2048),
    pages_(65536/keysPerPage_,0),reserved_(pages_.size(),0),
    usedPages_(0),limitPages_(std::min(budget/pageBytes_,pages_.size())),
    reservedPages_(0),evictedPages_(0),sampledPairs_(0){
    for(int i=0;i<64;i++)pthread_mutex_init(&locks_[i],nullptr);
    pthread_mutex_init(&budgetLock_,nullptr);
}
YuvLutTable::~YuvLutTable(){if(rgb_)munmap(rgb_,bytes());for(int i=0;i<64;i++)pthread_mutex_destroy(&locks_[i]);pthread_mutex_destroy(&budgetLock_);}
void YuvLutTable::init(){if(!rgb_){void* p=mmap(nullptr,size_t(256)*256*256*8,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);if(p==MAP_FAILED)throw std::runtime_error("cannot reserve YUV cache");rgb_=static_cast<uint64_t*>(p);}}
size_t YuvLutTable::residentBytes() const{return usedPages_*pageBytes_;}
size_t YuvLutTable::populatedEntries() const{
    size_t blocks=0;for(uint8_t mask:ready_)blocks+=__builtin_popcount(unsigned(mask));return blocks*32;
}
void YuvLutTable::populate(const Lut& lut,const std::vector<int>& indices,int workers){
    if(!lut.size()||workers<1||workers>4)throw std::runtime_error("invalid table build");
    if(indices.empty())return;init();Build tasks[4];pthread_t threads[3];int started=0;
    for(int i=0;i<workers;i++)tasks[i]=Build{&lut,rgb_,ready_.data(),indices.data(),int(indices.size()*i/workers),int(indices.size()*(i+1)/workers),255,{0}};
    for(int i=1;i<workers;i++){if(pthread_create(&threads[started],nullptr,sl::build,&tasks[i])){for(int j=0;j<started;j++)pthread_join(threads[j],nullptr);throw std::runtime_error("table worker creation failed");}started++;}
    sl::build(&tasks[0]);for(int i=0;i<started;i++)pthread_join(threads[i],nullptr);
    for(int i=0;i<workers;i++)if(tasks[i].error[0])throw std::runtime_error(tasks[i].error);
}
// Explicit full-table reference path for host tests, outside the lazy-cache budget.
void YuvLutTable::build(const Lut& lut,int workers){
    std::vector<int> indices;for(int i=0;i<65536;i++)indices.push_back(i);populate(lut,indices,workers);
    std::fill(pages_.begin(),pages_.end(),1);usedPages_=pages_.size();
}
bool YuvLutTable::ensure(const Lut& lut,unsigned uv,unsigned lumaMask){
    if(uv>=65536||!rgb_||!lumaMask||lumaMask>255)throw std::runtime_error("invalid cache key");
    pthread_mutex_t* lock=&locks_[uv%64];pthread_mutex_lock(lock);
    if((ready_[uv]&lumaMask)!=lumaMask){
        pthread_mutex_lock(&budgetLock_);size_t page=uv/keysPerPage_;
        if(!pages_[page]){
            // Preserve slots for the current scene's sampled missing colors;
            // rare early pixels must not consume them before those colors arrive.
            if(usedPages_>=limitPages_||(!reserved_[page]&&limitPages_-usedPages_<=reservedPages_)){
                pthread_mutex_unlock(&budgetLock_);pthread_mutex_unlock(lock);return false;
            }
            pages_[page]=1;usedPages_++;
            if(reserved_[page]){reserved_[page]=0;reservedPages_--;}
        }
        pthread_mutex_unlock(&budgetLock_);
        int index=int(uv);Build task{&lut,rgb_,ready_.data(),&index,0,1,lumaMask,{0}};sl::build(&task);
        if(task.error[0]){pthread_mutex_unlock(lock);throw std::runtime_error(task.error);}}
    pthread_mutex_unlock(lock);return true;
}
void YuvLutTable::prepare(const Lut& lut,const uint8_t* image,int width,int height,size_t stride,int workers){
    if(!image||width<2||width%2||height<1||stride<size_t(width)*2)throw std::runtime_error("invalid YUV cache image");
    if(!lut.size()||workers<1||workers>4)throw std::runtime_error("invalid lazy cache parameters");
    init();evictedPages_=0;sampledPairs_=0;reservedPages_=0;
    std::fill(reserved_.begin(),reserved_.end(),0);
    if(!limitPages_||usedPages_<limitPages_)return;

    // No workers may be reading the table during prepare. Sample only while
    // full, before modifying any image pixels; this is an admission/retention
    // hint, never an approximation to the LUT or a change to its fallback.
    // 32K pairs bound the extra device-memory reads independently of resolution.
    std::vector<unsigned> hits(pages_.size(),0);
    const int columns=std::min(width/2,256),rows=std::min(height,128);
    for(int sy=0;sy<rows;sy++){
        size_t y=(size_t(2*sy+1)*height)/(2*rows);
        const uint8_t* row=image+y*stride;
        for(int sx=0;sx<columns;sx++){
            size_t pair=(size_t(2*sx+1)*(width/2))/(2*columns);
            const uint8_t* p=row+pair*4;
            hits[(unsigned(p[0])*256+p[2])/keysPerPage_]++;
        }
    }
    sampledPairs_=size_t(columns)*rows;
    std::vector<size_t> resident,missing;
    for(size_t p=0;p<pages_.size();p++){
        if(pages_[p])resident.push_back(p);
        else if(hits[p])missing.push_back(p);
    }
    // Ties keep the resident page, avoiding churn for equally rare colors.
    std::sort(resident.begin(),resident.end(),[&hits](size_t a,size_t b){return hits[a]!=hits[b]?hits[a]<hits[b]:a<b;});
    std::sort(missing.begin(),missing.end(),[&hits](size_t a,size_t b){return hits[a]!=hits[b]?hits[a]>hits[b]:a<b;});
    for(size_t i=0;i<std::min(resident.size(),missing.size());i++){
        size_t old=resident[i],next=missing[i];
        if(hits[next]<=hits[old])break;
        if(madvise(reinterpret_cast<uint8_t*>(rgb_)+old*pageBytes_,pageBytes_,MADV_DONTNEED))
            throw std::runtime_error("YUV cache page release failed");
        std::fill(ready_.begin()+old*keysPerPage_,ready_.begin()+(old+1)*keysPerPage_,0);
        pages_[old]=0;usedPages_--;evictedPages_++;
        reserved_[next]=1;reservedPages_++;
    }
}
void apply_uyvy(const Lut& lut,uint8_t* data,int width,int height,size_t stride,int workers,bool staged,YuvLutTable* table,const PreviewTarget* preview){
    if(!data||!lut.size()||width<2||width%2||height<1||stride<size_t(width)*2||workers<1||workers>4)throw std::runtime_error("invalid UYVY image");
    if(table&&table->bytes()!=size_t(256)*256*256*8)throw std::runtime_error("uninitialized YUV table");
    if(preview&&(!preview->rgba||preview->width<1||preview->height<1||preview->width>width||preview->height>height||preview->stride<size_t(preview->width)*4))throw std::runtime_error("invalid fused review geometry");
    Work tasks[4];pthread_t threads[3];int started=0;
    const std::vector<uint8_t> ready=table?table->snapshot():std::vector<uint8_t>();
    std::vector<int> previewX(preview?preview->width:0);for(size_t x=0;x<previewX.size();x++)previewX[x]=int(x)*width/preview->width;
    for(int i=0;i<workers;++i)tasks[i]=Work{&lut,data,width,height*i/workers,height*(i+1)/workers,stride,staged,table,ready.data(),preview,previewX.data(),height,{0}};
    for(int i=1;i<workers;++i){if(pthread_create(&threads[started],nullptr,run,&tasks[i])){
        for(int j=0;j<started;++j)pthread_join(threads[j],nullptr);throw std::runtime_error("UYVY worker creation failed");}++started;}
    run(&tasks[0]);for(int i=0;i<started;++i)pthread_join(threads[i],nullptr);
    for(int i=0;i<workers;++i)if(tasks[i].error[0])throw std::runtime_error(tasks[i].error);
}
void preview_uyvy(const uint8_t* source,int width,int height,size_t stride,uint8_t* rgba,int outWidth,int outHeight,size_t outStride){
    if(!source||!rgba||width<2||width%2||height<1||outWidth<1||outHeight<1||outWidth>width||outHeight>height||stride<size_t(width)*2||outStride<size_t(outWidth)*4)
        throw std::runtime_error("invalid UYVY preview geometry");
    std::vector<uint8_t> row(size_t(width)*2);
    std::vector<int> sourceX(outWidth);for(int x=0;x<outWidth;x++)sourceX[x]=x*width/outWidth;
    for(int y=0;y<outHeight;y++){
        memcpy(row.data(),source+size_t(y*height/outHeight)*stride,row.size());
        previewRow(row.data(),rgba+size_t(y)*outStride,outWidth,sourceX.data());
    }
}
}
