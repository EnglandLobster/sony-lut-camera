package io.github.englandlobster.lutcamera;

import java.util.*;

public final class CaptureAutoReviewTest {
    private static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    private static final class Camera implements CaptureAutoReview.Driver {
        int seconds;boolean refuse;final List<Integer> writes=new ArrayList<Integer>();
        Camera(int seconds){this.seconds=seconds;}
        public int time(){return seconds;}
        public void set(int value)throws Exception{
            if(refuse)throw new IllegalStateException("camera refused");
            writes.add(value);seconds=value;
        }
    }
    /** Sony's public shape: the control is attached to the camera, then read and written. */
    public static final class FakeCameraEx {
        public static final class AutoPictureReviewControl {
            public AutoPictureReviewControl(){}
            int seconds=5;
            public int getPictureReviewTime(){return seconds;}
            public void setPictureReviewTime(int value){seconds=value;}
        }
        AutoPictureReviewControl attached;
        public void setAutoPictureReviewControl(AutoPictureReviewControl control){attached=control;}
    }
    public static void main(String[] args)throws Exception{
        Camera camera=new Camera(10);CaptureAutoReview review=new CaptureAutoReview(camera);
        check(review.disable()==10&&review.changed()&&camera.seconds==0,"10 s review switched off");
        check(review.disable()==0&&camera.writes.size()==1,"a second call keeps the user's time, not 0");
        check(review.restore()&&camera.seconds==10&&!review.changed(),"user's time restored");
        check(!review.restore()&&camera.writes.equals(Arrays.asList(0,10)),"restored once");

        camera=new Camera(0);review=new CaptureAutoReview(camera);
        check(review.disable()==0&&!review.changed()&&!review.restore()&&camera.writes.isEmpty(),"review already off: camera untouched");

        camera=new Camera(2);camera.refuse=true;review=new CaptureAutoReview(camera);
        boolean failed=false;try{review.disable();}catch(IllegalStateException expected){failed=true;}
        check(failed&&!review.changed()&&!review.restore()&&camera.seconds==2,"refused write leaves nothing to restore");

        // The reflection adapter used on the camera, against Sony's public shape.
        FakeCameraEx sony=new FakeCameraEx();
        review=new CaptureAutoReview(CaptureAutoReview.camera(FakeCameraEx.class,sony));
        check(sony.attached!=null,"control attached to the camera");
        check(review.disable()==5&&sony.attached.seconds==0,"attached control read and switched off");
        check(review.restore()&&sony.attached.seconds==5,"attached control restored");
        System.out.println("PASS camera picture review: switched off for the session, user's time restored once, untouched when already off or refused; Sony control attached by reflection");
    }
}
