#include "core.hpp"
#include <cstdio>
#include <fstream>
#include <iterator>
#include <stdexcept>
int main(int argc,char** argv) {
    try {
        if(argc!=5)throw std::runtime_error("preview-tests ID LUT SMALL.jpg BIG.jpg");
        sl::Lut id,lut;id.load(argv[1]);lut.load(argv[2]);sl::Pool one(1),four(4);
        std::ifstream f(argv[3],std::ios::binary);std::vector<uint8_t> jpg((std::istreambuf_iterator<char>(f)),{});
        std::vector<uint8_t> original,expected,actual;int w,h;sl::Stats a;
        sl::process_preview(jpg.data(),jpg.size(),id,one,w,h,a,original);
        expected=original;lut.apply(expected.data(),size_t(w)*h);
        for(int i=0;i<30;++i) {
            sl::Stats b;int x,y;sl::process_preview(jpg.data(),jpg.size(),lut,four,x,y,b,actual);
            if(x!=w || y!=h || actual!=expected)throw std::runtime_error("preview/file-kernel parity failed");
        }
        bool failed=false;try{sl::process_preview(jpg.data(),100,lut,one,w,h,a,actual);}catch(const std::exception&){failed=true;}
        if(!failed)throw std::runtime_error("truncated preview accepted");
        std::ifstream big(argv[4],std::ios::binary);jpg.assign(std::istreambuf_iterator<char>(big),{});
        failed=false;try{sl::process_preview(jpg.data(),jpg.size(),lut,one,w,h,a,actual);}catch(const std::exception&){failed=true;}
        if(!failed)throw std::runtime_error("oversized preview accepted");
        puts("PASS preview: exact same LUT pixels, reusable buffers x30, 1/4 workers, malformed/oversized frame rejected");
    }catch(const std::exception& e){fprintf(stderr,"FAIL %s\n",e.what());return 1;}
}
