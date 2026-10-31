// CPU-only, bounded-memory benchmark: 128 rows at a time (<2 MiB of pixels).
// JPEG decoding/checksums are outside the LUT timer. Never writes a JPEG,
// changes the input, opens CameraEx or fires a shutter. The real image path
// uses Sony device memory; allocating another 24MP heap frame here is unsuitable.
#include "uyvy.hpp"
#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>
#include <vector>
#include <jpeglib.h>

static uint32_t checksum(uint32_t h,const uint8_t* data,size_t bytes){
    for(size_t i=0;i<bytes;i++)h=(h^data[i])*16777619U;return h;
}
int main(int argc,char** argv){try{
    if(argc!=5)throw std::runtime_error("usage: uyvy-bench LUT JPEG WORKERS RUNS");
    int workers=atoi(argv[3]),runs=atoi(argv[4]);
    if(workers<1||workers>4||runs<1||runs>4)throw std::runtime_error("invalid benchmark limits");
    const int width=6000,height=4000,band=128,stride=6080*2,pw=750;
    sl::Lut lut;lut.load(argv[1]);sl::YuvLutTable table;
    std::vector<uint8_t> pixels(size_t(stride)*band,0),line(width*3),review(pw*(band/8)*4);
    for(int i=0;i<runs;i++){
        FILE* f=fopen(argv[2],"rb");if(!f)throw std::runtime_error("cannot open benchmark JPEG");
        jpeg_decompress_struct d={};jpeg_error_mgr error;d.err=jpeg_std_error(&error);
        jpeg_create_decompress(&d);jpeg_stdio_src(&d,f);jpeg_read_header(&d,TRUE);
        if(d.image_width!=width||d.image_height!=height||d.num_components!=3||d.progressive_mode)
            throw std::runtime_error("benchmark requires baseline 6000x4000 colour JPEG");
        d.out_color_space=JCS_YCbCr;jpeg_start_decompress(&d);
        uint32_t imageHash=2166136261U,reviewHash=2166136261U;double prepared=0,applied=0;
        for(int y=0;y<height;y+=band){int rows=std::min(band,height-y);
            for(int row=0;row<rows;row++){
                JSAMPROW p=line.data();jpeg_read_scanlines(&d,&p,1);uint8_t* out=pixels.data()+size_t(row)*stride;
                for(int x=0;x<width;x+=2){const uint8_t* c=p+x*3;
                    out[x*2]=(unsigned(c[1])+c[4]+1)/2;out[x*2+1]=c[0];
                    out[x*2+2]=(unsigned(c[2])+c[5]+1)/2;out[x*2+3]=c[3];
                }
            }
            if(!y){double t=sl::milliseconds();table.prepare(lut,pixels.data(),width,rows,stride,workers);prepared=sl::milliseconds()-t;}
            sl::PreviewTarget target{review.data(),pw,rows/8,pw*4};double t=sl::milliseconds();
            sl::apply_uyvy(lut,pixels.data(),width,rows,stride,workers,true,&table,&target);applied+=sl::milliseconds()-t;
            imageHash=checksum(imageHash,pixels.data(),size_t(stride)*rows);
            reviewHash=checksum(reviewHash,review.data(),size_t(pw)*(rows/8)*4);
        }
        jpeg_finish_decompress(&d);jpeg_destroy_decompress(&d);fclose(f);
        printf("{\"run\":%d,\"workers\":%d,\"band_rows\":%d,\"pixel_buffer_bytes\":%zu,\"prepare_ms\":%.3f,\"apply_ms\":%.3f,\"total_ms\":%.3f,\"resident_bytes\":%zu,\"image_checksum\":\"%08x\",\"review_checksum\":\"%08x\"}\n",
            i,workers,band,pixels.size()+line.size()+review.size(),prepared,applied,prepared+applied,table.residentBytes(),imageHash,reviewHash);fflush(stdout);
    }
    return 0;
}catch(const std::exception& e){fprintf(stderr,"%s\n",e.what());return 1;}}
