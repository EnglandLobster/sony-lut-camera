package io.github.englandlobster.lutcamera;

import java.util.ArrayList;
import java.util.List;

public final class FocusFeedbackTest {
    static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    public static void main(String[] args){
        FocusFeedback f=new FocusFeedback();
        check(f.state()==FocusFeedback.State.NONE,"idle");
        List<int[]> frames=new ArrayList<int[]>();
        frames.add(new int[]{3,-100,-100,100,100});
        frames.add(new int[]{4,200,-50,400,150});
        frames.add(new int[]{5,0,0,1920,1080}); // not frame space: never drawn
        f.areas("multi",frames);
        f.start();check(f.state()==FocusFeedback.State.WORKING&&f.lockedFrames().isEmpty(),"working");
        f.done(FocusFeedback.LOCK,new int[]{4,5});
        List<int[]> locked=f.lockedFrames();
        check(f.state()==FocusFeedback.State.LOCKED&&locked.size()==1&&locked.get(0)[0]==200&&locked.get(0)[3]==150,"locked frame 4 only");
        f.done(FocusFeedback.CONTINUOUS,null);check(f.state()==FocusFeedback.State.LOCKED&&f.lockedFrames().isEmpty(),"AF-C in focus is green without frames");
        f.done(FocusFeedback.LOCK_WARN,null);check(f.state()==FocusFeedback.State.FAILED,"miss");
        f.done(FocusFeedback.CLEAR,null);check(f.state()==FocusFeedback.State.NONE,"released");
        // Recorded on the A6300 2026-11-13: whole-area outline (0), wide
        // zones, centre frame, and the flex-spot outline reported as focused.
        List<int[]> wide=new ArrayList<int[]>();
        wide.add(new int[]{0,-784,-819,785,820});wide.add(new int[]{7,-468,-490,-160,-170});
        f.areas("multi",wide);f.done(FocusFeedback.LOCK,new int[]{0});
        check(f.state()==FocusFeedback.State.LOCKED&&f.lockedFrames().isEmpty(),"whole-area outline never drawn");
        f.done(FocusFeedback.LOCK,new int[]{0,7});check(f.lockedFrames().size()==1&&f.lockedFrames().get(0)[0]==-468,"wide zone drawn");
        List<int[]> center=new ArrayList<int[]>();center.add(new int[]{0,-784,-819,785,820});center.add(new int[]{1,-153,-164,147,173});
        f.areas("center",center);f.done(FocusFeedback.LOCK,new int[]{1});check(f.lockedFrames().size()==1,"centre frame drawn");
        List<int[]> flex=new ArrayList<int[]>();flex.add(new int[]{0,-784,-819,785,820});flex.add(new int[]{1,-854,-828,855,829});flex.add(new int[]{2,2,2,206,248});
        f.areas("flex-spot",flex);f.done(FocusFeedback.LOCK,new int[]{1});
        check(f.state()==FocusFeedback.State.LOCKED&&f.lockedFrames().isEmpty(),"flex-spot: own AF box only, no outline");
        f.done(FocusFeedback.LOCK,new int[]{3});f.clear();check(f.state()==FocusFeedback.State.NONE&&f.lockedFrames().isEmpty(),"cleared");
        System.out.println("FocusFeedbackTest passed: lock, AF-C, miss, release, frame-space filter");
    }
}
