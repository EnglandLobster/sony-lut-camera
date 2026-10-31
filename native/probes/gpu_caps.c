/* Standalone, 1x1 offscreen GPU capability check. No camera or photo access. */
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>

static GLuint shader(GLenum type,const char *source) {
    GLuint id=glCreateShader(type);GLint ok=0;char log[1024];
    glShaderSource(id,1,&source,NULL);glCompileShader(id);glGetShaderiv(id,GL_COMPILE_STATUS,&ok);
    if(!ok){glGetShaderInfoLog(id,sizeof(log),NULL,log);fprintf(stderr,"shader_compile_failed=%s\n",log);glDeleteShader(id);return 0;}
    return id;
}
int main(void) {
    EGLDisplay display=EGL_NO_DISPLAY;EGLContext context=EGL_NO_CONTEXT;EGLSurface surface=EGL_NO_SURFACE;
    GLuint vertex=0,fragment=0,program=0,texture=0;int rc=1;EGLint major=0,minor=0,count=0;EGLConfig config;
    GLint size=0,units=0,range[2]={0,0},precision=0,linked=0;
    const EGLint attributes[]={EGL_SURFACE_TYPE,EGL_PBUFFER_BIT,EGL_RENDERABLE_TYPE,EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE,8,EGL_GREEN_SIZE,8,EGL_BLUE_SIZE,8,EGL_ALPHA_SIZE,8,EGL_DEPTH_SIZE,0,EGL_STENCIL_SIZE,0,EGL_NONE};
    const EGLint buffer[]={EGL_WIDTH,1,EGL_HEIGHT,1,EGL_NONE};
    const EGLint version[]={EGL_CONTEXT_CLIENT_VERSION,2,EGL_NONE};
    const GLfloat points[]={-1,-1,1,-1,-1,1,1,1};
    const GLubyte pixels[]={64,128,192,255,64,128,192,255,64,128,192,255,64,128,192,255};
    GLubyte output[4]={0,0,0,0};
    alarm(15);
    setvbuf(stdout,NULL,_IONBF,0);
    display=eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if(display==EGL_NO_DISPLAY||!eglInitialize(display,&major,&minor)){fprintf(stderr,"egl_init_failed=0x%x\n",eglGetError());goto cleanup;}
    printf("egl_version=%d.%d\negl_vendor=%s\n",major,minor,eglQueryString(display,EGL_VENDOR));
    printf("egl_extensions=%s\n",eglQueryString(display,EGL_EXTENSIONS));
    if(!eglBindAPI(EGL_OPENGL_ES_API)||!eglChooseConfig(display,attributes,&config,1,&count)||count<1){fprintf(stderr,"no_es2_rgba8_pbuffer=0x%x\n",eglGetError());goto cleanup;}
    surface=eglCreatePbufferSurface(display,config,buffer);
    context=eglCreateContext(display,config,EGL_NO_CONTEXT,version);
    if(surface==EGL_NO_SURFACE||context==EGL_NO_CONTEXT||!eglMakeCurrent(display,surface,surface,context)){fprintf(stderr,"es2_context_failed=0x%x\n",eglGetError());goto cleanup;}
    printf("gl_vendor=%s\ngl_renderer=%s\ngl_version=%s\nglsl_version=%s\n",glGetString(GL_VENDOR),glGetString(GL_RENDERER),glGetString(GL_VERSION),glGetString(GL_SHADING_LANGUAGE_VERSION));
    printf("gl_extensions=%s\n",glGetString(GL_EXTENSIONS));
    glGetIntegerv(GL_MAX_TEXTURE_SIZE,&size);glGetIntegerv(GL_MAX_TEXTURE_IMAGE_UNITS,&units);
    printf("max_texture_size=%d\nfragment_texture_units=%d\n",size,units);
    glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE,&size);printf("max_renderbuffer_size=%d\n",size);
    glGetShaderPrecisionFormat(GL_FRAGMENT_SHADER,GL_HIGH_FLOAT,range,&precision);
    printf("fragment_highp_bits=%d\nfragment_highp_range=%d,%d\n",precision,range[0],range[1]);
    vertex=shader(GL_VERTEX_SHADER,"attribute vec2 p; varying vec2 uv; void main(){uv=(p+1.0)*0.5;gl_Position=vec4(p,0.0,1.0);}");
    fragment=shader(GL_FRAGMENT_SHADER,"precision mediump float; varying vec2 uv; uniform sampler2D source; void main(){vec3 c=texture2D(source,uv).rgb;gl_FragColor=vec4(c.b,c.r,c.g,1.0);}");
    if(!vertex||!fragment)goto cleanup;
    program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);glBindAttribLocation(program,0,"p");glLinkProgram(program);glGetProgramiv(program,GL_LINK_STATUS,&linked);
    if(!linked){char log[1024];glGetProgramInfoLog(program,sizeof(log),NULL,log);fprintf(stderr,"program_link_failed=%s\n",log);goto cleanup;}
    glUseProgram(program);glUniform1i(glGetUniformLocation(program,"source"),0);
    glGenTextures(1,&texture);glBindTexture(GL_TEXTURE_2D,texture);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_NEAREST);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE);glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D,0,GL_RGBA,2,2,0,GL_RGBA,GL_UNSIGNED_BYTE,pixels);
    glViewport(0,0,1,1);glDisable(GL_DITHER);glVertexAttribPointer(0,2,GL_FLOAT,GL_FALSE,0,points);glEnableVertexAttribArray(0);
    glDrawArrays(GL_TRIANGLE_STRIP,0,4);glReadPixels(0,0,1,1,GL_RGBA,GL_UNSIGNED_BYTE,output);
    printf("test_input_rgba=64,128,192,255\ntest_expected_rgba=192,64,128,255\ntest_actual_rgba=%u,%u,%u,%u\n",output[0],output[1],output[2],output[3]);
    if(glGetError()!=GL_NO_ERROR||abs((int)output[0]-192)>1||abs((int)output[1]-64)>1||abs((int)output[2]-128)>1||output[3]!=255){fprintf(stderr,"pixel_validation_failed\n");goto cleanup;}
    printf("RESULT=PASS_ES2_TEXTURE_SHADER_READBACK\n");rc=0;
cleanup:
    if(context!=EGL_NO_CONTEXT&&eglGetCurrentContext()==context){
        if(texture)glDeleteTextures(1,&texture);if(program)glDeleteProgram(program);if(vertex)glDeleteShader(vertex);if(fragment)glDeleteShader(fragment);
        eglMakeCurrent(display,EGL_NO_SURFACE,EGL_NO_SURFACE,EGL_NO_CONTEXT);
    }
    if(context!=EGL_NO_CONTEXT)eglDestroyContext(display,context);
    if(surface!=EGL_NO_SURFACE)eglDestroySurface(display,surface);
    if(display!=EGL_NO_DISPLAY)eglTerminate(display);
    return rc;
}
