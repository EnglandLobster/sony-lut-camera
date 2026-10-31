#include "core.hpp"
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <iterator>
#include <algorithm>
#include <cstdio>

static void check(bool yes,const char* why){if(!yes)throw std::runtime_error(why);}
static void same(const sl::Lut& a,const sl::Lut& b){
    check(a.size()==b.size()&&a.valuesQ12()==b.valuesQ12(),"vertex bits differ");
    for(int c=0;c<3;++c)for(int i=0;i<256;++i){const sl::Axis& x=a.axis(c,i);const sl::Axis& y=b.axis(c,i);
        check(x.base==y.base&&x.delta==y.delta&&x.fraction==y.fraction,"axis bits differ");}
}
static void write(const std::string& path,const std::vector<unsigned char>& data){std::ofstream f(path.c_str(),std::ios::binary);f.write(reinterpret_cast<const char*>(data.data()),data.size());}
static void checksum(std::vector<unsigned char>& b){
    uint32_t crc=0xffffffffU;for(size_t i=0;i<b.size()-4;++i){crc^=b[i];for(int bit=0;bit<8;++bit)crc=(crc>>1)^((crc&1)?0xedb88320U:0);}crc^=0xffffffffU;
    for(int j=0;j<4;++j)b[b.size()-4+j]=(crc>>(8*j))&255;
}
int main(int argc,char** argv){try{
    check(argc>2,"usage: cache-tests output-directory cubes...");std::string path=std::string(argv[1])+"/TABLE.BIN",bad=std::string(argv[1])+"/BAD.BIN",id(64,'a');
    for(int arg=2;arg<argc;++arg){sl::Lut source,compiled;double start=sl::milliseconds();source.load(argv[arg]);double parse=sl::milliseconds()-start;
        source.saveCompiled(path,id);start=sl::milliseconds();compiled.loadCompiled(path,id);double load=sl::milliseconds()-start;same(source,compiled);
        // Exhaust all 8-bit RGB inputs for the final (33^3 nonidentity) fixture.
        if(arg==argc-1){std::vector<uint8_t> a(65536*3),b;
            for(int r=0;r<256;++r){for(int x=0;x<65536;++x){a[x*3]=r;a[x*3+1]=x>>8;a[x*3+2]=x&255;}b=a;
                source.apply(a.data(),65536);compiled.apply(b.data(),65536);check(a==b,"RGB output differs");}
        }
        std::ifstream f(path.c_str(),std::ios::binary);std::vector<unsigned char> bytes((std::istreambuf_iterator<char>(f)),std::istreambuf_iterator<char>());
        for(int kind=0;kind<9;++kind){std::vector<unsigned char> damaged=bytes;std::string expected=id;
            if(kind==0)damaged.resize(10);
            if(kind==1)damaged.pop_back();
            if(kind==2)damaged.push_back(0);
            if(kind==3)damaged[0]^=1;
            if(kind==4)expected[0]='b';
            if(kind==5)damaged[damaged.size()-8]^=1;
            if(kind==6){damaged[8]=66;checksum(damaged);}
            if(kind==7){damaged[76]=255;damaged[77]=255;damaged[78]=255;damaged[79]=127;checksum(damaged);}
            if(kind==8){size_t p=76+9216;damaged[p]=255;damaged[p+1]=255;damaged[p+2]=255;damaged[p+3]=127;checksum(damaged);}
            write(bad,damaged);bool failed=false;try{compiled.loadCompiled(bad,expected);}catch(const std::exception&){failed=true;}
            check(failed,"invalid cache was accepted");same(source,compiled);
        }
        bool failed=false;try{source.saveCompiled(std::string(argv[1])+"/MISSING/TABLE.BIN",id);}catch(const std::exception&){failed=true;}check(failed,"failed write accepted");
        compiled.loadCompiled(path,id);same(source,compiled);
        std::cout<<"PASS "<<argv[arg]<<" grid="<<source.size()<<" text_ms="<<parse<<" binary_ms="<<load<<" bytes="<<bytes.size()<<" exact_nodes_axes=true corrupt_rejection=9\n";
    }
    std::remove(path.c_str());std::remove(bad.c_str());std::cout<<"PASS exhaustive 16777216 RGB outputs identical; failed loads preserve previous LUT\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
