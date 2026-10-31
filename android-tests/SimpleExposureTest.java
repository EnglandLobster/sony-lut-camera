package org.hongen.sonylutprobe;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SimpleExposureTest {
    static class Fake implements SimpleExposure.Driver {
        final Map<String,String> values=new LinkedHashMap<String,String>();
        boolean rejectNext;
        Fake(){
            String[] pairs={"scene-mode-values=program-auto,manual-exposure,aperture-priority,shutter-priority",
                "iso-sensitivity-values=0,100,200,800","metering-mode-values=multi,center-spot",
                "scene-mode=manual-exposure","iso-sensitivity=800","metering-mode=center-spot",
                "exposure-compensation=2","min-exposure-compensation=-6","max-exposure-compensation=6",
                "exposure-compensation-step=0.33333","aperture=560","shutter-speed-nume=1","shutter-speed-deno=125",
                "auto-exposure-lock=true","jpeg-quality=95","picture-size=6000x4000","whitebalance=daylight"};
            for(String item:pairs){String[] p=item.split("=");values.put(p[0],p[1]);}
        }
        public Map<String,String> read(){return new LinkedHashMap<String,String>(values);}
        public void write(Map<String,String> p){
            if(p.containsKey("scene-mode")&&!p.get("scene-mode").equals(values.get("scene-mode"))){
                values.put("iso-sensitivity","100");values.put("exposure-compensation","0");
            }
            if(rejectNext){rejectNext=false;return;}
            values.putAll(p);
        }
    }
    static void check(boolean yes,String message){if(!yes)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{
        Fake f=new Fake();Map<String,String> before=f.read();SimpleExposure e=new SimpleExposure(f);e.open();
        check(f.values.get("scene-mode").equals("program-auto")&&f.values.get("iso-sensitivity").equals("0"),"P and auto ISO");
        check(f.values.get("auto-exposure-lock").equals("false"),"unlock metering");
        e.adjust(1,false);check(e.label().equals("亮度 +0.3 EV"),"native compensation step");
        for(int i=0;i<20;i++)e.adjust(1,false);check(f.values.get("exposure-compensation").equals("6"),"upper bound");
        for(int i=0;i<30;i++)e.adjust(-1,false);check(f.values.get("exposure-compensation").equals("-6"),"lower bound");
        e.adjust(0,true);check(e.label().equals("亮度 +0.0 EV"),"reset");
        f.rejectNext=true;boolean rejected=false;try{e.adjust(1,false);}catch(Exception expected){rejected=true;}
        check(rejected&&e.label().equals("亮度 +0.0 EV"),"readback rejects ignored adjustment");
        e.close();check(before.equals(f.values),"manual state restored and quality/WB untouched");e.close();
        Fake unsupported=new Fake();unsupported.values.put("iso-sensitivity-values","100,200");before=unsupported.read();
        rejected=false;try{new SimpleExposure(unsupported).open();}catch(Exception expected){rejected=true;}
        check(rejected&&before.equals(unsupported.values),"unsupported auto ISO causes no writes");
        Fake partial=new Fake();partial.rejectNext=true;before=partial.read();SimpleExposure rollback=new SimpleExposure(partial);
        rejected=false;try{rollback.open();}catch(Exception expected){rejected=true;}
        check(rejected,"ignored mode change rejected");rollback.close();check(before.equals(partial.values),"failed init rollback");
        Fake auto=new Fake();auto.values.put("scene-mode","program-auto");auto.values.put("iso-sensitivity","0");
        SimpleExposure a=new SimpleExposure(auto);a.open();auto.values.put("aperture","800");a.close();
        check(auto.values.get("aperture").equals("800"),"do not restore automatic metering result");
        System.out.println("SimpleExposureTest passed: auto setup, limits, readback, rollback, restoration, image parameters preserved");
    }
}
