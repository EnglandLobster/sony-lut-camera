package org.hongen.sonylutprobe;

/** Keep the negative still during LUT work; reveal only the completed grade. */
final class DevelopTimeline {
    static final int FRAME_MS=125;
    static final long REVEAL_MS=1000;
    private long shot,start=-1;
    private boolean active,sourceReady;
    void begin(long shot,boolean enabled){this.shot=shot;active=enabled;sourceReady=false;start=-1;}
    boolean accepts(long shot){return active&&this.shot==shot;}
    boolean source(long shot,long now){if(!accepts(shot)||sourceReady)return false;sourceReady=true;return true;}
    boolean graded(long shot,long now){if(!accepts(shot)||!sourceReady||start>=0)return false;start=now;return true;}
    boolean hasSource(){return active&&sourceReady;}
    boolean moving(long now){return hasSource()&&start>=0&&now-start<REVEAL_MS;}
    float reveal(long now){return hasSource()&&start>=0?fraction(now-start):0;}
    void end(){active=false;sourceReady=false;start=-1;}
    static float fraction(long elapsed){
        float t=Math.max(0,Math.min(1,elapsed/(float)REVEAL_MS));
        return t*t*(3-2*t);
    }
}
