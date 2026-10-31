#include "core.hpp"
#include "preview_fit.hpp"
#include <jni.h>
#include <android/bitmap.h>
#include <mutex>
#include <memory>
#include <stdexcept>
#include <android/log.h>
struct Engine {sl::Lut lut;sl::Pool pool;std::vector<uint8_t> frame;std::mutex mutex;Engine(const char* p,int n):pool(n){lut.load(p);}explicit Engine(int n):pool(n){}};
static void error(JNIEnv* env,const char* text) {env->ThrowNew(env->FindClass("java/lang/RuntimeException"),text);}
struct Text {JNIEnv* e;jstring s;const char* p;Text(JNIEnv* e,jstring s):e(e),s(s),p(e->GetStringUTFChars(s,nullptr)){}~Text(){if(p)e->ReleaseStringUTFChars(s,p);}};
extern "C" JNIEXPORT jint JNICALL Java_org_hongen_sonylutprobe_Native_inspectLut(JNIEnv* e,jclass,jstring cube) {
    try {Text t(e,cube);if(!t.p)return 0;sl::Lut lut;lut.load(t.p);return lut.size();}
    catch(const std::exception& x) {error(e,x.what());return 0;}
}
extern "C" JNIEXPORT jint JNICALL Java_org_hongen_sonylutprobe_Native_compileLut(JNIEnv* e,jclass,jstring cube,jstring cache,jstring digest) {
    try{Text source(e,cube),target(e,cache),id(e,digest);if(!source.p||!target.p||!id.p)return 0;
        sl::Lut lut;lut.load(source.p);
        try{lut.saveCompiled(target.p,id.p);}catch(const std::exception& x){
            __android_log_print(ANDROID_LOG_WARN,"SonyLutLoad","cache_write_failed: %s; source remains usable",x.what());
        }
        return lut.size();
    }catch(const std::exception& x){error(e,x.what());return 0;}
}
extern "C" JNIEXPORT jlong JNICALL Java_org_hongen_sonylutprobe_Native_create(JNIEnv* e,jclass,jstring cube,jint workers) {
    try {Text t(e,cube);if(!t.p)return 0;return reinterpret_cast<jlong>(new Engine(t.p,workers));}
    catch(const std::exception& x) {error(e,x.what());return 0;}
}
extern "C" JNIEXPORT jlong JNICALL Java_org_hongen_sonylutprobe_Native_createCompiled(JNIEnv* e,jclass,jstring cube,jstring cache,jstring digest,jint workers) {
    try{Text source(e,cube),binary(e,cache),id(e,digest);if(!source.p||!binary.p||!id.p)return 0;
        std::unique_ptr<Engine> engine(new Engine(workers));
        try{engine->lut.loadCompiled(binary.p,id.p);}
        catch(const std::exception& x){__android_log_print(ANDROID_LOG_WARN,"SonyLutLoad","preview cache fallback: %s",x.what());engine->lut.load(source.p);}
        return reinterpret_cast<jlong>(engine.release());
    }catch(const std::exception& x){error(e,x.what());return 0;}
}
extern "C" JNIEXPORT void JNICALL Java_org_hongen_sonylutprobe_Native_destroy(JNIEnv*,jclass,jlong p) {delete reinterpret_cast<Engine*>(p);}
static jstring file(JNIEnv* e,jlong p,jstring in,jstring out,jint scale,jboolean apply,bool pipeline) {
    try {
        if(!p)throw std::runtime_error("no engine");Engine& g=*reinterpret_cast<Engine*>(p);std::lock_guard<std::mutex> guard(g.mutex);
        Text a(e,in),b(e,out);if(!a.p||!b.p)return nullptr;
        std::string s=sl::process_file(a.p,b.p,apply?&g.lut:nullptr,g.pool,scale,92,64,pipeline).json();return e->NewStringUTF(s.c_str());
    }catch(const std::exception& x){error(e,x.what());return nullptr;}
}
extern "C" JNIEXPORT jstring JNICALL Java_org_hongen_sonylutprobe_Native_file(JNIEnv* e,jclass,jlong p,jstring in,jstring out,jint scale,jboolean apply) {return file(e,p,in,out,scale,apply,false);}
extern "C" JNIEXPORT jstring JNICALL Java_org_hongen_sonylutprobe_Native_filePipelined(JNIEnv* e,jclass,jlong p,jstring in,jstring out,jint scale,jboolean apply) {return file(e,p,in,out,scale,apply,true);}
extern "C" JNIEXPORT jstring JNICALL Java_org_hongen_sonylutprobe_Native_frame(JNIEnv* e,jclass,jlong p,jobject input,jint length,jobject bitmap) {
    try {
        if(!p)throw std::runtime_error("no engine");Engine& g=*reinterpret_cast<Engine*>(p);std::lock_guard<std::mutex> guard(g.mutex);
        void* data=e->GetDirectBufferAddress(input);jlong capacity=e->GetDirectBufferCapacity(input);
        if(!data || length<=0 || length>capacity)throw std::runtime_error("invalid direct JPEG buffer");
        int w=0,h=0;sl::Stats s;double start=sl::milliseconds();
        sl::process_preview(static_cast<uint8_t*>(data),length,g.lut,g.pool,w,h,s,g.frame);
        AndroidBitmapInfo info;void* pixels=nullptr;
        if(AndroidBitmap_getInfo(e,bitmap,&info)!=0 || info.format!=ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width!=unsigned(w) || info.height!=unsigned(h))
            throw std::runtime_error("decoded preview dimensions differ from bitmap");
        if(AndroidBitmap_lockPixels(e,bitmap,&pixels)!=0)throw std::runtime_error("cannot lock preview bitmap");
        for(int y=0;y<h;++y) {
            uint8_t* dest=static_cast<uint8_t*>(pixels)+size_t(y)*info.stride;
            const uint8_t* rgb=g.frame.data()+size_t(y)*w*3;
            for(int x=0;x<w;++x){dest[x*4]=rgb[x*3];dest[x*4+1]=rgb[x*3+1];dest[x*4+2]=rgb[x*3+2];dest[x*4+3]=255;}
        }
        AndroidBitmap_unlockPixels(e,bitmap);s.total=sl::milliseconds()-start;
        return e->NewStringUTF(s.json().c_str());
    }catch(const std::exception& x){error(e,x.what());return nullptr;}
}

extern "C" JNIEXPORT jstring JNICALL Java_org_hongen_sonylutprobe_Native_fitPreview(JNIEnv* e,jclass,jstring cube,jstring binary,jstring digest) {
    try{Text source(e,cube),cache(e,binary),id(e,digest);if(!source.p||!cache.p||!id.p)return nullptr;
        sl::Lut lut;try{lut.loadCompiled(cache.p,id.p);}catch(const std::exception& x){
            __android_log_print(ANDROID_LOG_INFO,"SonyPreviewFit","cache fallback: %s",x.what());lut.load(source.p);}
        auto fit=sl::fit_preview(lut);auto profile=fit.profile(id.p);
        __android_log_print(ANDROID_LOG_INFO,"SonyPreviewFit","fit_complete grid=%d ms=%.1f mae=%.3f p95=%.3f",lut.size(),fit.elapsed,fit.mae,fit.p95);
        return e->NewStringUTF(profile.c_str());
    }catch(const std::exception& x){error(e,x.what());return nullptr;}
}
