package org.hongen.sonylutprobe;

final class UiList {
    static int clamp(int index,int count){return count<=0?0:Math.max(0,Math.min(count-1,index));}
    static int move(int index,int delta,int count){return clamp(index+delta,count);}
    static int pageStart(int selected,int count,int pageSize){
        if(pageSize<1)throw new IllegalArgumentException("page size");
        return clamp(selected,count)/pageSize*pageSize;
    }
}
final class PreviewRequest {
    private long generation;
    synchronized long next(){return ++generation;}
    synchronized boolean current(long token){return token==generation;}
}

/** User-facing pipeline milestones. Mutated only on the UI thread. */
final class CaptureProgress {
    enum Stage { CAPTURE, DEVELOP, GRADE, SAVE }
    private long shot;
    private Stage stage=Stage.CAPTURE;
    void begin(long nextShot){shot=nextShot;stage=Stage.CAPTURE;}
    Stage stage(){return stage;}
    boolean advance(long eventShot,Stage next){
        if(eventShot!=shot||next.ordinal()<stage.ordinal())return false;
        stage=next;return true;
    }
}
