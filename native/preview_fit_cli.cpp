#include "preview_fit.hpp"
#include <iostream>
#include <fstream>
int main(int argc,char** argv){try{
    if(argc!=4)throw std::runtime_error("usage: preview-fit input.cube sha256 output.PRV");
    sl::Lut lut;lut.load(argv[1]);auto f=sl::fit_preview(lut);
    std::ofstream out(argv[3]);out<<f.profile(argv[2]);out.close();if(!out)throw std::runtime_error("profile write failed");
    std::cout<<"{\"grid\":"<<lut.size()<<",\"mae\":"<<f.mae<<",\"p95\":"<<f.p95<<",\"identity_mae\":"<<f.identityMae<<",\"fit_ms\":"<<f.elapsed<<"}\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
