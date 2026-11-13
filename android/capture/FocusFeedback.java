package io.github.englandlobster.lutcamera;

import java.util.ArrayList;
import java.util.List;

/** Focus confirmation for the viewfinder. Pure: fed by Sony's
 * AutoFocusStart/AutoFocusDone/FocusArea notifications, read by the HUD.
 *
 * CameraEx.AutoFocusDoneListener statuses: 0 CLEAR, 1 LOCK, 2 LOCK_WARN,
 * 3 WORKING, 4 CONTINUOUS. LOCK and CONTINUOUS (AF-C in focus) turn the
 * finder green, LOCK_WARN reports a miss, CLEAR (half-press released)
 * removes the indication. */
final class FocusFeedback {
    enum State { NONE, WORKING, LOCKED, FAILED }
    static final int CLEAR=0,LOCK=1,LOCK_WARN=2,WORKING=3,CONTINUOUS=4;
    /** Sony frame space used by focus-point-x/y. */
    static final int LIMIT=1000;

    private State state=State.NONE;
    private String mode;
    private final List<int[]> areas=new ArrayList<int[]>(); // {index,left,top,right,bottom}
    private final List<Integer> focused=new ArrayList<Integer>();

    void start(){state=State.WORKING;focused.clear();}
    void done(int status,int[] indexes){
        focused.clear();
        switch(status){
            case LOCK:case CONTINUOUS:
                state=State.LOCKED;
                if(indexes!=null)for(int i:indexes)focused.add(Integer.valueOf(i));
                break;
            case LOCK_WARN:state=State.FAILED;break;
            case WORKING:state=State.WORKING;break;
            default:state=State.NONE;
        }
    }
    void clear(){state=State.NONE;focused.clear();}
    /** The camera's AF frames; rects outside Sony's frame space are kept
     * for the log but never drawn (their coordinate system is unknown). */
    void areas(String focusAreaMode,List<int[]> frames){mode=focusAreaMode;areas.clear();if(frames!=null)areas.addAll(frames);}

    State state(){return state;}
    /** Frames to draw green, in -1000..1000 frame space, as
     * {left,top,right,bottom}. Empty when Sony did not say which frames
     * focused or their coordinates are not frame space. */
    List<int[]> lockedFrames(){
        List<int[]> out=new ArrayList<int[]>();
        if(state!=State.LOCKED)return out;
        // A6300 2026-11-13: in flex-spot Sony reports the frame that covers
        // the whole finder (index 1) as focused and the spot itself in
        // another rect format. The app's own AF box shows the spot instead.
        if(mode!=null&&mode.startsWith("flex-spot"))return out;
        for(int[] a:areas){
            if(!focused.contains(Integer.valueOf(a[0])))continue;
            if(!frameSpace(a)||container(a))continue;
            out.add(new int[]{a[1],a[2],a[3],a[4]});
        }
        return out;
    }
    /** Index 0 in every A6300 mode is the whole AF coverage; any frame
     * wider or taller than half the finder is an outline, not a focus point. */
    private static boolean container(int[] a){return a[3]-a[1]>LIMIT||a[4]-a[2]>LIMIT;}
    private static boolean frameSpace(int[] a){
        for(int i=1;i<5;i++)if(a[i]<-LIMIT||a[i]>LIMIT)return false;
        return a[3]>a[1]&&a[4]>a[2];
    }
}
