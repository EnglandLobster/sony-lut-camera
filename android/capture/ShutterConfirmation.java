package io.github.englandlobster.lutcamera;

/** A full press arms the capture before the camera confirms it, so that
 * releasing the button cannot return to the coloured preview mid-exposure.
 * The key event can wait behind the preview restore on the UI thread: the
 * press then reached the camera while its release was still locked, nothing
 * is exposed, and no shutter notice follows (ILCE-6300 2.01, 2026-11-16:
 * three of six quick full presses). The armed attempt is withdrawn once a
 * real exposure would have reported. */
final class ShutterConfirmation {
    private ShutterConfirmation(){}
    static final long MARGIN_MS=1000,LONGEST_EXPOSURE_MS=30000;
    /** Time to wait after the full press is released before treating the
     * press as not taken, or -1 when the exposure time is unknown (bulb, no
     * readout): then the attempt is left alone.
     * The exposure counts twice: long-exposure noise reduction repeats it. */
    static long waitMs(int shutterNumerator,int shutterDenominator){
        if(shutterNumerator<=0||shutterDenominator<=0)return -1;
        long exposure=1000L*shutterNumerator/shutterDenominator;
        if(exposure>LONGEST_EXPOSURE_MS)return -1;
        return 2*exposure+MARGIN_MS;
    }
}
