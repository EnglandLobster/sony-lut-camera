#include "uyvy.hpp"
#include <cmath>
#include <cstdio>
#include <stdexcept>
#include <vector>
#include <algorithm>
#include <cstring>
static int clamp(double x){return std::max(0,std::min(255,int(std::floor(x+0.5))));}
static void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
int main(){try{
    const int width=512,height=128,stride=width*2+32;unsigned seed=17;
    std::vector<uint8_t> original(stride*height+64,0xa7);
    for(int y=0;y<height;y++)for(int x=0;x<width*2;x++){seed=seed*1664525U+1013904223U;original[y*stride+x]=seed>>24;}
    const int pw=73,ph=37,ps=pw*4+16;std::vector<uint8_t> preview(ps*ph+64,0x5c);
    auto untouched=original;sl::preview_uyvy(original.data(),width,height,stride,preview.data(),pw,ph,ps);
    require(original==untouched,"review modified full-resolution image");
    for(int y=0;y<ph;y++){
        for(int x=0;x<pw;x++){
            int sx=x*width/pw,sy=y*height/ph;const uint8_t* p=original.data()+sy*stride+(sx&~1)*2;
            int yy=p[(sx&1)*2+1],u=int(p[0])-128,v=int(p[2])-128;
            int expected[]={clamp(yy+1.402*v),clamp(yy-0.344136*u-0.714136*v),clamp(yy+1.772*u),255};
            for(int k=0;k<4;k++)require(std::abs(expected[k]-int(preview[y*ps+x*4+k]))<=1,"review channel or sample mismatch");
        }
        for(int x=pw*4;x<ps;x++)require(preview[y*ps+x]==0x5c,"review modified row padding");
    }
    for(size_t i=ps*ph;i<preview.size();i++)require(preview[i]==0x5c,"review modified buffer guard");
    puts("PASS direct review sampling, RGBA channels, source preservation and bounds");
    for(const char* name:{"out/tests/ID33.CUB","out/tests/COLOR17.CUB"}){
        sl::Lut lut;lut.load(name);auto one=original,four=original,direct=original;
        sl::YuvLutTable geometry;const size_t pageBytes=geometry.pageBytes(),pageKeys=pageBytes/2048;
        sl::YuvLutTable incremental(pageBytes);incremental.init();
        require(incremental.ensure(lut,0,1)&&incremental.populatedEntries()==32,"first luma range not built exactly");
        require(incremental.ensure(lut,0,129)&&incremental.populatedEntries()==64,"luma ranges overwritten or rebuilt");
        require(incremental.ensure(lut,1,2)&&incremental.populatedEntries()==96,"shared page budget rejected valid range");
        require(!incremental.ensure(lut,pageKeys,1)&&incremental.snapshot()[pageKeys]==0,"denied page published uninitialized range");
        sl::apply_uyvy(lut,one.data(),width,height,stride,1);sl::apply_uyvy(lut,four.data(),width,height,stride,4);
        sl::apply_uyvy(lut,direct.data(),width,height,stride,4,false);require(direct==four,"staged and direct results differ");
        sl::YuvLutTable table;table.build(lut,4);auto cached=original;sl::apply_uyvy(lut,cached.data(),width,height,stride,4,true,&table);require(cached==four,"precomputed YUV lookup changed pixels");
        // Word loads must also work without staging, with an unaligned base
        // and odd row stride. Include untouched prefix, padding and tail bytes.
        std::vector<uint8_t> unaligned(size_t(stride+1)*height+65,0xa7);
        for(int y=0;y<height;y++)memcpy(unaligned.data()+1+size_t(y)*(stride+1),original.data()+size_t(y)*stride,width*2);
        auto unalignedExpected=unaligned;
        sl::apply_uyvy(lut,unalignedExpected.data()+1,width,height,stride+1,1,false);
        sl::apply_uyvy(lut,unaligned.data()+1,width,height,stride+1,4,false,&table);
        require(unaligned==unalignedExpected,"packed lookup changed unaligned rows or guards");
        sl::YuvLutTable sparse;sparse.prepare(lut,original.data(),width,height,stride,4);auto lazy=original;sl::apply_uyvy(lut,lazy.data(),width,height,stride,4,true,&sparse);require(lazy==four,"sparse YUV lookup changed pixels");
        // Reuse the exact production cache across shots; preparation must keep
        // valid entries under budget and every photo must match uncached output.
        sl::YuvLutTable session;auto scene=original;
        for(int y=0;y<height;y++)for(int x=0;x<width;x+=2){scene[y*stride+x*2]=uint8_t(120+y%8);scene[y*stride+x*2+2]=uint8_t(125+x%8);}
        auto reference=scene;sl::apply_uyvy(lut,reference.data(),width,height,stride,1);
        size_t entries=0;
        for(int shot=0;shot<5;shot++){
            session.prepare(lut,scene.data(),width,height,stride,4);
            if(shot)require(session.populatedEntries()==entries,"warm session discarded valid cache");
            auto result=scene;sl::apply_uyvy(lut,result.data(),width,height,stride,4,true,&session);
            require(result==reference,"cross-photo cache reuse changed output or guards");
            if(shot)require(session.populatedEntries()==entries,"same scene rebuilt cached colors");
            entries=session.populatedEntries();require(entries>0,"cache remained empty");
        }
        std::vector<uint8_t> separate(ps*ph+64,0x5c);sl::preview_uyvy(four.data(),width,height,stride,separate.data(),pw,ph,ps);
        for(int workers:{1,4}){
            auto fused=original;std::vector<uint8_t> fusedReview(ps*ph+64,0x5c);
            sl::PreviewTarget target{fusedReview.data(),pw,ph,ps};
            sl::apply_uyvy(lut,fused.data(),width,height,stride,workers,true,&sparse,&target);
            require(fused==four,"fused review changed full-resolution output");
            require(fusedReview==separate,"fused review differs from separate pass or changed bounds");
        }
        sl::YuvLutTable bounded(pageBytes);bounded.prepare(lut,original.data(),width,height,stride,4);auto limited=original;
        sl::apply_uyvy(lut,limited.data(),width,height,stride,4,true,&bounded);
        require(limited==four,"cache budget fallback changed pixels");require(bounded.residentBytes()<=pageBytes,"cache budget exceeded");
        bounded.prepare(lut,original.data(),width,height,stride,4);
        limited=original;sl::apply_uyvy(lut,limited.data(),width,height,stride,4,true,&bounded);
        require(limited==four&&bounded.residentBytes()<=pageBytes,"bounded cache reuse changed pixels or exceeded budget");
        require(one==four,"thread outputs differ");int maxError=0;
        for(int y=0;y<height;y++){
            for(int x=0;x<width;x+=2){const uint8_t* p=original.data()+y*stride+x*2;uint8_t rgb[6];
                for(int k=0;k<2;k++){double yy=p[k*2+1],u=int(p[0])-128,v=int(p[2])-128;
                    rgb[k*3]=clamp(yy+1.402*v);rgb[k*3+1]=clamp(yy-0.344136*u-0.714136*v);rgb[k*3+2]=clamp(yy+1.772*u);}
                lut.apply(rgb,2);double u=0,v=0;int expected[4];
                for(int k=0;k<2;k++){double r=rgb[k*3],g=rgb[k*3+1],b=rgb[k*3+2];expected[k*2+1]=clamp(0.299*r+0.587*g+0.114*b);u+=-0.168736*r-0.331264*g+0.5*b;v+=0.5*r-0.418688*g-0.081312*b;}
                expected[0]=clamp(128+u/2);expected[2]=clamp(128+v/2);
                for(int k=0;k<4;k++)maxError=std::max(maxError,std::abs(expected[k]-int(one[y*stride+x*2+k])));
            }
            for(int k=width*2;k<stride;k++)require(one[y*stride+k]==0xa7,"modified row padding");
        }
        for(size_t i=stride*height;i<one.size();i++)require(one[i]==0xa7,"modified image guard");
        require(maxError<=2,"fixed-point conversion differs from floating reference");printf("PASS %s: 1/4 threads, padding, float reference max error %d/255\n",name,maxError);
    }
    sl::Lut identity;identity.load("out/tests/ID33.CUB");std::vector<uint8_t> gray(1024);
    for(int i=0;i<256;i++){gray[i*4]=128;gray[i*4+1]=i;gray[i*4+2]=128;gray[i*4+3]=i;}auto saved=gray;
    sl::apply_uyvy(identity,gray.data(),512,1,1024,4);require(gray==saved,"neutral identity changed");
    bool rejected=false;try{sl::apply_uyvy(identity,gray.data(),511,1,1024,4);}catch(const std::exception&){rejected=true;}require(rejected,"odd UYVY width accepted");
    puts("PASS neutral ramp and invalid geometry");return 0;
}catch(const std::exception& e){fprintf(stderr,"FAIL: %s\n",e.what());return 1;}}
