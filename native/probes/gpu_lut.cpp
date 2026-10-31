// Isolated ES2 LUT experiment. No camera APIs, photo writes, or full-frame buffers.
// Usage: sony-gpu-lut cube copy|tetra|tetra-branchless width height iterations [staged|combined] [rgba|native]
#include "../core.hpp"
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdexcept>
#include <string>
#include <unistd.h>

static const char* vertexSource =
    "attribute vec2 p; varying highp vec2 uv;"
    "void main(){uv=(p+1.0)*0.5;gl_Position=vec4(p,0.0,1.0);}";
static const char* copySource =
    "precision highp float; varying highp vec2 uv; uniform highp sampler2D source;"
    "void main(){gl_FragColor=texture2D(source,uv);}";
static const char* tetraSource = R"GLSL(
precision highp float;
varying highp vec2 uv;
uniform highp sampler2D source;
uniform highp sampler2D axes;
uniform highp sampler2D lowTable;
uniform highp sampler2D highTable;
// n, blue-slice columns, 1/atlas width, 1/atlas height
uniform vec4 atlas;
vec3 axisValue(float inputValue,float channel) {
    vec4 a=floor(texture2D(axes,vec2((floor(inputValue*255.0+0.5)+0.5)/256.0,
                                  (channel+0.5)/3.0))*255.0+0.5);
    return vec3(a.r,(a.g*256.0+a.b)/16384.0,a.a);
}
vec3 vertexValue(vec3 p) {
    // Slice indices are integers. A reciprocal-based division on SMAPH can
    // put 30/6 just below 5, so floor/mod would select the preceding atlas
    // row and an out-of-range column. Put the quotient inside its interval.
    float tileY=floor((p.b+0.5)/atlas.y);
    float tileX=p.b-tileY*atlas.y;
    vec2 t=(vec2(p.r+tileX*atlas.x,p.g+tileY*atlas.x)+0.5)*atlas.zw;
    vec4 low=floor(texture2D(lowTable,t)*255.0+0.5);
    vec3 high=floor(texture2D(highTable,t).rgb*255.0+0.5);
    // Signed Q12 [-16,16], exactly stored in bytes. Alpha carries 2 upper
    // bits per channel; float arithmetic is separately checked against CPU.
    vec3 upper=mod(floor(low.aaa/vec3(1.0,4.0,16.0)),4.0)-1.0;
    return low.rgb/4096.0+high/16.0+upper*16.0;
}
vec3 grade(vec3 color) {
    vec3 ar=axisValue(color.r,0.0),ag=axisValue(color.g,1.0),ab=axisValue(color.b,2.0);
    vec3 base=vec3(ar.x,ag.x,ab.x),f=vec3(ar.y,ag.y,ab.y);
    vec3 r=vec3(ar.z,0.0,0.0),g=vec3(0.0,ag.z,0.0),b=vec3(0.0,0.0,ab.z);
    vec3 p1,p2;vec4 w;
#ifdef BRANCHLESS
    float top=max(f.x,max(f.y,f.z)),bottom=min(f.x,min(f.y,f.z));
    float middle=max(min(f.x,f.y),min(max(f.x,f.y),f.z));
    vec3 largest=step(f.yzx,f.xyz)*step(f.zxy,f.xyz);
    vec3 smallest=step(f.xyz,f.yzx)*step(f.xyz,f.zxy);
    vec3 delta=vec3(ar.z,ag.z,ab.z);
    // On a tie, the affected vertex has zero weight; multiple selected axes
    // remain inside this cell. No different interpolation is introduced.
    p1=base+largest*delta;p2=base+delta-smallest*delta;
    w=vec4(1.0-top,top-middle,middle-bottom,bottom);
#else
    if(f.x>=f.y) {
        if(f.y>=f.z){p1=base+r;p2=p1+g;w=vec4(1.0-f.x,f.x-f.y,f.y-f.z,f.z);}
        else if(f.x>=f.z){p1=base+r;p2=p1+b;w=vec4(1.0-f.x,f.x-f.z,f.z-f.y,f.y);}
        else{p1=base+b;p2=p1+r;w=vec4(1.0-f.z,f.z-f.x,f.x-f.y,f.y);}
    } else {
        if(f.z>=f.y){p1=base+b;p2=p1+g;w=vec4(1.0-f.z,f.z-f.y,f.y-f.x,f.x);}
        else if(f.z>=f.x){p1=base+g;p2=p1+b;w=vec4(1.0-f.y,f.y-f.z,f.z-f.x,f.x);}
        else{p1=base+g;p2=p1+r;w=vec4(1.0-f.y,f.y-f.x,f.x-f.z,f.z);}
    }
#endif
    vec3 value=clamp(vertexValue(base)*w.x+vertexValue(p1)*w.y+
                     vertexValue(p2)*w.z+vertexValue(base+r+g+b)*w.w,0.0,1.0);
    // Match the CPU's two rounding stages: Q12, then 8-bit output.
    return floor(floor(value*4096.0+0.5)*255.0/4096.0+0.5)/255.0;
}
void main(){gl_FragColor=vec4(grade(texture2D(source,uv).rgb),1.0);}
)GLSL";

static void checkGL(const char* where) {
    GLenum e=glGetError();if(e!=GL_NO_ERROR){char b[160];snprintf(b,sizeof(b),"%s GL error 0x%x",where,e);throw std::runtime_error(b);}
}
static GLuint compile(GLenum type,const char* source) {
    GLuint s=glCreateShader(type);GLint ok=0;glShaderSource(s,1,&source,0);glCompileShader(s);
    glGetShaderiv(s,GL_COMPILE_STATUS,&ok);
    if(!ok){char b[4096];glGetShaderInfoLog(s,sizeof(b),0,b);glDeleteShader(s);throw std::runtime_error(std::string("shader: ")+b);}
    return s;
}
struct GPU {
    EGLDisplay display=EGL_NO_DISPLAY;EGLContext context=EGL_NO_CONTEXT;EGLSurface surface=EGL_NO_SURFACE;
    GLuint textures[5]={0,0,0,0,0},fb=0,program=0;
    double setupMs=0;
    GPU(const sl::Lut& lut,bool copy,bool branchless,int width,int height) {
        double begin=sl::milliseconds();
        const EGLint attrs[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
            EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_DEPTH_SIZE,0,EGL_STENCIL_SIZE,0,EGL_NONE};
        const EGLint pb[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE},ver[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
        EGLConfig config;EGLint count;
        display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if(!eglInitialize(display,0,0)||!eglBindAPI(EGL_OPENGL_ES_API)||!eglChooseConfig(display,attrs,&config,1,&count)||!count)
            throw std::runtime_error("EGL setup failed");
        surface=eglCreatePbufferSurface(display,config,pb);context=eglCreateContext(display,config,EGL_NO_CONTEXT,ver);
        if(surface==EGL_NO_SURFACE||context==EGL_NO_CONTEXT||!eglMakeCurrent(display,surface,surface,context))
            throw std::runtime_error("EGL current failed");
        printf("{\"kind\":\"device\",\"renderer\":\"%s\",\"gl\":\"%s\"}\n",glGetString(GL_RENDERER),glGetString(GL_VERSION));
        std::string fragment=copy?copySource:tetraSource;
        if(branchless)fragment="#define BRANCHLESS\n"+fragment;
        GLuint vs=compile(GL_VERTEX_SHADER,vertexSource),fs=compile(GL_FRAGMENT_SHADER,fragment.c_str());
        program=glCreateProgram();glAttachShader(program,vs);glAttachShader(program,fs);glBindAttribLocation(program,0,"p");glLinkProgram(program);
        glDeleteShader(vs);glDeleteShader(fs);GLint ok=0;glGetProgramiv(program,GL_LINK_STATUS,&ok);
        if(!ok){char b[4096];glGetProgramInfoLog(program,sizeof(b),0,b);throw std::runtime_error(std::string("link: ")+b);}
        glUseProgram(program);glGenTextures(5,textures);
        allocate(0,width,height,0);allocate(4,width,height,0);
        glGenFramebuffers(1,&fb);glBindFramebuffer(GL_FRAMEBUFFER,fb);
        glFramebufferTexture2D(GL_FRAMEBUFFER,GL_COLOR_ATTACHMENT0,GL_TEXTURE_2D,textures[4],0);
        if(glCheckFramebufferStatus(GL_FRAMEBUFFER)!=GL_FRAMEBUFFER_COMPLETE)throw std::runtime_error("RGBA8 FBO incomplete");
        GLint readFormat=0,readType=0;
        glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT,&readFormat);
        glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE,&readType);
        printf("{\"kind\":\"read_format\",\"format\":%d,\"type\":%d,\"egl_extensions\":\"%s\"}\n",readFormat,readType,eglQueryString(display,EGL_EXTENSIONS));
        glUniform1i(glGetUniformLocation(program,"source"),0);
        if(!copy){
            const int n=lut.size(),cols=int(std::ceil(std::sqrt(double(n)))),aw=n*cols,ah=n*((n+cols-1)/cols);
            std::vector<uint8_t> lo(size_t(aw)*ah*4,0),hi(lo.size(),0),axes(256*3*4,0);
            const std::vector<int32_t>& values=lut.valuesQ12();
            for(int b=0;b<n;b++)for(int g=0;g<n;g++)for(int r=0;r<n;r++){
                size_t at=size_t((b/cols)*n+g)*aw+(b%cols)*n+r;unsigned upper=0;
                for(int c=0;c<3;c++){
                    int q=values[size_t(b*n*n+g*n+r)*3+c];
                    // Arithmetic shifts are avoided for portable negative packing.
                    unsigned biased=unsigned(q+65536);lo[at*4+c]=biased&255;hi[at*4+c]=(biased>>8)&255;
                    upper|=(biased>>16)<<(2*c);
                }
                lo[at*4+3]=upper;hi[at*4+3]=255;
            }
            int stride=3;
            for(int c=0;c<3;c++){
                for(int v=0;v<256;v++){
                    const sl::Axis& a=lut.axis(c,uint8_t(v));size_t at=size_t(c*256+v)*4;
                    axes[at]=a.base/stride;axes[at+1]=a.fraction>>8;axes[at+2]=a.fraction&255;axes[at+3]=a.delta?1:0;
                }
                stride*=n;
            }
            allocate(1,256,3,axes.data());allocate(2,aw,ah,lo.data());allocate(3,aw,ah,hi.data());
            glUniform1i(glGetUniformLocation(program,"axes"),1);glUniform1i(glGetUniformLocation(program,"lowTable"),2);glUniform1i(glGetUniformLocation(program,"highTable"),3);
            glUniform4f(glGetUniformLocation(program,"atlas"),float(n),float(cols),1.0f/aw,1.0f/ah);
        }
        static const GLfloat points[]={-1,-1,1,-1,-1,1,1,1};
        glViewport(0,0,width,height);glDisable(GL_DITHER);glDisable(GL_BLEND);
        glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,points);glEnableVertexAttribArray(0);
        glPixelStorei(GL_PACK_ALIGNMENT,1);glPixelStorei(GL_UNPACK_ALIGNMENT,1);glFinish();checkGL("setup");
        setupMs=sl::milliseconds()-begin;
    }
    void allocate(int unit,int w,int h,const uint8_t* data){
        glActiveTexture(GL_TEXTURE0+unit);glBindTexture(GL_TEXTURE_2D,textures[unit]);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,w,h,0,GL_RGBA,GL_UNSIGNED_BYTE,data);checkGL("texture");
    }
    ~GPU(){
        glDeleteFramebuffers(1,&fb);glDeleteTextures(5,textures);glDeleteProgram(program);
        eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
        eglDestroyContext(display,context);eglDestroySurface(display,surface);eglTerminate(display);
    }
};

static unsigned randomWord(unsigned& state){state^=state<<13;state^=state>>17;state^=state<<5;return state;}
int main(int argc,char** argv){
    alarm(55);setvbuf(stdout,0,_IONBF,0);
    try{
        if(argc<6||argc>8)throw std::runtime_error("usage: cube copy|tetra|tetra-branchless width height iterations [staged|combined] [rgba|native]");
        const std::string timing=argc>=7?argv[6]:"staged";
        if(timing!="staged"&&timing!="combined")throw std::runtime_error("invalid timing mode");
        const std::string readMode=argc==8?argv[7]:"rgba";
        if(readMode!="rgba"&&readMode!="native")throw std::runtime_error("invalid read mode");
        const bool copy=std::string(argv[2])=="copy";
        const bool branchless=std::string(argv[2])=="tetra-branchless";
        if(!copy&&!branchless&&std::string(argv[2])!="tetra")throw std::runtime_error("invalid mode");
        int width=atoi(argv[3]),height=atoi(argv[4]),iterations=atoi(argv[5]);
        if(width<1||width>3072||height<1||height>512||size_t(width)*height>262144||iterations<1||iterations>1000)throw std::runtime_error("bounded dimensions/iterations exceeded");
        const size_t pixels=size_t(width)*height;
        sl::Lut lut;lut.load(argv[1]);sl::Pool pool(4);GPU gpu(lut,copy,branchless,width,height);
        GLint readFormat=GL_RGBA,readType=GL_UNSIGNED_BYTE;
        if(readMode=="native"){
            glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_FORMAT,&readFormat);glGetIntegerv(GL_IMPLEMENTATION_COLOR_READ_TYPE,&readType);
        }
        if((readFormat!=GL_RGBA&&readFormat!=GL_RGB)||readType!=GL_UNSIGNED_BYTE)throw std::runtime_error("unsupported native read format");
        const int components=readFormat==GL_RGBA?4:3;
        std::vector<uint8_t> input(pixels*4),output(pixels*components),rgb(pixels*3),reference(pixels*3);
        unsigned state=0x71432659;
        for(size_t i=0;i<pixels;i++){
            unsigned r=randomWord(state);
            for(int c=0;c<3;c++)input[i*4+c]=rgb[i*3+c]=uint8_t(r>>(c*8));input[i*4+3]=255;
        }
        // Explicit extrema and neutral samples, then a deterministic diverse set.
        for(size_t i=0;i<std::min(pixels,size_t(256));i++)for(int c=0;c<3;c++)input[i*4+c]=rgb[i*3+c]=uint8_t(i);
        double upload=0,draw=0,read=0,cpu=0,first=0;size_t processed=0,checked=0,differing=0,overOne=0;int maximum=0,alphaErrors=0;
        printf("{\"kind\":\"begin\",\"mode\":\"%s\",\"timing\":\"%s\",\"read_mode\":\"%s\",\"lut_size\":%d,\"width\":%d,\"height\":%d,\"iterations\":%d,\"setup_ms\":%.3f}\n",argv[2],timing.c_str(),readMode.c_str(),lut.size(),width,height,iterations,gpu.setupMs);
        for(int step=0;step<iterations;step++){
            // Prevent an identical upload shortcut without including fixture
            // generation in the measured GPU or CPU kernel time.
            input[0]=rgb[0]=uint8_t(step);input[1]=rgb[1]=uint8_t(step*71);input[2]=rgb[2]=uint8_t(step*113);
            double t0=sl::milliseconds();
            glActiveTexture(GL_TEXTURE0);glBindTexture(GL_TEXTURE_2D,gpu.textures[0]);
            glTexSubImage2D(GL_TEXTURE_2D,0,0,0,width,height,GL_RGBA,GL_UNSIGNED_BYTE,input.data());
            if(timing=="staged")glFinish();
            double t1=sl::milliseconds();glDrawArrays(GL_TRIANGLE_STRIP,0,4);
            if(timing=="staged")glFinish();
            double t2=sl::milliseconds();glReadPixels(0,0,width,height,readFormat,readType,output.data());
            double t3=sl::milliseconds();checkGL("tile");
            processed+=pixels;
            upload+=t1-t0;draw+=t2-t1;read+=t3-t2;if(!step)first=t3-t0;
            reference=rgb;double c0=sl::milliseconds();if(!copy)pool.run(lut,reference.data(),width,height,size_t(width)*3);cpu+=sl::milliseconds()-c0;
            if(step<4||step==iterations-1){
                for(size_t i=0;i<pixels;i++){
                    if(components==4&&output[i*4+3]!=255)alphaErrors++;
                    for(int c=0;c<3;c++){
                        int d=std::abs(int(output[i*components+c])-int(reference[i*3+c]));maximum=std::max(maximum,d);differing+=d!=0;overOne+=d>1;checked++;
                        if(d>1&&overOne<=4)printf("{\"kind\":\"mismatch\",\"pixel\":%zu,\"channel\":%d,\"input\":[%u,%u,%u],\"expected\":%u,\"actual\":%u}\n",i,c,rgb[i*3],rgb[i*3+1],rgb[i*3+2],reference[i*3+c],output[i*components+c]);
                    }
                }
            }
            if(step==0)printf("{\"kind\":\"first_tile\",\"ms\":%.3f,\"max_error\":%d,\"over_one\":%zu}\n",first,maximum,overOne);
            if(maximum>1||alphaErrors)break;
        }
        bool passed=maximum<=(copy?0:1)&&!alphaErrors;
        printf("{\"kind\":\"result\",\"passed\":%s,\"processed_pixels\":%zu,\"upload_ms\":%.3f,\"draw_ms\":%.3f,\"readback_ms\":%.3f,\"gpu_total_ms\":%.3f,\"cpu_rgb_4thread_ms\":%.3f,\"first_tile_ms\":%.3f,\"checked_channels\":%zu,\"different_channels\":%zu,\"max_error\":%d,\"over_one\":%zu,\"alpha_errors\":%d}\n",passed?"true":"false",processed,upload,draw,read,upload+draw+read,cpu,first,checked,differing,maximum,overOne,alphaErrors);
        return passed?0:2;
    }catch(const std::exception& e){fprintf(stderr,"PROBE_FAILED: %s\n",e.what());return 1;}
}
