package org.hongen.sonylutprobe;

/** Absence selects the explicit original-colour view; no hardware is opened. */
final class NativePreviewCapability {
    static boolean available(){
        try{
            ClassLoader loader=NativePreviewCapability.class.getClassLoader();
            Class<?> camera=Class.forName("com.sony.scalar.hardware.CameraEx",false,loader);
            Class<?> gamma=Class.forName("com.sony.scalar.hardware.CameraEx$GammaTable",false,loader);
            Class<?> modifier=Class.forName("com.sony.scalar.hardware.CameraEx$ParametersModifier",false,loader);
            camera.getMethod("createGammaTable");camera.getMethod("getExtendedGammaTable");camera.getMethod("setExtendedGammaTable",gamma);
            modifier.getMethod("setRGBMatrix",int[].class);
            gamma.getMethod("getSize");gamma.getMethod("write",java.io.InputStream.class);gamma.getMethod("read",byte[].class);
            gamma.getMethod("release");gamma.getMethod("setPictureEffectGammaForceOff",boolean.class);
            return true;
        }catch(ClassNotFoundException absent){return false;}
        catch(NoSuchMethodException absent){return false;}
    }
}
