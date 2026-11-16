package io.github.englandlobster.lutcamera;

/** The camera's own picture review ("自动检视" in the camera menu).
 * After a capture the firmware holds the shot for the review time the user set
 * (2, 5 or 10 s) unless the app cancels it, as Sony's shooting apps do when
 * leaving their review state. This app shows its own result, so the review is
 * switched off for the session and the user's time is put back on close. */
final class CaptureAutoReview {
    interface Driver {
        int time()throws Exception;
        void set(int seconds)throws Exception;
    }
    private final Driver driver;
    private int original=-1;
    CaptureAutoReview(Driver driver){this.driver=driver;}
    /** The camera's review time in seconds; a positive one is switched off. */
    int disable()throws Exception{
        int seconds=driver.time();
        if(seconds>0&&original<0){driver.set(0);original=seconds;}
        return seconds;
    }
    boolean changed(){return original>0;}
    /** Put the user's review time back. Returns false when nothing was changed. */
    boolean restore()throws Exception{
        if(original<=0)return false;
        int seconds=original;original=-1;driver.set(seconds);return true;
    }
    /** Sony's control object (CameraEx.AutoPictureReviewControl), attached to an open CameraEx. */
    static Driver camera(Class<?> cameraType,final Object cameraEx)throws Exception{
        final Class<?> type=Class.forName(cameraType.getName()+"$AutoPictureReviewControl",true,cameraType.getClassLoader());
        final Object control=type.newInstance();
        cameraType.getMethod("setAutoPictureReviewControl",type).invoke(cameraEx,control);
        return new Driver(){
            public int time()throws Exception{return (Integer)type.getMethod("getPictureReviewTime").invoke(control);}
            public void set(int seconds)throws Exception{type.getMethod("setPictureReviewTime",int.class).invoke(control,seconds);}
        };
    }
}
