package org.hongen.sonylutprobe;
public final class DevelopTimelineTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        DevelopTimeline t=new DevelopTimeline();t.begin(1,true);
        check(!t.hasSource()&&!t.moving(1000)&&t.reveal(1000)==0,"wait for real source");
        check(!t.graded(1,1000),"cannot reveal without source");
        check(t.source(1,1000),"accept current shot");
        check(!t.source(1,1500),"duplicate source rejected");
        check(!t.moving(10000)&&t.reveal(10000)==0,"no animation work while waiting for grade");
        check(!t.graded(0,10000)&&t.graded(1,10000),"only current grade starts reveal");
        check(!t.graded(1,10500),"duplicate cannot restart animation");
        float prior=0;for(long now=10000;now<20000;now+=17){float value=t.reveal(now);check(value>=prior&&value<=1,"one-way bounded reveal");prior=value;}
        check(t.reveal(11000)==1&&!t.moving(11000)&&t.reveal(100000)==1,"finish without looping");
        t.end();check(!t.accepts(1)&&!t.hasSource()&&!t.source(1,12000)&&!t.graded(1,12000),"completion rejects late thumbnails");
        t.begin(2,true);check(!t.source(1,13000)&&t.source(2,13000)&&t.reveal(13000)==0,"new shot cannot inherit old image or time");
        t.graded(2,13000);t.end();check(!t.moving(13001),"save completion interrupts animation immediately");
        t.begin(3,false);check(!t.source(3,14000)&&!t.accepts(3)&&!t.moving(14000),"disabled A/B mode");
        check(DevelopTimeline.fraction(-100)==0,"negative time bounded");
        System.out.println("PASS static negative until grade, monotonic reveal, immediate stop, stale/duplicate rejection, disabled mode");
    }
}
