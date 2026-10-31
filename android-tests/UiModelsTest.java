package org.hongen.sonylutprobe;

public final class UiModelsTest {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        require(UiList.clamp(9,3)==2&&UiList.clamp(-1,3)==0&&UiList.clamp(0,0)==0,"selection bounds");
        require(UiList.move(1,1,3)==2&&UiList.move(2,1,3)==2&&UiList.move(0,-1,3)==0,"direction and boundary");
        require(UiList.pageStart(0,9,4)==0&&UiList.pageStart(3,9,4)==0&&UiList.pageStart(4,9,4)==4&&UiList.pageStart(8,9,4)==8,"paged focus");
        PreviewRequest requests=new PreviewRequest();long first=requests.next(),second=requests.next();
        require(!requests.current(first)&&requests.current(second),"stale preview must not replace current selection");
        CaptureProgress progress=new CaptureProgress();progress.begin(1);
        require(progress.stage()==CaptureProgress.Stage.CAPTURE,"new shot starts at capture");
        require(progress.advance(1,CaptureProgress.Stage.DEVELOP),"develop begins");
        require(progress.advance(1,CaptureProgress.Stage.GRADE),"original preservation prepares grading");
        require(progress.advance(1,CaptureProgress.Stage.GRADE),"LUT keeps grading milestone");
        require(progress.advance(1,CaptureProgress.Stage.SAVE),"graded output enters final save");
        require(!progress.advance(1,CaptureProgress.Stage.GRADE)&&progress.stage()==CaptureProgress.Stage.SAVE,"late update cannot regress");
        progress.begin(2);
        require(progress.stage()==CaptureProgress.Stage.CAPTURE,"next shot resets progress");
        require(!progress.advance(1,CaptureProgress.Stage.SAVE)&&progress.stage()==CaptureProgress.Stage.CAPTURE,"old shot cannot advance next shot");
        require(progress.advance(2,CaptureProgress.Stage.SAVE),"non-LUT diagnostic can save directly");
        System.out.println("PASS capture milestones, overlap preparation, monotonic updates, next-shot reset and stale-shot rejection");
        System.out.println("PASS list navigation, visible pages and stale preview rejection");
    }
}
