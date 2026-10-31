// Host-only checks for packed Q12 vertices and branchless tetrahedral selection.
// Device precision, texture sampling and speed remain separate GPU tests.
#include "../core.hpp"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>
#include <vector>
static void require(bool p,const char* message){if(!p)throw std::runtime_error(message);}
static unsigned rng(unsigned& s){s^=s<<13;s^=s>>17;s^=s<<5;return s;}
static void branchless(const sl::Lut& lut,uint8_t* rgb){
    const sl::Axis *a[3]={&lut.axis(0,rgb[0]),&lut.axis(1,rgb[1]),&lut.axis(2,rgb[2])};
    const int f[]={a[0]->fraction,a[1]->fraction,a[2]->fraction};
    const int top=std::max(f[0],std::max(f[1],f[2])),bottom=std::min(f[0],std::min(f[1],f[2]));
    const int middle=std::max(std::min(f[0],f[1]),std::min(std::max(f[0],f[1]),f[2]));
    int p[4]={a[0]->base+a[1]->base+a[2]->base,0,0,0};
    p[3]=p[0]+a[0]->delta+a[1]->delta+a[2]->delta;p[1]=p[0];p[2]=p[3];
    for(int c=0;c<3;c++){if(f[c]==top)p[1]+=a[c]->delta;if(f[c]==bottom)p[2]-=a[c]->delta;}
    const int w[]={16384-top,top-middle,middle-bottom,bottom};
    for(int c=0;c<3;c++){
        int sum=0;for(int j=0;j<4;j++)sum+=lut.valuesQ12()[p[j]+c]*w[j];
        int q=sum<=0?0:sum>=67108864?4096:(sum+8192)>>14;rgb[c]=(q*255+2048)>>12;
    }
}
int main(int argc,char** argv){try{
    for(int q=-65536;q<=65536;q++){
        unsigned biased=unsigned(q+65536),lo=biased&255,hi=(biased>>8)&255,upper=biased>>16;
        require(int(lo+256*hi)+(int(upper)-1)*65536==q,"packed vertex changed Q12");
        for(int c=0;c<3;c++)require(((upper<<(c*2))>>(c*2)&3)==upper,"packed alpha changed upper bits");
    }
    for(int n=2;n<=65;n++){
        int cols=1;while(cols*cols<n)cols++;
        const int width=n*cols,height=n*((n+cols-1)/cols);
        require(width<=4096&&height<=4096,"atlas exceeds device size");
        const float lowReciprocal=std::nextafter(1.0f/cols,0.0f);
        for(int b=0;b<n;b++)require(int(std::floor((b+0.5f)*lowReciprocal))==b/cols,
                                  "atlas row vulnerable to rounded-down reciprocal");
        std::vector<bool> visited(size_t(width)*height,false);
        for(int b=0;b<n;b++)for(int g=0;g<n;g++)for(int r=0;r<n;r++){
            size_t at=size_t(b/cols*n+g)*width+b%cols*n+r;
            require(at<visited.size()&&!visited[at],"atlas collision/out of bounds");visited[at]=true;
        }
    }
    const size_t count=1024*1024;
    for(int arg=1;arg<argc;arg++){
        sl::Lut lut;lut.load(argv[arg]);unsigned state=0x89cdefab;
        std::vector<uint8_t> src(count*3),expected;
        for(size_t i=0;i<count;i++){
            unsigned bits=rng(state);for(int c=0;c<3;c++)src[i*3+c]=uint8_t(bits>>(8*c));
            if(i<256)for(int c=0;c<3;c++)src[i*3+c]=uint8_t(i);
        }
        expected=src;lut.apply(expected.data(),count);
        for(size_t i=0;i<count;i++)branchless(lut,src.data()+i*3);
        require(src==expected,"branchless tetrahedral differs from production reference");
        printf("PASS %s: %zu RGB pixels, byte exact\n",argv[arg],count);
    }
    puts("PASS all signed Q12 values, alpha fields, 2..65 atlas layouts");return 0;
}catch(const std::exception& e){fprintf(stderr,"FAIL %s\n",e.what());return 1;}}
