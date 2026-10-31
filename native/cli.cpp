#include "core.hpp"
#include <cstdio>
#include <cstdlib>
#include <exception>
int main(int argc,char** argv) {
    if(argc<4 || argc>9) {
        fprintf(stderr,"usage: sony-lut LUT.cube|--codec INPUT.jpg NEW_OUTPUT.jpg [workers=1 scale=1 quality=92 batch=64 pipeline=0]\n");return 2;
    }
    try {
        sl::Lut lut;bool apply=std::string(argv[1])!="--codec";if(apply) lut.load(argv[1]);
        sl::Pool pool(argc>4?atoi(argv[4]):1);
        sl::Stats s=sl::process_file(argv[2],argv[3],apply?&lut:nullptr,pool,
            argc>5?atoi(argv[5]):1,argc>6?atoi(argv[6]):92,argc>7?atoi(argv[7]):64,argc>8?atoi(argv[8])!=0:false);
        puts(s.json().c_str());return 0;
    } catch(const std::exception& e) {fprintf(stderr,"ERROR: %s\n",e.what());return 1;}
}
