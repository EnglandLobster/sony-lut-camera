// Probe bridge to the device library inspected on A6300 firmware.
// The Java OptimizedImage retains ownership throughout every call.
#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstdlib>
#include <sstream>
#include <stdexcept>
#include <vector>
#include <cstdio>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <memory>
#include "uyvy.hpp"
namespace {
void* library(){static void* lib=dlopen("libcameraseq.so",RTLD_NOW);if(!lib)throw std::runtime_error("libcameraseq unavailable");return lib;}
template<class T>T symbol(const char* name){void* p=dlsym(library(),name);if(!p)throw std::runtime_error("required device-memory symbol unavailable");return reinterpret_cast<T>(p);}
void* memory(JNIEnv* e,jobject image){
    jclass c=e->FindClass("com/sony/scalar/graphics/OptimizedImage");
    if(!c||!image||!e->IsInstanceOf(image,c))throw std::runtime_error("expected live OptimizedImage");
    jfieldID f=e->GetFieldID(c,"mNativeMemory","I");if(!f)throw std::runtime_error("native memory field missing");
    uintptr_t p=static_cast<uint32_t>(e->GetIntField(image,f));if(!p||p%4)throw std::runtime_error("invalid native memory handle");return reinterpret_cast<void*>(p);
}
const char* property(void* m,const char* key){return symbol<const char*(*)(void*,const char*)>("_ZN6scalar18DeviceMemoryDiadem11getPropertyEPKc")(m,key);}
unsigned number(void* m,const char* key){const char* p=property(m,key);if(!p)throw std::runtime_error("missing device property");char* end=nullptr;unsigned long n=strtoul(p,&end,0);if(!end||*end||n>0xffffffffUL)throw std::runtime_error("invalid device property");return unsigned(n);}
class Mapping {
    void* util;void* base=nullptr;unsigned mapped=0;int fd=-1;
    typedef int(*Unmap)(void*,void*,unsigned,int);Unmap unmap;
public:
    void* data=nullptr;unsigned size=0;
    explicit Mapping(void* m){
        unsigned address=0;
        if(!symbol<int(*)(void*,unsigned*,unsigned*)>("_ZN6scalar18DeviceMemoryDiadem21getPhysicalMemoryInfoERjS1_")(m,&address,&size)||!size||size>128U*1024*1024)throw std::runtime_error("invalid image memory span");
        util=symbol<void*(*)()>("_ZN6scalar13CameraSeqUtil11getInstanceEv")();
        unmap=symbol<Unmap>("_ZN6scalar13CameraSeqUtil17unmapSharedMemoryEPvji");
        if(!symbol<int(*)(void*,unsigned,unsigned,void**,void**,unsigned*,int*)>("_ZN6scalar13CameraSeqUtil15mapSharedMemoryEjjPPvS2_PjPi")(util,address,size,&data,&base,&mapped,&fd))throw std::runtime_error("Sony image mapping failed");
    }
    ~Mapping(){if(base)unmap(util,base,mapped,fd);}
};
void fail(JNIEnv* e,const char* msg){if(!e->ExceptionCheck())e->ThrowNew(e->FindClass("java/lang/IllegalStateException"),msg);}
struct CachedLut {sl::Lut lut;sl::YuvLutTable table;int workers;std::string loadInfo;};
// Cache failures use the authoritative text parser and are explicitly recorded.
CachedLut* prepareLut(const std::string& path,const std::string& cache,const std::string& id,int workers){
    if(workers<1||workers>4)throw std::runtime_error("invalid cache worker count");
    double start=sl::milliseconds(),readMs=0,parseMs=0,writeMs=0,at;
    bool hit=false;const char* state=cache.empty()?"disabled":"rebuilt";
    std::unique_ptr<CachedLut> c(new CachedLut);c->workers=workers;
    if(!cache.empty()){
        at=sl::milliseconds();try{c->lut.loadCompiled(cache,id);hit=true;state="hit";}
        catch(const std::exception& x){__android_log_print(ANDROID_LOG_INFO,"SonyLutLoad","cache_rebuild: %s",x.what());}
        readMs=sl::milliseconds()-at;
    }
    if(!hit){
        at=sl::milliseconds();c->lut.load(path);parseMs=sl::milliseconds()-at;
        if(!cache.empty()){
            at=sl::milliseconds();try{c->lut.saveCompiled(cache,id);}
            catch(const std::exception& x){state="write_failed";__android_log_print(ANDROID_LOG_WARN,"SonyLutLoad","cache_write_failed: %s",x.what());}
            writeMs=sl::milliseconds()-at;
        }
    }
    at=sl::milliseconds();c->table.init();double tableMs=sl::milliseconds()-at;
    std::ostringstream info;info<<"{\"cache\":\""<<state<<"\",\"lut_size\":"<<c->lut.size()
        <<",\"binary_read_ms\":"<<readMs<<",\"text_parse_ms\":"<<parseMs<<",\"binary_write_ms\":"<<writeMs
        <<",\"table_init_ms\":"<<tableMs<<",\"native_prepare_ms\":"<<(sl::milliseconds()-start)<<"}";
    c->loadInfo=info.str();return c.release();
}
std::string applyImage(JNIEnv* e,jobject image,const sl::Lut& lut,sl::YuvLutTable* table,int workers,const sl::PreviewTarget* preview=nullptr){
    double callStart=sl::milliseconds();
    void* m=memory(e,image);unsigned cw=number(m,"image-canvas-width"),ch=number(m,"image-canvas-height"),w=number(m,"image-target-width"),h=number(m,"image-target-height"),x=number(m,"image-target-x-offset"),y=number(m,"image-target-y-offset"),off=number(m,"image-data-offset");
    if(w<2||w%2||!h||cw>10000||ch>10000||x%2||uint64_t(x)+w>cw||uint64_t(y)+h>ch)throw std::runtime_error("unsupported UYVY geometry");
    double elapsed,prepare=0,properties=sl::milliseconds()-callStart,mapTime=0,unmapStart=0;
    size_t entriesBefore=table?table->populatedEntries():0,entriesPrepared=0;
    {Mapping map(m);size_t stride=size_t(cw)*2,first=off+size_t(y)*stride+size_t(x)*2;
        mapTime=sl::milliseconds()-callStart-properties;
        if(uint64_t(off)+uint64_t(cw)*ch*2>map.size||first+size_t(h-1)*stride+size_t(w)*2>map.size)throw std::runtime_error("UYVY geometry exceeds memory span");
        double start=sl::milliseconds();if(table)table->prepare(lut,static_cast<uint8_t*>(map.data)+first,w,h,stride,workers);prepare=sl::milliseconds()-start;
        entriesPrepared=table?table->populatedEntries():0;
        sl::apply_uyvy(lut,static_cast<uint8_t*>(map.data)+first,w,h,stride,workers,true,table,preview);elapsed=sl::milliseconds()-start;unmapStart=sl::milliseconds();
    }
    double unmapTime=sl::milliseconds()-unmapStart;
    std::ostringstream out;out<<"{\"lut_size\":"<<lut.size()<<",\"lut_ms\":"<<elapsed<<",\"properties_ms\":"<<properties<<",\"map_ms\":"<<mapTime<<",\"unmap_ms\":"<<unmapTime<<",\"table_prepare_ms\":"<<prepare<<",\"table_resident_bytes\":"<<(table?table->residentBytes():0)<<",\"cache_page_bytes\":"<<(table?table->pageBytes():0)<<",\"cache_evicted_pages\":"<<(table?table->evictedPages():0)<<",\"cache_sampled_pairs\":"<<(table?table->sampledPairs():0)<<",\"table_entries_before\":"<<entriesBefore<<",\"table_entries_reused\":"<<entriesPrepared<<",\"table_entries\":"<<(table?table->populatedEntries():0)<<",\"workers\":"<<workers<<",\"width\":"<<w<<",\"height\":"<<h<<"}";return out.str();
}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_hongen_sonylutprobe_Native_deviceLut(JNIEnv* e,jclass,jobject image,jstring cube,jint workers){
    try{
        const char* p=e->GetStringUTFChars(cube,nullptr);if(!p)return nullptr;std::string path(p);e->ReleaseStringUTFChars(cube,p);
        sl::Lut lut;lut.load(path);return e->NewStringUTF(applyImage(e,image,lut,nullptr,workers).c_str());
    }catch(const std::exception& x){fail(e,x.what());return nullptr;}
}
extern "C" JNIEXPORT jlong JNICALL Java_org_hongen_sonylutprobe_Native_prepareDeviceLut(JNIEnv* e,jclass,jstring cube,jint workers){
    try{const char* p=e->GetStringUTFChars(cube,nullptr);if(!p)return 0;std::string path(p);e->ReleaseStringUTFChars(cube,p);
        return reinterpret_cast<jlong>(prepareLut(path,"","",workers));
    }catch(const std::exception& x){fail(e,x.what());return 0;}
}
extern "C" JNIEXPORT jlong JNICALL Java_org_hongen_sonylutprobe_Native_prepareDeviceLutCompiled(JNIEnv* e,jclass,jstring cube,jstring bin,jstring digest,jint workers){
    try{
        const char* p=e->GetStringUTFChars(cube,nullptr);if(!p)return 0;std::string path(p);e->ReleaseStringUTFChars(cube,p);
        p=e->GetStringUTFChars(bin,nullptr);if(!p)return 0;std::string cache(p);e->ReleaseStringUTFChars(bin,p);
        p=e->GetStringUTFChars(digest,nullptr);if(!p)return 0;std::string id(p);e->ReleaseStringUTFChars(digest,p);
        return reinterpret_cast<jlong>(prepareLut(path,cache,id,workers));
    }catch(const std::exception& x){fail(e,x.what());return 0;}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_hongen_sonylutprobe_Native_deviceLutLoadInfo(JNIEnv* e,jclass,jlong cache){
    if(!cache){fail(e,"missing prepared LUT");return nullptr;}
    return e->NewStringUTF(reinterpret_cast<CachedLut*>(cache)->loadInfo.c_str());
}
extern "C" JNIEXPORT void JNICALL Java_org_hongen_sonylutprobe_Native_releaseDeviceLut(JNIEnv*,jclass,jlong cache){delete reinterpret_cast<CachedLut*>(cache);}
extern "C" JNIEXPORT jstring JNICALL Java_org_hongen_sonylutprobe_Native_deviceLutCached(JNIEnv* e,jclass,jobject image,jlong cache){
    try{if(!cache)throw std::runtime_error("missing prepared LUT");CachedLut& c=*reinterpret_cast<CachedLut*>(cache);return e->NewStringUTF(applyImage(e,image,c.lut,&c.table,c.workers).c_str());}
    catch(const std::exception& x){fail(e,x.what());return nullptr;}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_hongen_sonylutprobe_Native_deviceLutReview(JNIEnv* e,jclass,jobject image,jlong cache,jobject bitmap){
    void* pixels=nullptr;
    try {
        if(!cache)throw std::runtime_error("missing prepared LUT");CachedLut& c=*reinterpret_cast<CachedLut*>(cache);
        AndroidBitmapInfo info;
        if(AndroidBitmap_getInfo(e,bitmap,&info)||info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888||info.width>1024||info.height>1024)throw std::runtime_error("invalid fused review bitmap");
        if(AndroidBitmap_lockPixels(e,bitmap,&pixels))throw std::runtime_error("cannot lock fused review bitmap");
        sl::PreviewTarget target{static_cast<uint8_t*>(pixels),int(info.width),int(info.height),info.stride};
        std::string result=applyImage(e,image,c.lut,&c.table,c.workers,&target);
        AndroidBitmap_unlockPixels(e,bitmap);pixels=nullptr;return e->NewStringUTF(result.c_str());
    }catch(const std::exception& x){if(pixels)AndroidBitmap_unlockPixels(e,bitmap);fail(e,x.what());return nullptr;}
}
extern "C" JNIEXPORT void JNICALL Java_org_hongen_sonylutprobe_Native_devicePreview(JNIEnv* e,jclass,jobject image,jobject bitmap){
    void* pixels=nullptr;
    try {
        void* m=memory(e,image);unsigned cw=number(m,"image-canvas-width"),ch=number(m,"image-canvas-height"),w=number(m,"image-target-width"),h=number(m,"image-target-height"),x=number(m,"image-target-x-offset"),y=number(m,"image-target-y-offset"),off=number(m,"image-data-offset");
        if(w<2||w%2||!h||cw>10000||ch>10000||x%2||uint64_t(x)+w>cw||uint64_t(y)+h>ch)throw std::runtime_error("unsupported preview geometry");
        Mapping map(m);size_t stride=size_t(cw)*2,first=off+size_t(y)*stride+size_t(x)*2;
        if(uint64_t(off)+uint64_t(cw)*ch*2>map.size||first+size_t(h-1)*stride+size_t(w)*2>map.size)throw std::runtime_error("preview exceeds image memory span");
        AndroidBitmapInfo info;
        if(AndroidBitmap_getInfo(e,bitmap,&info)||info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888||info.width>1024||info.height>1024)throw std::runtime_error("invalid review bitmap");
        if(AndroidBitmap_lockPixels(e,bitmap,&pixels))throw std::runtime_error("cannot lock review bitmap");
        sl::preview_uyvy(static_cast<const uint8_t*>(map.data)+first,w,h,stride,static_cast<uint8_t*>(pixels),info.width,info.height,info.stride);
        AndroidBitmap_unlockPixels(e,bitmap);pixels=nullptr;
    }catch(const std::exception& x){if(pixels)AndroidBitmap_unlockPixels(e,bitmap);fail(e,x.what());}
}
extern "C" JNIEXPORT jlong JNICALL Java_org_hongen_sonylutprobe_Native_saveSonyStream(JNIEnv* e,jclass,jobject stream,jstring path){
    FILE* file=nullptr;std::string output;bool owned=false;
    try{
        jclass cls=e->FindClass("com/sony/scalar/io/MemoryInputStream");if(!cls||!stream||!e->IsInstanceOf(stream,cls))throw std::runtime_error("expected Sony JPEG stream");
        jfieldID field=e->GetFieldID(cls,"mNativeInputStream","I");if(!field)throw std::runtime_error("Sony stream handle missing");
        void* ptr=reinterpret_cast<void*>(static_cast<uintptr_t>(static_cast<uint32_t>(e->GetIntField(stream,field))));if(!ptr)throw std::runtime_error("closed Sony stream");
        static void* lib=dlopen("libStillPlayHAL.so",RTLD_NOW);if(!lib)throw std::runtime_error("StillPlayHAL unavailable");
        auto read=reinterpret_cast<int(*)(void*,uint8_t*,unsigned)>(dlsym(lib,"_ZN6scalar23NativeMemoryInputStream4readEPhj"));if(!read)throw std::runtime_error("Sony stream reader unavailable");
        const char* text=e->GetStringUTFChars(path,nullptr);if(!text)return -1;output=text;e->ReleaseStringUTFChars(path,text);
        int fd=open(output.c_str(),O_WRONLY|O_CREAT|O_EXCL,0600);if(fd<0)throw std::runtime_error("new output unavailable");owned=true;
        file=fdopen(fd,"wb");if(!file){close(fd);throw std::runtime_error("output stream unavailable");}
        std::vector<uint8_t> bytes(256*1024);jlong count=0;
        for(;;){int n=read(ptr,bytes.data(),bytes.size());if(n<0||n>int(bytes.size()))throw std::runtime_error("invalid JPEG stream read");if(!n)break;
            count+=n;if(count>24LL*1024*1024||fwrite(bytes.data(),1,n,file)!=size_t(n))throw std::runtime_error("JPEG write failed");}
        if(fflush(file))throw std::runtime_error("JPEG flush failed");
        if(fsync(fileno(file))&&errno!=EINVAL&&errno!=ENOSYS&&errno!=EOPNOTSUPP)throw std::runtime_error("JPEG sync failed");
        int rc=fclose(file);file=nullptr;if(rc)throw std::runtime_error("JPEG close failed");return count;
    }catch(const std::exception& x){if(file)fclose(file);if(owned)unlink(output.c_str());fail(e,x.what());return -1;}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_hongen_sonylutprobe_Native_deviceInfo(JNIEnv* e,jclass,jobject image){
    try{void* m=memory(e,image);std::ostringstream json;json<<"{";bool first=true;
        for(const char* key:{"memory-size","image-data-offset","image-canvas-width","image-canvas-height","image-target-width","image-target-height","image-target-x-offset","image-target-y-offset"}){
            const char* value=property(m,key);if(!first)json<<",";first=false;json<<"\""<<key<<"\":";if(value)json<<"\""<<value<<"\"";else json<<"null";
        }json<<"}";return e->NewStringUTF(json.str().c_str());
    }catch(const std::exception& x){fail(e,x.what());return nullptr;}
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_hongen_sonylutprobe_Native_deviceRead(JNIEnv* e,jclass,jobject image,jint offset,jint length){
    try{void* m=memory(e,image);if(offset<0||length<1||length>1024*1024)throw std::runtime_error("invalid bounded read");
        const char* text=property(m,"memory-size");if(!text)throw std::runtime_error("device memory size unavailable");
        char* end=nullptr;unsigned long size=strtoul(text,&end,0);if(!end||*end||!size||uint64_t(offset)+length>size)throw std::runtime_error("read exceeds device memory");
        std::vector<jbyte> bytes(length);int n=symbol<int(*)(void*,void*,unsigned,unsigned)>("_ZN6scalar18DeviceMemoryDiadem10readBufferEPvjj")(m,bytes.data(),length,offset);
        if(n!=length)throw std::runtime_error("device memory short read");jbyteArray out=e->NewByteArray(n);if(out)e->SetByteArrayRegion(out,0,n,bytes.data());return out;
    }catch(const std::exception& x){fail(e,x.what());return nullptr;}
}
