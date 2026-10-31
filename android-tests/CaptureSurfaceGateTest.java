package org.hongen.sonylutprobe;

public final class CaptureSurfaceGateTest {
    static void check(boolean value,String text){if(!value)throw new AssertionError(text);}
    public static void main(String[] args){
        CaptureSurfaceGate before=new CaptureSurfaceGate();
        check(before.waiting(true,true,false),"prepared capture waits for lifecycle callback");
        check(!before.claim(true,true,false,true),"no camera open before callback");
        // Reproduce the reported A6000 combination: callbacks arrive while
        // Surface.isValid stays false. The old extra prerequisite blocks it.
        boolean reportedValid=false;before.created();before.changed();
        check(!reportedValid,"old isValid prerequisite fails in reported fixture");
        check(before.claim(true,true,false,true),"callback authorizes opening despite false Android flag");
        before.changed();check(!before.claim(true,true,false,true),"duplicate callbacks cannot reopen camera");
        CaptureSurfaceGate early=new CaptureSurfaceGate();early.created();
        check(!early.claim(false,false,false,true),"callback before resume/prepare waits");
        check(!early.claim(true,false,false,true),"resume before prepare waits");
        check(early.claim(true,true,false,true),"prepare completion opens an existing surface");
        CaptureSurfaceGate lost=new CaptureSurfaceGate();lost.created();lost.destroyed();
        check(!lost.claim(true,true,false,true)&&lost.waiting(true,true,false),"destroyed surface is not usable");
        lost.changed();check(!lost.claim(true,true,true,true),"closed session remains closed");
        check(!lost.claim(true,true,false,false),"holder still required");
        check(lost.claim(true,true,false,true),"recreated surface before first open works");
        check(!new CaptureSurfaceGate().waiting(false,true,false),"paused session must not time out");
        System.out.println("PASS callback-based surface opening: false isValid fixture, callback order, destruction, pause, close and duplicate callbacks");
    }
}
