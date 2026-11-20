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
        // Recorded on the A6300 2026-11-20: a real AF run names its frames;
        // a half-press on the same subject brings no start and two locks on
        // the whole area. The frames stay, like Sony's own finder.
        List<int[]> grid=new ArrayList<int[]>();
        grid.add(new int[]{0,-784,-819,785,820});grid.add(new int[]{7,-468,-490,-160,-168});grid.add(new int[]{8,-153,-490,154,-168});
        f.areas("multi",grid);
        f.start();f.done(FocusFeedback.LOCK,new int[]{7,8});check(f.lockedFrames().size()==2,"real run frames");
        f.done(FocusFeedback.CLEAR,new int[]{0});check(f.state()==FocusFeedback.State.NONE&&f.lockedFrames().isEmpty(),"release hides frames");
        f.done(FocusFeedback.LOCK,new int[]{0});f.done(FocusFeedback.LOCK,new int[]{0});
        check(f.lockedFrames().size()==2&&f.lockedFrames().get(0)[0]==-468,"already in focus keeps last frames");
        f.done(FocusFeedback.CLEAR,new int[]{0});f.clear();
        f.done(FocusFeedback.LOCK,new int[]{0});check(f.lockedFrames().size()==2,"kept across a shot");
        f.done(FocusFeedback.CONTINUOUS,new int[]{0});check(f.lockedFrames().isEmpty(),"AF-C never reuses frames");
        f.start();f.done(FocusFeedback.LOCK,new int[]{0});check(f.lockedFrames().isEmpty(),"a run that names no frame drops them");
        f.done(FocusFeedback.LOCK,new int[]{0});check(f.lockedFrames().isEmpty(),"dropped frames stay dropped");
        f.start();f.done(FocusFeedback.LOCK,new int[]{8});f.areas("multi",grid);
        f.done(FocusFeedback.LOCK,new int[]{0});check(f.lockedFrames().isEmpty(),"new AF area forgets frames");
        f.areas("flex-spot",flex);f.start();f.done(FocusFeedback.LOCK,new int[]{2});f.done(FocusFeedback.LOCK,new int[]{1});
        check(f.lockedFrames().isEmpty(),"flex-spot never holds frames");
        System.out.println("FocusFeedbackTest passed: lock, AF-C, miss, release, frame-space filter, held frames");
    }
}
