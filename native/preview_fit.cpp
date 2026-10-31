#include "preview_fit.hpp"
#include <algorithm>
#include <cmath>
#include <sstream>
#include <iomanip>
#include <stdexcept>

namespace sl { namespace {
const int K=33;
struct Sample { double x[3],y[3]; };
struct Model { double m[3][3],g[K]; };
double clamp(double x,double lo=0,double hi=1){return std::max(lo,std::min(hi,x));}
double curve(const Model& m,double x){x=clamp(x)*32;int j=std::min(31,int(x));return m.g[j]+(m.g[j+1]-m.g[j])*(x-j);}
double predict(const Model& m,const Sample& s,int c){double x=0;for(int a=0;a<3;a++)x+=m.m[c][a]*s.x[a];return curve(m,x);}
Model identity(){Model m={};for(int i=0;i<3;i++)m.m[i][i]=1;for(int i=0;i<K;i++)m.g[i]=i/32.;return m;}
// Independent deterministic streams for fit and validation; uses production
// tetrahedral/Q12 engine, including DOMAIN and output clipping.
std::vector<Sample> samples(const Lut& lut,int count,uint32_t seed,bool neutrals){
    std::vector<uint8_t> input;input.reserve((count+385)*3);
    for(int i=0;i<count;i++)for(int c=0;c<3;c++){seed=1664525u*seed+1013904223u;input.push_back(uint8_t(seed>>24));}
    if(neutrals){
        for(int i=0;i<=128;i++)for(int c=0;c<3;c++)input.push_back(uint8_t(std::min(255,i*2)));
        for(int i=0;i<256;i++){seed=1664525u*seed+1013904223u;int base=seed>>24;
            for(int c=0;c<3;c++){seed=1664525u*seed+1013904223u;input.push_back(uint8_t(clamp(base+int(seed>>27)-16,0,255)));}}
    }
    std::vector<uint8_t> target=input;lut.apply(target.data(),target.size()/3);
    std::vector<Sample> out(input.size()/3);
    for(size_t i=0;i<out.size();i++)for(int c=0;c<3;c++){out[i].x[c]=input[i*3+c]/255.;out[i].y[c]=target[i*3+c]/255.;}
    return out;
}
double loss(const Model& m,const std::vector<Sample>& data){double sum=0;for(const auto& s:data)for(int c=0;c<3;c++){double e=predict(m,s,c)-s.y[c];sum+=e*e;}return sum/(data.size()*3);}
bool solve(double a[K][K],double b[K],double x[K],int n){
    for(int i=0;i<n;i++){
        int pivot=i;for(int j=i+1;j<n;j++)if(std::abs(a[j][i])>std::abs(a[pivot][i]))pivot=j;
        if(std::abs(a[pivot][i])<1e-12)return false;
        for(int j=0;j<n;j++)std::swap(a[i][j],a[pivot][j]);std::swap(b[i],b[pivot]);
        for(int k=i+1;k<n;k++){double q=a[k][i]/a[i][i];for(int j=i;j<n;j++)a[k][j]-=q*a[i][j];b[k]-=q*b[i];}
    }
    for(int i=n-1;i>=0;i--){double v=b[i];for(int j=i+1;j<n;j++)v-=a[i][j]*x[j];x[i]=v/a[i][i];if(!std::isfinite(x[i]))return false;}
    return true;
}
void fitCurve(Model& m,const std::vector<Sample>& data){
    double a[K][K]={},b[K]={},x[K]={},weights[K];
    for(int i=0;i<K;i++){a[i][i]=.5;b[i]=.5*m.g[i];}
    for(const auto& s:data)for(int c=0;c<3;c++){
        double v=0;for(int j=0;j<3;j++)v+=m.m[c][j]*s.x[j];v=clamp(v)*32;
        int j=std::min(31,int(v));double f=v-j,u=1-f;
        a[j][j]+=u*u;a[j+1][j+1]+=f*f;a[j][j+1]+=u*f;a[j+1][j]+=u*f;
        b[j]+=u*s.y[c];b[j+1]+=f*s.y[c];
    }
    for(int i=0;i<K;i++)weights[i]=a[i][i];
    if(!solve(a,b,x,K))return;
    // Weighted isotonic projection: bounded monotone LUT by construction.
    double value[K],weight[K];int first[K],last[K],blocks=0;
    for(int i=0;i<K;i++){
        value[blocks]=clamp(x[i]);weight[blocks]=weights[i];first[blocks]=last[blocks]=i;blocks++;
        while(blocks>1&&value[blocks-2]>value[blocks-1]){
            int j=blocks-2;double w=weight[j]+weight[j+1];value[j]=(value[j]*weight[j]+value[j+1]*weight[j+1])/w;
            weight[j]=w;last[j]=last[j+1];blocks--;
        }
    }
    Model candidate=m;for(int j=0;j<blocks;j++)for(int i=first[j];i<=last[j];i++)candidate.g[i]=value[j];
    if(loss(candidate,data)<loss(m,data))m=candidate;
}
void fitMatrix(Model& m,const std::vector<Sample>& data){
    double before=loss(m,data);Model candidate=m;
    for(int c=0;c<3;c++){
        double a[K][K]={},b[K]={},delta[K]={};for(int j=0;j<3;j++)a[j][j]=2.;
        for(const auto& s:data){
            double v=0;for(int j=0;j<3;j++)v+=m.m[c][j]*s.x[j];if(v<0||v>1)continue;
            int k=std::min(31,int(v*32));double slope=32*(m.g[k+1]-m.g[k]);double e=s.y[c]-curve(m,v);
            for(int j=0;j<3;j++){double d=slope*s.x[j];b[j]+=d*e;for(int l=0;l<3;l++)a[j][l]+=d*slope*s.x[l];}
        }
        if(solve(a,b,delta,3))for(int j=0;j<3;j++)candidate.m[c][j]=clamp(m.m[c][j]+delta[j],-2,2);
    }
    for(double scale=1;scale>=.0625;scale*=.5){Model trial=m;
        for(int c=0;c<3;c++)for(int j=0;j<3;j++)trial.m[c][j]=m.m[c][j]+scale*(candidate.m[c][j]-m.m[c][j]);
        if(loss(trial,data)<before){m=trial;return;}
    }
}
Model linearStart(const std::vector<Sample>& data){
    Model m=identity();for(int c=0;c<3;c++){
        double a[K][K]={},b[K]={},x[K]={};for(int j=0;j<3;j++){a[j][j]=.5;b[j]=j==c?.5:0;}
        for(const auto& s:data)for(int j=0;j<3;j++){b[j]+=s.x[j]*s.y[c];for(int k=0;k<3;k++)a[j][k]+=s.x[j]*s.x[k];}
        if(solve(a,b,x,3))for(int j=0;j<3;j++)m.m[c][j]=clamp(x[j],-2,2);
    }return m;
}
}
PreviewFit fit_preview(const Lut& lut){
    if(lut.size()<2)throw std::runtime_error("preview requires a loaded LUT");
    double begin=milliseconds();auto train=samples(lut,2048,6300,true),validation=samples(lut,4096,330063,false);
    Model best=identity();double bestLoss=loss(best,train);
    for(int start=0;start<2;start++){
        Model m=start?linearStart(train):identity();
        for(int iteration=0;iteration<24;iteration++){
            double old=loss(m,train);fitCurve(m,train);fitMatrix(m,train);double next=loss(m,train);
            if(next<bestLoss){best=m;bestLoss=next;}
            if(old-next<1e-9)break;
        }
    }
    PreviewFit result;
    for(int c=0;c<3;c++)for(int j=0;j<3;j++)result.matrix[c*3+j]=int(::lround(best.m[c][j]*1024));
    for(int i=0;i<1024;i++)result.gamma[i]=int(::lround(curve(best,i/1023.)*1023));
    // Report error after the exact hardware parameter quantization.
    std::vector<double> errors;errors.reserve(validation.size()*3);
    for(const auto& s:validation)for(int c=0;c<3;c++){
        double v=0;for(int j=0;j<3;j++)v+=result.matrix[c*3+j]/1024.*s.x[j];v=clamp(v)*1023;
        int k=std::min(1022,int(v));double y=(result.gamma[k]+(result.gamma[k+1]-result.gamma[k])*(v-k))/1023.;
        double e=std::abs(y-s.y[c])*255;result.mae+=e;result.identityMae+=std::abs(s.x[c]-s.y[c])*255;errors.push_back(e);
    }
    result.mae/=errors.size();result.identityMae/=errors.size();std::sort(errors.begin(),errors.end());result.p95=errors[size_t((errors.size()-1)*.95)];
    result.elapsed=milliseconds()-begin;return result;
}
std::string PreviewFit::profile(const std::string& digest)const{
    if(digest.size()!=64||digest.find_first_not_of("0123456789abcdef")!=std::string::npos)throw std::runtime_error("invalid preview digest");
    std::ostringstream s;s.imbue(std::locale::classic());s<<std::setprecision(10)<<"SONY_PREVIEW_V2\n"<<digest<<"\nmatrix_gamma\n1 "<<mae<<' '<<p95<<' '<<identityMae<<' '<<elapsed<<'\n';
    for(int v:matrix)s<<v<<' ';s<<'\n';for(int v:gamma)s<<v<<' ';s<<'\n';return s.str();
}
}
