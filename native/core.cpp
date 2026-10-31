#include "core.hpp"
#include <algorithm>
#include <cmath>
#include <fstream>
#include <sstream>
#include <stdexcept>
#include <time.h>

namespace sl {
void Lut::load(const std::string& path) {
    std::ifstream f(path.c_str());
    if(!f) throw std::runtime_error("cannot open cube: "+path);
    int n=0; double lo[3]={0,0,0},hi[3]={1,1,1};
    bool haveLo=false,haveHi=false;
    std::vector<int32_t> values;
    std::string line;
    size_t lineNo=0,totalBytes=0;
    while(std::getline(f,line)) {
        ++lineNo;
        totalBytes+=line.size()+1;
        if(totalBytes>64*1024*1024) throw std::runtime_error("cube exceeds 64 MiB");
        if(lineNo==1 && line.compare(0,3,"\xef\xbb\xbf")==0) line.erase(0,3);
        if(line.size()>16384) throw std::runtime_error("cube line too long");
        size_t hash=line.find('#'); if(hash!=std::string::npos) line.resize(hash);
        std::istringstream s(line); std::string key; if(!(s>>key)) continue;
        if(key=="TITLE") continue;
        if(key=="LUT_1D_SIZE") throw std::runtime_error("1D / combined LUT unsupported");
        if(key=="LUT_3D_SIZE") {
            std::string extra;
            if(n || !(s>>n) || n<2 || n>65 || (s>>extra)) throw std::runtime_error("cube size must be 2..65, once");
            continue;
        }
        if(key=="DOMAIN_MIN" || key=="DOMAIN_MAX") {
            bool& seen=key=="DOMAIN_MIN"?haveLo:haveHi;
            double* a=key=="DOMAIN_MIN"?lo:hi;
            std::string extra;
            if(seen || !(s>>a[0]>>a[1]>>a[2]) || (s>>extra)) throw std::runtime_error("invalid cube domain");
            for(int c=0;c<3;++c) if(!std::isfinite(a[c])) throw std::runtime_error("non-finite domain");
            seen=true; continue;
        }
        double v[3]; std::string extra; std::istringstream data(line);
        if(!n || !(data>>v[0]>>v[1]>>v[2]) || (data>>extra)) throw std::runtime_error("invalid cube data row");
        for(int c=0;c<3;++c) {
            if(!std::isfinite(v[c]) || v[c]<-16 || v[c]>16) throw std::runtime_error("cube output outside supported [-16,16]");
            values.push_back(int32_t(::llround(v[c]*4096)));
        }
        if(values.size()>size_t(n)*n*n*3) throw std::runtime_error("too many cube rows");
    }
    if(!f.eof() || !n || values.size()!=size_t(n)*n*n*3) throw std::runtime_error("truncated / invalid cube");
    Axis axes[3][256];
    int stride=3;
    for(int c=0;c<3;++c) {
        if(!(hi[c]>lo[c]) || !std::isfinite(hi[c]-lo[c])) throw std::runtime_error("cube domain must have a finite positive range");
        for(int v=0;v<256;++v) {
            double pos=std::max(0.0,std::min(1.0,(v/255.0-lo[c])/(hi[c]-lo[c])))*(n-1);
            int fixed=int(::llround(pos*16384));
            int cell=fixed>>14;
            axes[c][v]=Axis{cell*stride,cell<n-1?stride:0,fixed&16383};
        }
        stride*=n;
    }
    // Failed loads leave the last valid LUT intact.
    values_.swap(values); n_=n;
    for(int c=0;c<3;++c) std::copy(axes[c],axes[c]+256,axes_[c]);
}

void Lut::apply(uint8_t* rgb,size_t pixels) const {
    if(!n_) throw std::runtime_error("no LUT loaded");
    for(size_t x=0;x<pixels;++x,rgb+=3) {
        const Axis& r=axes_[0][rgb[0]];const Axis& g=axes_[1][rgb[1]];const Axis& b=axes_[2][rgb[2]];
        const int base=r.base+g.base+b.base;
        const int dx=r.fraction,dy=g.fraction,dz=b.fraction;
        int p1,p2,w0,w1,w2,w3;
        if(dx>=dy) {
            if(dy>=dz) {p1=base+r.delta;p2=p1+g.delta;w0=16384-dx;w1=dx-dy;w2=dy-dz;w3=dz;}
            else if(dx>=dz) {p1=base+r.delta;p2=p1+b.delta;w0=16384-dx;w1=dx-dz;w2=dz-dy;w3=dy;}
            else {p1=base+b.delta;p2=p1+r.delta;w0=16384-dz;w1=dz-dx;w2=dx-dy;w3=dy;}
        } else {
            if(dz>=dy) {p1=base+b.delta;p2=p1+g.delta;w0=16384-dz;w1=dz-dy;w2=dy-dx;w3=dx;}
            else if(dz>=dx) {p1=base+g.delta;p2=p1+b.delta;w0=16384-dy;w1=dy-dz;w2=dz-dx;w3=dx;}
            else {p1=base+g.delta;p2=p1+r.delta;w0=16384-dy;w1=dy-dx;w2=dx-dz;w3=dz;}
        }
        const int p3=base+r.delta+g.delta+b.delta;
        for(int c=0;c<3;++c) {
            // Non-negative weights sum to 16384; [-16,16] Q12 vertices fit int32.
            int sum=values_[base+c]*w0+values_[p1+c]*w1+values_[p2+c]*w2+values_[p3+c]*w3;
            int q=sum<=0?0:sum>=67108864?4096:(sum+8192)>>14;
            rgb[c]=uint8_t((q*255+2048)>>12);
        }
    }
}

Pool::Pool(int count):count_(count) {
    if(count<1 || count>4) throw std::runtime_error("workers must be 1..4");
    pthread_mutex_init(&mutex_,nullptr);pthread_mutex_init(&runMutex_,nullptr);
    pthread_cond_init(&wake_,nullptr);pthread_cond_init(&done_,nullptr);
    workers_.resize(count-1);
    for(int i=0;i<count-1;++i) {
        workers_[i].pool=this; workers_[i].id=i+1;
        if(pthread_create(&workers_[i].thread,nullptr,entry,&workers_[i])) {
            pthread_mutex_lock(&mutex_);stopping_=true;pthread_cond_broadcast(&wake_);pthread_mutex_unlock(&mutex_);
            for(int j=0;j<started_;++j) pthread_join(workers_[j].thread,nullptr);
            pthread_cond_destroy(&wake_);pthread_cond_destroy(&done_);
            pthread_mutex_destroy(&mutex_);pthread_mutex_destroy(&runMutex_);
            throw std::runtime_error("cannot create worker");
        }
        ++started_;
    }
}
Pool::~Pool() {
    pthread_mutex_lock(&mutex_);stopping_=true;pthread_cond_broadcast(&wake_);pthread_mutex_unlock(&mutex_);
    for(int i=0;i<started_;++i) pthread_join(workers_[i].thread,nullptr);
    pthread_cond_destroy(&wake_);pthread_cond_destroy(&done_);
    pthread_mutex_destroy(&mutex_);pthread_mutex_destroy(&runMutex_);
}
void Pool::range(int id) {
    for(int y=rowsCount_*id/count_;y<rowsCount_*(id+1)/count_;++y)
        lut_->apply(rows_+size_t(y)*stride_,width_);
}
void* Pool::entry(void* arg) {
    Worker* worker=static_cast<Worker*>(arg);Pool& p=*worker->pool;unsigned seen=0;
    pthread_mutex_lock(&p.mutex_);
    while(true) {
        while(!p.stopping_ && p.generation_==seen) pthread_cond_wait(&p.wake_,&p.mutex_);
        if(p.stopping_) break;
        seen=p.generation_;pthread_mutex_unlock(&p.mutex_);
        p.range(worker->id);
        pthread_mutex_lock(&p.mutex_);
        if(--p.remaining_==0) pthread_cond_signal(&p.done_);
    }
    pthread_mutex_unlock(&p.mutex_);return nullptr;
}
void Pool::run(const Lut& lut,uint8_t* rows,int width,int count,size_t stride) {
    if(lut.size()==0 || !rows || width<1 || count<0 || stride<size_t(width)*3) throw std::runtime_error("invalid row batch");
    pthread_mutex_lock(&runMutex_);
    if(count_<2 || count<count_*4) {
        for(int y=0;y<count;++y) lut.apply(rows+size_t(y)*stride,width);
    } else {
        pthread_mutex_lock(&mutex_);
        lut_=&lut;rows_=rows;width_=width;rowsCount_=count;stride_=stride;remaining_=started_;
        ++generation_;pthread_cond_broadcast(&wake_);pthread_mutex_unlock(&mutex_);
        range(0);
        pthread_mutex_lock(&mutex_);
        while(remaining_) pthread_cond_wait(&done_,&mutex_);
        pthread_mutex_unlock(&mutex_);
    }
    pthread_mutex_unlock(&runMutex_);
}
double milliseconds() {
    struct timespec t;clock_gettime(CLOCK_MONOTONIC,&t);
    return t.tv_sec*1000.0+t.tv_nsec/1000000.0;
}
std::string Stats::json() const {
    std::ostringstream s;s.precision(8);
    s<<"{\"width\":"<<width<<",\"height\":"<<height<<",\"workers\":"<<workers<<",\"scale\":"<<scale
     <<",\"pipeline\":"<<(pipeline?"true":"false")<<",\"decode_cpu_ms\":"<<decodeCpu<<",\"encode_cpu_ms\":"<<encodeCpu
     <<",\"jpeg_read_buffer_ms\":"<<readBuffer<<",\"jpeg_write_buffer_ms\":"<<writeBuffer
     <<",\"row_buffer_bytes\":"<<rowBytes<<",\"lut_bytes\":"<<lutBytes<<",\"fsync_errno\":"<<syncError<<",\"decode_ms\":"<<decode
     <<",\"lut_ms\":"<<lut<<",\"encode_ms\":"<<encode<<",\"close_ms\":"<<close<<",\"total_ms\":"<<total<<"}";
    return s.str();
}
}
