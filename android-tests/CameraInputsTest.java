package io.github.englandlobster.lutcamera;

import java.util.Arrays;
import com.sony.scalar.sysutil.ScalarProperties;

public final class CameraInputsTest {
    static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        check(Boolean.TRUE.equals(CameraInputs.has(ControlLayout.MODE_DIAL)),"fixture exposes a valid logical mode key");
        check(CameraInputs.dialPosition()==539,"fixture even exposes a valid A mode position");
        ScalarProperties.positions=null;
        check(Boolean.FALSE.equals(CameraInputs.modeDial()),"logical mode status does not hide software mode selection");
        ManualExposureTest.Camera camera=new ManualExposureTest.Camera("program-auto");
        boolean physical=Boolean.TRUE.equals(CameraInputs.modeDial());
        ManualExposure exposure=new ManualExposure(camera,null);exposure.open(new ManualExposure.Memory(),physical);
        ViewfinderControls controls=new ViewfinderControls(exposure,null,new ControlLayout(null),physical,new ViewfinderControlsTest.Host());
        controls.showSettings();
        check(Arrays.asList(controls.rowLabels()).contains("曝光模式"),"mode row is visible despite key 572 being valid");
        controls.press(UiKeyActions.OK);
        check(Arrays.asList(controls.rowLabels()).equals(Arrays.asList("P 程序自动","A 光圈优先","S 快门优先","M 手动曝光")),"all advertised PASM modes reachable");
        controls.press(UiKeyActions.DOWN);controls.press(UiKeyActions.DOWN);controls.press(UiKeyActions.DOWN);controls.press(UiKeyActions.OK);
        check(exposure.mode()==ManualExposure.Mode.M&&"manual-exposure".equals(camera.values.get("scene-mode")),"menu selects M on a no-dial body");
        exposure.close();check("program-auto".equals(camera.values.get("scene-mode")),"software-selected mode restores on exit");
        ScalarProperties.positions=new int[0];
        check(Boolean.FALSE.equals(CameraInputs.modeDial()),"empty mode-position list is also no dial");
        ScalarProperties.positions=new int[]{537,538,539,540};
        check(Boolean.TRUE.equals(CameraInputs.modeDial()),"declared physical PASM dial remains supported");
        controls.physicalDial(true);controls.showSettings();
        check(!Arrays.asList(controls.rowLabels()).contains("曝光模式"),"physical dial retains mode ownership");
        ScalarProperties.unavailable=true;
        check(CameraInputs.modeDial()==null,"unavailable property leaves hardware unknown, not inferred from logical key");
        System.out.println("PASS camera input capability: valid logical mode without dial, software PASM selection/restore, physical dial and unknown property");
    }
}
