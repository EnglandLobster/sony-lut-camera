package org.hongen.sonylutprobe;
import android.view.KeyEvent;
final class UiKeys {
    static final int NONE=0,UP=1,DOWN=2,LEFT=3,RIGHT=4,OK=5,BACK=6,PLAY=7;
    // Sony AppRoot.USER_KEYCODE: DIAL1 and DIAL2 left/right. These are
    // incremental controls, distinct from the physical P/A/S/M mode dial.
    static int dial(KeyEvent e){int k=e.getKeyCode(),s=e.getScanCode();
        if(k==526||k==529||s==526||s==529)return -1;
        if(k==525||k==528||s==525||s==528)return 1;
        return 0;
    }
    static int key(KeyEvent e){int k=e.getKeyCode(),s=e.getScanCode();
        if(k==207||s==207)return PLAY;
        if(k==KeyEvent.KEYCODE_DPAD_UP||s==103)return UP;
        if(k==KeyEvent.KEYCODE_DPAD_DOWN||s==108)return DOWN;
        if(k==KeyEvent.KEYCODE_DPAD_LEFT||s==105)return LEFT;
        if(k==KeyEvent.KEYCODE_DPAD_RIGHT||s==106)return RIGHT;
        if(k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER||s==232)return OK;
        if(k==KeyEvent.KEYCODE_BACK||k==KeyEvent.KEYCODE_MENU||s==514)return BACK;
        return NONE;
    }
    static boolean shutter(KeyEvent e){int k=e.getKeyCode(),s=e.getScanCode();
        return s==516||s==517||s==518||k==516||k==517||k==518||k==KeyEvent.KEYCODE_CAMERA;
    }
}
