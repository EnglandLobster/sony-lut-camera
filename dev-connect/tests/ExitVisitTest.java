package org.hongen.sonydevconnect;

public final class ExitVisitTest {
    private static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    public static void main(String[] args){
        ExitVisit s=new ExitVisit();
        check(!s.enter(false)&&!s.exiting(),"initial entry is idle");
        long first=s.begin();check(s.begin()==-1,"duplicate Menu must not queue cleanup");
        check(!s.enter(false)&&s.exiting(),"incidental resume cannot abort cleanup");
        check(s.cleaned(first)==ExitVisit.Completion.RETURN_TO_CAMERA,"cleanup precedes native exit");
        check(s.waitingForNative(first),"foreground native wait is observable");
        s.leftForeground();check(!s.waitingForNative(first),"no timeout recovery in native camera");
        check(s.enter(false)&&!s.exiting(),"same Activity pause/resume clears exit state");
        check(!s.fail(first)&&s.cleaned(first)==ExitVisit.Completion.STALE,"old completion cannot affect new visit");

        long second=s.begin();s.leftForeground();
        check(!s.enter(true)&&s.exiting(),"explicit reopen waits for cleanup to finish");
        check(s.cleaned(second)==ExitVisit.Completion.RECONNECT&&!s.exiting(),"reopen skips obsolete native exit");
        check(!s.current(second),"old native request invalidated");

        long third=s.begin();check(s.cleaned(third)==ExitVisit.Completion.RETURN_TO_CAMERA,"next exit works");
        check(s.enter(true)&&!s.exiting(),"singleTask onNewIntent recovers even without a pause callback");
        check(!s.fail(third),"stale timeout ignored");
        long fourth=s.begin();check(s.fail(fourth)&&!s.exiting(),"cleanup failure remains retryable");
        long fifth=s.begin();s.cleaned(fifth);check(s.fail(fifth),"native exit failure remains retryable");
        long sixth=s.begin();s.destroy();check(s.cleaned(sixth)==ExitVisit.Completion.STALE,"destroy invalidates worker");
        System.out.println("PASS retained Activity resume/newIntent, cleanup-before-reconnect, repeated exits, stale callbacks and failure recovery");
    }
}
