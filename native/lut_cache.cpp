#include "core.hpp"
#include <algorithm>
#include <cstdio>
#include <fstream>
#include <stdexcept>
#include <cstring>
#include <cerrno>

namespace sl {
namespace {
const char magic[]="SLUTB001"; // Bump when parser numeric representation changes.
const size_t prefix=8+4+64,axisBytes=3*256*3*4;
void require(bool yes,const char* message){if(!yes)throw std::runtime_error(message);}
void checkId(const std::string& id){
    require(id.size()==64&&id.find_first_not_of("0123456789abcdef")==std::string::npos,"invalid compiled LUT source digest");
}
void put(std::vector<uint8_t>& b,uint32_t v){for(int i=0;i<4;++i)b.push_back(uint8_t(v>>(8*i)));}
uint32_t get(const std::vector<uint8_t>& b,size_t p){
    return uint32_t(b[p])|(uint32_t(b[p+1])<<8)|(uint32_t(b[p+2])<<16)|(uint32_t(b[p+3])<<24);
}
int32_t signedGet(const std::vector<uint8_t>& b,size_t p){uint32_t v=get(b,p);return v<=0x7fffffffU?int32_t(v):int32_t(int64_t(v)-4294967296LL);}
uint32_t crc(const std::vector<uint8_t>& b,size_t length){
    uint32_t table[256];for(unsigned i=0;i<256;++i){uint32_t v=i;for(int j=0;j<8;++j)v=(v>>1)^((v&1)?0xedb88320U:0);table[i]=v;}
    uint32_t v=0xffffffffU;for(size_t i=0;i<length;++i)v=table[(v^b[i])&255]^(v>>8);return v^0xffffffffU;
}
}
void Lut::saveCompiled(const std::string& path,const std::string& sourceId) const {
    checkId(sourceId);require(n_>=2&&n_<=65,"no valid LUT to compile");
    std::vector<uint8_t> b;b.reserve(prefix+axisBytes+values_.size()*4+4);
    b.insert(b.end(),magic,magic+8);put(b,uint32_t(n_));b.insert(b.end(),sourceId.begin(),sourceId.end());
    for(int c=0;c<3;++c)for(int x=0;x<256;++x){const Axis& a=axes_[c][x];put(b,a.base);put(b,a.delta);put(b,a.fraction);}
    for(int32_t v:values_)put(b,uint32_t(v));put(b,crc(b,b.size()));
    size_t slash=path.find_last_of('/'),dot=path.find_last_of('.');
    std::string tmp=(dot!=std::string::npos&&(slash==std::string::npos||dot>slash)?path.substr(0,dot):path)+".TMP";
    require(tmp!=path,"invalid compiled LUT temporary path");
    try{
        std::ofstream f(tmp.c_str(),std::ios::binary|std::ios::trunc);require(bool(f),"cannot create compiled LUT temporary file");
        f.write(reinterpret_cast<const char*>(b.data()),b.size());f.flush();require(bool(f),"cannot write compiled LUT");
        f.close();require(bool(f),"cannot close compiled LUT");
        // Sony FuFsys rejects rename-over-existing. Only remove the disposable
        // old cache after the new file is complete and closed. A power loss in
        // this small gap leaves a cache miss; the original CUBE is untouched.
        if(std::remove(path.c_str())!=0&&errno!=ENOENT)throw std::runtime_error("cannot replace compiled LUT");
        require(std::rename(tmp.c_str(),path.c_str())==0,"cannot publish compiled LUT");
    }catch(...){std::remove(tmp.c_str());throw;}
}
void Lut::loadCompiled(const std::string& path,const std::string& sourceId){
    checkId(sourceId);std::ifstream f(path.c_str(),std::ios::binary|std::ios::ate);
    require(bool(f),"compiled LUT missing");std::streamoff length=f.tellg();
    require(length>=std::streamoff(prefix+axisBytes+2*2*2*3*4+4)&&length<=std::streamoff(prefix+axisBytes+65*65*65*3*4+4),"invalid compiled LUT length");
    std::vector<uint8_t> b(static_cast<size_t>(length));f.seekg(0);f.read(reinterpret_cast<char*>(b.data()),b.size());require(bool(f),"truncated compiled LUT");
    require(std::memcmp(b.data(),magic,8)==0,"compiled LUT version mismatch");
    uint32_t n=get(b,8);require(n>=2&&n<=65,"invalid compiled LUT grid");
    size_t count=size_t(n)*n*n*3;
    require(b.size()==prefix+axisBytes+count*4+4,"compiled LUT grid length mismatch");
    require(std::memcmp(b.data()+12,sourceId.data(),64)==0,"compiled LUT source mismatch");
    require(crc(b,b.size()-4)==get(b,b.size()-4),"compiled LUT checksum mismatch");
    Axis axes[3][256];size_t p=prefix;int stride=3;
    for(int c=0;c<3;++c){for(int x=0;x<256;++x){
        Axis a={signedGet(b,p),signedGet(b,p+4),signedGet(b,p+8)};p+=12;
        require(a.base>=0&&a.base<=int(n-1)*stride&&a.base%stride==0,"invalid compiled LUT axis base");
        bool last=a.base==int(n-1)*stride;
        require(a.delta==(last?0:stride)&&a.fraction>=0&&a.fraction<16384&&(!last||a.fraction==0),"invalid compiled LUT axis interval");
        axes[c][x]=a;
    }stride*=n;}
    std::vector<int32_t> values(count);for(size_t i=0;i<count;++i,p+=4){
        values[i]=signedGet(b,p);require(values[i]>=-65536&&values[i]<=65536,"invalid compiled LUT vertex");
    }
    // A failed/corrupt cache never replaces a valid in-memory LUT.
    values_.swap(values);n_=int(n);for(int c=0;c<3;++c)std::copy(axes[c],axes[c]+256,axes_[c]);
}
}
