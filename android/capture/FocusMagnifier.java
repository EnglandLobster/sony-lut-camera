package io.github.englandlobster.lutcamera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Native preview magnification state, independent of Android and capture.
 * Advertised levels are opaque IDs; only the callback's actual ratio is used
 * for position bounds. No image resizing or capture-parameter changes. */
final class FocusMagnifier {
    interface Driver { void start(int level,int x,int y)throws Exception;void stop()throws Exception; }
    private final Driver driver;
    private final List<Integer> levels=new ArrayList<Integer>();
    private int level,x,y,actual;
    private boolean ring,suppressed,awaiting;
    private long deadline;
    FocusMagnifier(Driver driver,List<Integer> supported){
        this.driver=driver;
        if(supported!=null)for(Integer value:supported)if(value!=null&&value>0&&!levels.contains(value))levels.add(value);
        Collections.sort(levels);
    }
    boolean supported(){return !levels.isEmpty();}
    boolean active(){return level!=0;}
    int x(){return x;}int y(){return y;}int actual(){return actual;}
    long deadline(){return deadline;}
    void timeout(long now,int durationMs){deadline=active()&&!ring&&durationMs>0?now+durationMs:0;}
    void next()throws Exception{
        if(!supported())throw new UnsupportedOperationException("Focus magnification unavailable");
        int index=levels.indexOf(level)+1;
        if(index>=levels.size()){stop();return;}
        deadline=0;start(levels.get(index),x,y);
    }
    private void start(int next,int px,int py)throws Exception{
        driver.start(next,px,py);level=next;x=px;y=py;awaiting=true;
    }
    void move(int dx,int dy)throws Exception{
        if(!active())return;
        int limit=actual>100?Math.max(0,1000-100000/actual):900;
        int px=Math.max(-limit,Math.min(limit,x+dx*80)),py=Math.max(-limit,Math.min(limit,y+dy*80));
        start(level,px,py);
    }
    /** Half-press cancels and suppresses automatic reopening until release. */
    void shutter(boolean down)throws Exception{suppressed=down;if(down)stop();}
    void ring(boolean turning,int px,int py,boolean enabled,boolean manualFocus,boolean allowed,long now,int durationMs)throws Exception{
        if(turning&&!ring)suppressed=false; // A new turn may start DMF assist after half-press.
        ring=turning;
        if(turning){
            deadline=0;
            if(!active()&&!suppressed&&enabled&&manualFocus&&allowed&&supported()){
                start(levels.get(0),px,py);
            }
        }else timeout(now,durationMs);
    }
    void expire(long now)throws Exception{if(deadline>0&&now>=deadline&&!ring)stop();}
    void changed(boolean on,int ratio,int reportedLevel,int px,int py){
        // A queued positive notification must not resurrect a cancelled request.
        if(!active())return;
        // A delayed OFF from the previous request must not abandon a new zoom.
        // Retain ownership until its ON acknowledgement or an explicit stop.
        if(!on){if(!awaiting){level=0;actual=0;deadline=0;}return;}
        if(reportedLevel!=level)return;
        awaiting=false;
        actual=ratio;x=px;y=py;
        if(levels.contains(reportedLevel))level=reportedLevel;
    }
    void stop()throws Exception{
        if(active())driver.stop(); // Keep active state on failure, so teardown retries.
        level=0;actual=0;deadline=0;awaiting=false;
    }
}
