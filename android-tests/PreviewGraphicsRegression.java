package org.hongen.sonylutprobe;

/** The same real-graphics suite must expose rc.3's defect and pass the fix. */
public final class PreviewGraphicsRegression {
    public static void main(String[] args){
        boolean baseline=args.length==1&&args[0].equals("baseline");
        try{
            if(android.os.Build.VERSION.SDK_INT!=10||Runtime.getRuntime().maxMemory()!=24L*1024*1024)
                throw new AssertionError("requires real API10 / 24 MiB runtime");
            System.out.println("sdk=10 heap="+Runtime.getRuntime().maxMemory()+" baseline="+baseline);
            DevelopingPreviewTest.main(new String[0]);
            if(baseline)throw new AssertionError("rc.3 defect was not reproduced");
            System.out.println("PASS_FIXED_GRAPHICS");
        }catch(Throwable error){
            error.printStackTrace(System.out);
            if(baseline&&error instanceof NullPointerException){
                for(StackTraceElement frame:error.getStackTrace())if(frame.getClassName().equals("android.graphics.Canvas")&&frame.getMethodName().equals("setBitmap")){
                    System.out.println("PASS_BASELINE_CANVAS_NULL_REPRODUCED");return;
                }
            }
            System.exit(1);
        }
    }
}
