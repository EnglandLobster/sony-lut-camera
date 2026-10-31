#include "core.hpp"
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <vector>
static void require(bool value,const char* why) {if(!value) throw std::runtime_error(why);}
struct FloatLut {
    int n=0;double lo[3]={0,0,0},hi[3]={1,1,1};std::vector<double> v;
    explicit FloatLut(const char* file) {
        std::ifstream f(file);std::string line,key;
        while(std::getline(f,line)) {
            std::istringstream s(line);s>>key;
            if(key=="LUT_3D_SIZE") s>>n;
            else if(key=="DOMAIN_MIN") s>>lo[0]>>lo[1]>>lo[2];
            else if(key=="DOMAIN_MAX") s>>hi[0]>>hi[1]>>hi[2];
            else {double r,g,b;std::istringstream t(line);if(t>>r>>g>>b) {v.push_back(r);v.push_back(g);v.push_back(b);}}
        }
        require(n>1 && v.size()==size_t(n)*n*n*3,"reference cube invalid");
    }
    void apply(const uint8_t* rgb,uint8_t* out) const {
        int cell[3],axis[3]={0,1,2};double part[3];
        for(int c=0;c<3;++c) {
            double x=std::max(0.0,std::min(1.0,(rgb[c]/255.0-lo[c])/(hi[c]-lo[c])))*(n-1);
            cell[c]=std::min(n-2,int(std::floor(x)));part[c]=x-cell[c];
        }
        std::sort(axis,axis+3,[&](int a,int b){return part[a]>part[b];});
        double weight[4]={1-part[axis[0]],part[axis[0]]-part[axis[1]],part[axis[1]]-part[axis[2]],part[axis[2]]};
        double value[3]={0,0,0};
        for(int k=0;k<4;++k) {
            if(k) ++cell[axis[k-1]];
            size_t p=size_t((cell[2]*n+cell[1])*n+cell[0])*3;
            for(int c=0;c<3;++c) value[c]+=weight[k]*v[p+c];
        }
        for(int c=0;c<3;++c) out[c]=uint8_t(std::lround(std::max(0.0,std::min(1.0,value[c]))*255));
    }
};
int main(int argc,char** argv) {
    try {
        require(argc>=4,"usage tests IDENTITY.cube BAD.cube COLOR.cube ...");
        sl::Lut identity;identity.load(argv[1]);
        const int width=4096,rows=64;
        std::vector<uint8_t> all(size_t(width)*rows*3); // bounded strips, exhaustive RGB domain
        for(unsigned begin=0;begin<(1u<<24);begin+=width*rows) {
            for(unsigned i=0;i<width*rows;++i) for(int c=0;c<3;++c) all[size_t(i)*3+c]=uint8_t((begin+i)>>(c*8));
            identity.apply(all.data(),size_t(width)*rows);
            for(unsigned i=0;i<width*rows;++i) for(int c=0;c<3;++c)
                require(all[size_t(i)*3+c]==uint8_t((begin+i)>>(c*8)),"identity changed a channel");
        }
        bool rejected=false;try {identity.load(argv[2]);} catch(const std::exception&) {rejected=true;}
        require(rejected,"invalid LUT accepted");uint8_t black[3]={0,0,0};identity.apply(black,1);
        require(!black[0]&&!black[1]&&!black[2],"load failure changed LUT / black floor introduced");
        unsigned rng=17;int worst=0;
        for(int a=3;a<argc;++a) {
            sl::Lut lut;lut.load(argv[a]);FloatLut ref(argv[a]);
            for(int i=0;i<100000;++i) {
                uint8_t rgb[3],expected[3];for(int c=0;c<3;++c) {rng=rng*1664525u+1013904223u;rgb[c]=uint8_t(rng>>24);}
                ref.apply(rgb,expected);lut.apply(rgb,1);
                for(int c=0;c<3;++c) {int e=std::abs(int(rgb[c])-expected[c]);worst=std::max(worst,e);require(e<=1,"fixed point exceeds 1 code vs independent float");}
            }
            const int w=319,h=133;const size_t stride=w*3+7;
            std::vector<uint8_t> input(stride*h),serial,parallel;
            for(size_t i=0;i<input.size();++i) {rng=rng*1664525u+1013904223u;input[i]=uint8_t(rng>>24);}
            sl::Pool one(1),four(4);
            for(int repeat=0;repeat<24;++repeat) {
                serial=input;parallel=input;one.run(lut,serial.data(),w,h,stride);four.run(lut,parallel.data(),w,h,stride);
                require(serial==parallel,"thread output differs");
                for(int y=0;y<h;++y) for(size_t p=w*3;p<stride;++p)
                    require(parallel[y*stride+p]==input[y*stride+p],"row padding overwritten");
            }
        }
        printf("PASS exhaustive_identity=16777216 float_reference_luts=%d max_error=%d parallel_reuse=24 stride_padding=ok failed_load=preserved\n",argc-3,worst);
    } catch(const std::exception& e) {fprintf(stderr,"FAIL %s\n",e.what());return 1;}
}
