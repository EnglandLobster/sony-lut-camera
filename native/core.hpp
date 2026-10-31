#pragma once
#include <cstddef>
#include <cstdint>
#include <pthread.h>
#include <string>
#include <vector>

namespace sl {
struct Axis { int base, delta, fraction; };
class Lut {
public:
    void load(const std::string& path);
    // Versioned exact serialization of the already parsed Q12/Q14 contract.
    // The caller verifies sourceId against the source file's SHA-256.
    void loadCompiled(const std::string& path,const std::string& sourceId);
    void saveCompiled(const std::string& path,const std::string& sourceId) const;
    void apply(uint8_t* rgb, size_t pixels) const;
    size_t bytes() const { return values_.size()*sizeof(int32_t)+sizeof(axes_); }
    int size() const { return n_; }
    // Read-only numeric contract for alternate backends; no LUT re-parsing or
    // reduced-precision vertex conversion. Q12 vertices, Q14 axis fractions.
    const std::vector<int32_t>& valuesQ12() const { return values_; }
    const Axis& axis(int channel, uint8_t input) const { return axes_[channel][input]; }
private:
    int n_=0;
    Axis axes_[3][256];
    std::vector<int32_t> values_;
};

// Persistent workers. Row batches serialize; immutable LUT data is shared.
class Pool {
public:
    explicit Pool(int workers);
    ~Pool();
    void run(const Lut& lut, uint8_t* rows, int width, int count, size_t stride);
    int workers() const { return count_; }
private:
    Pool(const Pool&);
    Pool& operator=(const Pool&);
    struct Worker { Pool* pool; int id; pthread_t thread; };
    static void* entry(void* arg);
    void range(int id);
    int count_, started_=0, remaining_=0;
    unsigned generation_=0;
    bool stopping_=false;
    pthread_mutex_t mutex_, runMutex_;
    pthread_cond_t wake_, done_;
    std::vector<Worker> workers_;
    const Lut* lut_=nullptr;
    uint8_t* rows_=nullptr;
    int width_=0, rowsCount_=0;
    size_t stride_=0;
};

double milliseconds();
struct Stats {
    int width=0,height=0,workers=1,scale=1;
    int syncError=0;bool pipeline=false;
    size_t rowBytes=0,lutBytes=0;
    double decode=0,lut=0,encode=0,close=0,total=0;
    double decodeCpu=0,encodeCpu=0,readBuffer=0,writeBuffer=0;
    std::string json() const;
};
// Output must not already exist. On failure the partial output is removed.
Stats process_file(const std::string& input,const std::string& output,
                   const Lut* lut,Pool& pool,int scale=1,int quality=92,int batch=64,bool pipeline=false);
// Low-resolution preview only: decode bounded JPEG, apply same LUT and return RGB.
void process_preview(const uint8_t* jpeg,size_t length,const Lut& lut,Pool& pool,
                    int& width,int& height,Stats& stats,std::vector<uint8_t>& rgb);
}
