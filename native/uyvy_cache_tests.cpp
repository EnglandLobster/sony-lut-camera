#include "uyvy.hpp"
#include <algorithm>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <vector>

static void require(bool ok,const char* why){if(!ok)throw std::runtime_error(why);}
static std::vector<uint8_t> scene(const std::vector<unsigned>& pages,size_t keys,int width,int height,size_t stride){
    std::vector<uint8_t> image(stride*height+64,0xa7);
    for(int y=0;y<height;y++)for(int x=0;x<width;x+=2){
        unsigned uv=pages[(x/2+y)%pages.size()]*keys+1;
        uint8_t* p=image.data()+size_t(y)*stride+x*2;
        p[0]=uv>>8;p[2]=uv;p[1]=uint8_t(x+y);p[3]=uint8_t(x*3+y*5);
    }
    return image;
}

int main(int argc,char** argv){try{
    if(argc!=3)throw std::runtime_error("usage: cache-tests IDENTITY.cube COLOR.cube");
    const int width=64,height=32;const size_t stride=width*2+17;
    sl::YuvLutTable geometry;const size_t page=geometry.pageBytes(),keys=page/2048;
    for(int file=1;file<argc;file++){
        sl::Lut lut;lut.load(argv[file]);sl::YuvLutTable cache(page*4);cache.init();
        for(unsigned i=0;i<4;i++)require(cache.ensure(lut,i*keys+1,255),"could not fill fixture cache");
        require(cache.residentBytes()==page*4,"fixture did not reach memory cap");
        const auto before=cache.snapshot();auto pixels=scene({0,4},keys,width,height,stride),saved=pixels;
        cache.prepare(lut,pixels.data(),width,height,stride,4);
        require(pixels==saved,"prepare changed source pixels or guards");
        require(cache.evictedPages()==1&&cache.residentBytes()==page*3,"did not evict exactly one unused page");
        auto after=cache.snapshot();require(after[1]==before[1],"evicted a hot page");
        for(size_t uv=keys;uv<keys*2;uv++)require(!after[uv],"evicted page retained stale validity bits");
        require(!cache.ensure(lut,99*keys+1,1),"rare early color consumed reserved scene slot");
        require(cache.ensure(lut,4*keys+1,1),"reserved scene color could not enter cache");
        require(cache.residentBytes()==page*4,"admission exceeded or lost budget");
        auto expected=pixels;sl::apply_uyvy(lut,expected.data(),width,height,stride,1);
        sl::apply_uyvy(lut,pixels.data(),width,height,stride,4,true,&cache);
        require(pixels==expected,"partial eviction changed output or guards");
        size_t entries=cache.populatedEntries();
        cache.prepare(lut,saved.data(),width,height,stride,4);
        require(cache.evictedPages()==0&&cache.populatedEntries()==entries,"same scene at full capacity lost warm entries");

        // Full scene changes must adapt immediately. Revisit an old palette to
        // catch stale masks after decommit; alternate 1/4 workers and odd strides.
        for(int shot=0;shot<8;shot++){
            unsigned start=shot%2?16:32;
            auto source=scene({start,start+1,start+2,start+3},keys,width,height,stride);
            auto reference=source;sl::apply_uyvy(lut,reference.data(),width,height,stride,1);
            cache.prepare(lut,source.data(),width,height,stride,4);
            require(cache.evictedPages()==4,"changed scene did not replace obsolete pages");
            sl::apply_uyvy(lut,source.data(),width,height,stride,shot%2?1:4,true,&cache);
            require(source==reference,"scene change changed exact pixels or padding");
            require(cache.residentBytes()==page*4,"scene change exceeded memory budget");
        }

        // Uniform demand ties should retain resident pages, with exact fallback
        // for the missing page rather than churn on every identical image.
        sl::YuvLutTable ties(page);ties.init();require(ties.ensure(lut,keys+1,255),"tie fixture failed");
        auto tie=scene({1,2},keys,width,height,stride),tieReference=tie;
        sl::apply_uyvy(lut,tieReference.data(),width,height,stride,1);
        ties.prepare(lut,tie.data(),width,height,stride,4);
        require(ties.evictedPages()==0,"equal-demand pages churned");
        sl::apply_uyvy(lut,tie.data(),width,height,stride,4,true,&ties);
        require(tie==tieReference,"tie fallback changed pixels");
        sl::YuvLutTable none(0);auto noCache=saved;
        none.prepare(lut,noCache.data(),width,height,stride,4);
        sl::apply_uyvy(lut,noCache.data(),width,height,stride,4,true,&none);
        require(noCache==expected&&none.residentBytes()==0,"zero-budget fallback failed");
        printf("PASS %s: hot retention, selective eviction, reserved admission, 8 scene changes, exact pixels, bounds, zero budget; page_bytes=%zu\n",argv[file],page);
    }
    return 0;
}catch(const std::exception& e){fprintf(stderr,"FAIL: %s\n",e.what());return 1;}}
