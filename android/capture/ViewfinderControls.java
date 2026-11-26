package io.github.englandlobster.lutcamera;

import java.util.ArrayList;
import java.util.List;

/** Viewfinder key routing and list state for the manual controls. Pure: the
 * Activity decides when controls may run (capture gate, preview state) and
 * forwards rotary inputs and UiKeys actions here.
 *
 * Live view: dials follow {@link ControlLayout}; → ISO list, ← white
 * balance list, ↓ focus/shooting settings, centre resets EV (or switches the
 * shared function on one-dial bodies). Lists: ↑↓/dials move, ←→ change a
 * setting row, centre applies, MENU closes. The ISO and white balance lists
 * preview the highlighted value on the camera while moving: centre keeps it,
 * MENU returns to the value the list opened with. Every function is reachable
 * with keys all supported bodies have (wheel, four-way, centre, MENU). */
final class ViewfinderControls {
    interface Host {
        void changed();
        void hint(String text);
        void failed(String where,Exception error);
        /** A verified WB write: the preview baseline must follow it. */
        void whiteBalanceChanged();
        /** The ISO/WB highlight moved: call {@link #applySelection()} now or
         * once input pauses, so a held key does not queue a write per row. */
        void selectionMoved();
        /** The composition grid changed: drawing and memory only. */
        void gridChanged(CompositionGrid grid);
    }
    /** CHOICE is the option list of one settings row (area, face, mode). */
    enum Panel { NONE, ISO, WB, SETTINGS, CHOICE, POINT, ADJUST, NATIVE }
    private enum Row { POINT, AREA, FACE, MODE, GRID, APERTURE, SHUTTER, EV, ISO, WB, EXTRA }
    interface Selection { void apply(String value)throws Exception; }
    interface Additional { String[] labels();void select(int index)throws Exception; }
    private Additional additional;
    private Selection nativeSelection;
    private String nativeTitle;
    private String[] nativeLabels;
    private ControlLayout.Action adjustment;
    private boolean nativeBindings;
    void additional(Additional value){additional=value;}
    void nativeBindings(boolean value){nativeBindings=value;}
    void showSettings(){open(Panel.SETTINGS);}
    void showIso(){open(Panel.ISO);}
    void showWhiteBalance(){open(Panel.WB);}
    void showMode(){openChoice(Row.MODE);host.changed();}
    void showFocusArea(){if(focus!=null){openChoice(Row.AREA);host.changed();}}
    void showFace(){if(focus!=null){openChoice(Row.FACE);host.changed();}}
    void focusStandard(){
        if(focus!=null&&focus.flexible()&&focus.pointAvailable()){focus.beginPoint();panel=Panel.POINT;host.changed();}
    }
    void selectMode(ManualExposure.Mode mode){
        try{exposure.selectMode(mode);alternate=false;host.whiteBalanceChanged();host.changed();}
        catch(Exception e){host.failed("mode_key",e);}
    }
    void showAdjust(ControlLayout.Action action){adjustment=action;selected=0;panel=Panel.ADJUST;host.changed();}
    void showNative(String title,List<String> values,String current,Selection selection){
        choices.clear();choices.addAll(values);nativeTitle=title;nativeSelection=selection;nativeLabels=null;
        selected=Math.max(0,choices.indexOf(current));panel=Panel.NATIVE;host.changed();
    }
    void nativeLabels(String[] labels){nativeLabels=labels;host.changed();}
    void step(ControlLayout.Action action,int direction)throws Exception{
        if(action==ControlLayout.Action.EV&&exposure.evDialOwnsValue())host.hint("请使用机身曝光补偿转盘");
        else exposure.step(action,direction);
        host.changed();
    }
    void toggleShared(){alternate=!alternate;host.changed();}
    void stepShared(int direction)throws Exception{
        step(alternate?ControlLayout.Action.APERTURE:ControlLayout.Action.SHUTTER,direction);
    }

    private final ManualExposure exposure;
    private final FocusControls focus;
    private final ControlLayout layout;
    private boolean physicalDial;
    private final Host host;
    private Panel panel=Panel.NONE;
    private int selected;
    /** Value in effect when the ISO/WB list opened; MENU returns to it. */
    private int isoBefore;private String wbBefore;
    private boolean alternate;
    private final List<Row> rows=new ArrayList<Row>();
    private Row choiceRow;private int settingsSelected;
    private final List<String> choices=new ArrayList<String>();
    private CompositionGrid grid=CompositionGrid.OFF;

    ViewfinderControls(ManualExposure exposure,FocusControls focus,ControlLayout layout,boolean physicalDial,Host host){
        this.exposure=exposure;this.focus=focus;this.layout=layout;this.physicalDial=physicalDial;this.host=host;
    }

    /** A PASM dial event arrived on a body whose probe did not report one. */
    void physicalDial(boolean value){physicalDial=value;}
    boolean modeSelectable(){return !physicalDial;}
    /** Remembered grid; set once after construction. */
    void grid(CompositionGrid value){grid=value==null?CompositionGrid.OFF:value;}
    CompositionGrid grid(){return grid;}
    private void setGrid(CompositionGrid value){if(value!=grid){grid=value;host.gridChanged(value);}}
    Panel panel(){return panel;}
    boolean panelOpen(){return panel!=Panel.NONE;}
    void closePanel(){
        if(panel==Panel.POINT&&focus!=null)focus.cancelPoint();
        if(panel!=Panel.NONE){panel=Panel.NONE;host.changed();}
    }

    // ---- inputs -------------------------------------------------------------------
    /** One rotary detent (or a coalesced burst). */
    void rotate(ControlLayout.Input input,int steps){
        if(steps==0)return;
        try{
            switch(panel){
                case ADJUST:step(adjustment,steps);return;
                case NONE:{
                    ControlLayout.Action action=layout.action(exposure.mode(),input,alternate);
                    boolean changed=exposure.step(action,steps);
                    if(!changed&&action==ControlLayout.Action.EV&&!exposure.evAvailable())host.hint("M 档固定 ISO 时不使用 EV");
                    host.changed();return;
                }
                case POINT:{
                    boolean horizontal=layout.role(input)==0;
                    if(focus.movePoint(horizontal?steps:0,horizontal?0:steps))host.changed();
                    return;
                }
                default:move(steps);
            }
        }catch(Exception e){host.failed("rotate_"+input,e);host.changed();}
    }
    /** @return true when consumed */
    boolean press(int action){
        try{
            switch(panel){
                case NONE:return live(action);
                case POINT:return point(action);
                case SETTINGS:return settings(action);
                case CHOICE:return choice(action);
                case ADJUST:
                    if(action==UiKeyActions.BACK||action==UiKeyActions.OK){closePanel();return true;}
                    if(action==UiKeyActions.UP||action==UiKeyActions.RIGHT)step(adjustment,1);
                    if(action==UiKeyActions.DOWN||action==UiKeyActions.LEFT)step(adjustment,-1);
                    return true;
                case NATIVE:
                    if(action==UiKeyActions.BACK){closePanel();return true;}
                    if(action==UiKeyActions.OK){String value=choices.get(selected);Selection callback=nativeSelection;
                        closePanel();callback.apply(value);host.changed();return true;}
                    if(action==UiKeyActions.UP||action==UiKeyActions.LEFT)move(-1);
                    if(action==UiKeyActions.DOWN||action==UiKeyActions.RIGHT)move(1);
                    return true;
                default:return list(action);
            }
        }catch(Exception e){
            // A failed mode/WB write may still have moved Sony's white
            // balance; the preview baseline must match the camera.
            host.whiteBalanceChanged();host.failed("key_"+panel,e);host.changed();return true;
        }
    }
    private boolean live(int action)throws Exception{
        if(action==UiKeyActions.RIGHT){open(Panel.ISO);return true;}
        if(action==UiKeyActions.LEFT){open(Panel.WB);return true;}
        if(action==UiKeyActions.DOWN){open(Panel.SETTINGS);return true;}
        if(action==UiKeyActions.OK){
            if(layout.shared(exposure.mode())){alternate=!alternate;host.hint(layout.hint(exposure.mode(),alternate));}
            else exposure.resetEv();
            host.changed();return true;
        }
        return false;
    }
    private void open(Panel next){
        if(next==Panel.SETTINGS)buildRows();
        panel=next;isoBefore=exposure.iso();wbBefore=exposure.whiteBalance();
        if(next==Panel.ISO)selected=Math.max(0,exposure.isoValues().indexOf(Integer.valueOf(exposure.iso())));
        else if(next==Panel.WB)selected=Math.max(0,exposure.whiteBalanceValues().indexOf(exposure.whiteBalance()));
        else selected=0;
        host.changed();
    }
    private void buildRows(){
        rows.clear();
        if(focus!=null&&focus.pointAvailable())rows.add(Row.POINT);
        if(focus!=null&&focus.areas().size()>1)rows.add(Row.AREA);
        if(focus!=null&&focus.faceAvailable())rows.add(Row.FACE);
        if(!physicalDial)rows.add(Row.MODE);
        rows.add(Row.GRID);
        if(additional!=null){
            if(exposure.mode()==ManualExposure.Mode.A||exposure.mode()==ManualExposure.Mode.M)rows.add(Row.APERTURE);
            if(exposure.mode()==ManualExposure.Mode.S||exposure.mode()==ManualExposure.Mode.M)rows.add(Row.SHUTTER);
            if(exposure.evAvailable())rows.add(Row.EV);
            rows.add(Row.ISO);rows.add(Row.WB);
            for(String ignored:additional.labels())rows.add(Row.EXTRA);
        }
    }
    private int size(){
        switch(panel){
            case ISO:return exposure.isoValues().size();
            case WB:return exposure.whiteBalanceValues().size();
            case SETTINGS:return rows.size();
            case CHOICE:case NATIVE:return choices.size();
            default:return 0;
        }
    }
    private void move(int steps){
        int count=size();if(count==0)return;
        int next=Math.max(0,Math.min(count-1,selected+steps));
        if(next!=selected){
            selected=next;host.changed();
            if(panel==Panel.ISO||panel==Panel.WB)host.selectionMoved();
        }
    }
    /** Live preview: write the highlighted ISO/WB value. A value the camera
     * refuses puts the highlight back on the one in effect. */
    void applySelection(){
        if(panel!=Panel.ISO&&panel!=Panel.WB)return;
        try{apply(panel,selected);}
        catch(Exception e){
            // A failed WB write may still have moved Sony's white balance.
            if(panel==Panel.WB)host.whiteBalanceChanged();
            selected=Math.max(0,panel==Panel.ISO?exposure.isoValues().indexOf(Integer.valueOf(exposure.iso()))
                :exposure.whiteBalanceValues().indexOf(exposure.whiteBalance()));
            host.failed("preview_"+panel,e);
        }
        host.changed();
    }
    private void apply(Panel list,int index)throws Exception{
        if(list==Panel.ISO)exposure.setIso(exposure.isoValues().get(index).intValue());
        else setWhiteBalance(exposure.whiteBalanceValues().get(index));
    }
    private void setWhiteBalance(String value)throws Exception{
        if(value.equals(exposure.whiteBalance()))return;
        exposure.setWhiteBalance(value);host.whiteBalanceChanged();
    }
    private boolean list(int action)throws Exception{
        if(action==UiKeyActions.UP||action==UiKeyActions.LEFT){move(-1);return true;}
        if(action==UiKeyActions.DOWN||action==UiKeyActions.RIGHT){move(1);return true;}
        if(action==UiKeyActions.BACK){
            // Cancel: undo whatever the live preview wrote.
            Panel was=panel;panel=Panel.NONE;
            if(was==Panel.ISO){if(exposure.isoValues().contains(Integer.valueOf(isoBefore)))exposure.setIso(isoBefore);}
            else if(wbBefore!=null&&exposure.whiteBalanceValues().contains(wbBefore))setWhiteBalance(wbBefore);
            host.changed();return true;
        }
        if(action==UiKeyActions.OK){
            // The highlight may be ahead of a preview write still pending.
            Panel was=panel;panel=Panel.NONE;apply(was,selected);
            host.changed();return true;
        }
        return true;
    }
    private boolean settings(int action)throws Exception{
        if(action==UiKeyActions.UP){move(-1);return true;}
        if(action==UiKeyActions.DOWN){move(1);return true;}
        if(action==UiKeyActions.BACK){closePanel();return true;}
        Row row=rows.get(selected);
        // Sony-style: centre opens the row (its option list, or the point
        // editor); left/right is the quick change without opening it.
        if(action==UiKeyActions.OK){
            if(row==Row.EXTRA){additional.select(selected-rows.indexOf(Row.EXTRA));return true;}
            else if(row==Row.ISO){showIso();return true;}
            else if(row==Row.WB){showWhiteBalance();return true;}
            else if(row==Row.APERTURE||row==Row.SHUTTER||row==Row.EV){
                showAdjust(row==Row.APERTURE?ControlLayout.Action.APERTURE:row==Row.SHUTTER?ControlLayout.Action.SHUTTER:ControlLayout.Action.EV);return true;
            }
            else if(row==Row.POINT){focus.beginPoint();panel=Panel.POINT;}
            else openChoice(row);
            host.changed();return true;
        }
        int direction=action==UiKeyActions.LEFT?-1:action==UiKeyActions.RIGHT?1:0;
        if(direction==0)return true;
        if(row==Row.AREA){
            List<String> areas=focus.areas();int i=areas.indexOf(focus.area());
            focus.setArea(areas.get(((i<0?0:i)+direction+areas.size())%areas.size()));
        }else if(row==Row.FACE)focus.setFace(!focus.face());
        else if(row==Row.GRID)setGrid(grid.step(direction));
        else if(row==Row.MODE){
            ManualExposure.Mode[] modes=ManualExposure.Mode.values();int i=exposure.mode().ordinal();
            for(int n=0;n<modes.length;n++){
                i=(i+direction+modes.length)%modes.length;
                if(exposure.supports(modes[i])){exposure.selectMode(modes[i]);break;}
            }
            alternate=false;host.whiteBalanceChanged();
        }
        host.changed();return true;
    }
    private void openChoice(Row row){
        choices.clear();String current;
        if(row==Row.AREA){choices.addAll(focus.areas());current=focus.area();}
        else if(row==Row.FACE){choices.add("off");choices.add("auto");current=focus.face()?"auto":"off";}
        else if(row==Row.GRID){for(CompositionGrid g:CompositionGrid.values())choices.add(g.key);current=grid.key;}
        else{
            for(ManualExposure.Mode m:ManualExposure.Mode.values())if(exposure.supports(m))choices.add(m.name());
            current=exposure.mode().name();
        }
        choiceRow=row;settingsSelected=selected;
        selected=Math.max(0,choices.indexOf(current));
        panel=Panel.CHOICE;
    }
    private void backToSettings(){buildRows();panel=Panel.SETTINGS;selected=Math.min(settingsSelected,Math.max(0,rows.size()-1));}
    private boolean choice(int action)throws Exception{
        if(action==UiKeyActions.UP||action==UiKeyActions.LEFT){move(-1);return true;}
        if(action==UiKeyActions.DOWN||action==UiKeyActions.RIGHT){move(1);return true;}
        if(action==UiKeyActions.BACK){backToSettings();host.changed();return true;}
        if(action!=UiKeyActions.OK)return true;
        String value=choices.get(selected);Row row=choiceRow;
        // Leave the list first: a refused value reports and shows settings.
        backToSettings();
        if(row==Row.AREA){
            focus.setArea(value);
            // Choosing a flexible spot goes straight to placing it.
            if(focus.flexible()&&focus.pointAvailable()){focus.beginPoint();panel=Panel.POINT;}
        }else if(row==Row.FACE)focus.setFace("auto".equals(value));
        else if(row==Row.GRID)setGrid(CompositionGrid.decode(value));
        else{
            exposure.selectMode(ManualExposure.Mode.valueOf(value));
            alternate=false;host.whiteBalanceChanged();
        }
        host.changed();return true;
    }
    private boolean point(int action)throws Exception{
        int dx=0,dy=0;
        if(action==UiKeyActions.UP)dy=-1;else if(action==UiKeyActions.DOWN)dy=1;
        else if(action==UiKeyActions.LEFT)dx=-1;else if(action==UiKeyActions.RIGHT)dx=1;
        if(dx!=0||dy!=0){if(focus.movePoint(dx,dy))host.changed();return true;}
        if(action==UiKeyActions.OK){focus.confirmPoint();panel=Panel.NONE;host.changed();return true;}
        if(action==UiKeyActions.BACK){focus.cancelPoint();buildRows();panel=Panel.SETTINGS;selected=Math.min(selected,Math.max(0,rows.size()-1));host.changed();return true;}
        return true;
    }

    /** Physical PASM dial moved.
     * @param position Sony ISV_KEY_MODE_* code, or -1 when unreadable (then
     * the camera's own scene-mode readback is followed) */
    void dialChanged(int position){
        try{
            boolean changed=position<0?exposure.sync()
                :exposure.follow(ControlLayout.dialMode(position),ControlLayout.dialName(position));
            alternate=false;host.whiteBalanceChanged();
            String fallback=exposure.fallbackFrom();
            if(fallback!=null)host.hint("转盘档位 "+fallback+" 不可用 · 按 P 档拍摄");
            else if(changed)host.hint(modeName()+" · "+layout.hint(exposure.mode(),false));
        }catch(Exception e){host.whiteBalanceChanged();host.failed("dial_sync",e);}
        host.changed();
    }
    /** Full press or half press: lists never cover a shot. An ISO/WB value
     * already previewed stays in effect. */
    void shutter(){
        if(panel==Panel.POINT){
            // A shot never waits behind the editor: a refused point is dropped.
            try{focus.confirmPoint();}catch(Exception e){focus.cancelPoint();host.failed("af_point",e);}
            panel=Panel.NONE;host.changed();
        }
        else closePanel();
    }

    // ---- HUD model --------------------------------------------------------------
    String modeLabel(){return exposure.modeLabel();}
    private static String rowName(Row r){
        if(r==Row.APERTURE)return "光圈";if(r==Row.SHUTTER)return "快门";if(r==Row.EV)return "曝光补偿";
        if(r==Row.ISO)return "ISO";if(r==Row.WB)return "白平衡";
        return r==Row.POINT?"移动对焦点":r==Row.AREA?"对焦区域":r==Row.FACE?"人脸优先":r==Row.GRID?"网格线":"曝光模式";
    }
    String modeName(){return modeName(exposure.mode());}
    static String modeName(ManualExposure.Mode mode){
        switch(mode){
            case A:return "A 光圈优先";
            case S:return "S 快门优先";
            case M:return "M 手动曝光";
            default:return "P 程序自动";
        }
    }
    String hint(){
        if(nativeBindings)return "Fn / MENU 设置";
        return layout.hint(exposure.mode(),alternate)+" · → ISO · ← 白平衡 · ↓ 设置";
    }
    String isoText(int measured){
        int iso=exposure.iso();
        if(iso>0)return "ISO "+iso;
        return measured>0?"AUTO "+measured:"ISO AUTO";
    }
    String compensationText(){return exposure.compensationLabel();}
    String whiteBalanceText(){return ManualExposure.whiteBalanceLabel(exposure.whiteBalance());}
    String title(){
        switch(panel){
            case ISO:return "ISO";
            case WB:return "白平衡";
            case SETTINGS:return "对焦与取景设置";
            case CHOICE:return rowName(choiceRow);
            case NATIVE:return nativeTitle;
            case ADJUST:return ControlLayout.label(adjustment);
            default:return null;
        }
    }
    String[] rowLabels(){
        switch(panel){
            case ISO:{
                List<Integer> v=exposure.isoValues();String[] out=new String[v.size()];
                for(int i=0;i<out.length;i++)out[i]=v.get(i).intValue()==0?"AUTO":Integer.toString(v.get(i).intValue());
                return out;
            }
            case WB:{
                List<String> v=exposure.whiteBalanceValues();String[] out=new String[v.size()];
                for(int i=0;i<out.length;i++)out[i]=ManualExposure.whiteBalanceLabel(v.get(i));
                return out;
            }
            case SETTINGS:{
                String[] out=new String[rows.size()];
                for(int i=0;i<out.length;i++)out[i]=rows.get(i)==Row.EXTRA?additional.labels()[i-rows.indexOf(Row.EXTRA)]:rowName(rows.get(i));
                return out;
            }
            case CHOICE:{
                String[] out=new String[choices.size()];
                for(int i=0;i<out.length;i++){
                    String v=choices.get(i);
                    out[i]=choiceRow==Row.AREA?FocusControls.areaLabel(v):choiceRow==Row.FACE?("auto".equals(v)?"开":"关")
                        :choiceRow==Row.GRID?CompositionGrid.decode(v).label:modeName(ManualExposure.Mode.valueOf(v));
                }
                return out;
            }
            case NATIVE:return nativeLabels==null?choices.toArray(new String[choices.size()]):nativeLabels;
            case ADJUST:return new String[]{adjustmentLabel()};
            default:return null;
        }
    }
    String[] rowValues(){
        if(panel!=Panel.SETTINGS)return null;
        String[] out=new String[rows.size()];
        for(int i=0;i<out.length;i++){
            Row r=rows.get(i);
            if(r==Row.POINT)out[i]="";
            else if(r==Row.AREA)out[i]=FocusControls.areaLabel(focus.area());
            else if(r==Row.FACE)out[i]=focus.face()?"开":"关";
            else if(r==Row.GRID)out[i]=grid.label;
            else if(r==Row.EXTRA||r==Row.APERTURE||r==Row.SHUTTER||r==Row.EV||r==Row.ISO||r==Row.WB)out[i]="";
            else out[i]=modeName();
        }
        return out;
    }
    int selected(){return selected;}
    ControlLayout.Action adjustment(){return panel==Panel.ADJUST?adjustment:ControlLayout.Action.NONE;}
    private String adjustmentLabel(){
        if(adjustment==ControlLayout.Action.EV)return exposure.compensationLabel();
        MapReadout r=new MapReadout(exposure.parameters());
        return adjustment==ControlLayout.Action.APERTURE?r.aperture():r.shutter();
    }
    private static final class MapReadout {
        final java.util.Map<String,String> p;
        MapReadout(java.util.Map<String,String> p){this.p=p;}
        String aperture(){int value=ManualExposure.number(p.get("aperture"));return value>0?String.format(java.util.Locale.US,"F%.1f",value/100f):"--";}
        String shutter(){String n=p.get("shutter-speed-nume"),d=p.get("shutter-speed-deno");return n==null||d==null?"--":n+"/"+d;}
    }
    String panelHint(){
        switch(panel){
            case ADJUST:return "←→ / ↑↓ 调整 · 中键 / MENU 返回";
            case NATIVE:return "↑↓ 选择 · 中键确定 · MENU 返回";
            case ISO:case WB:return "↑↓ 实时预览 · 中键确定 · MENU 取消";
            case SETTINGS:{
                if(rows.isEmpty())return "";
                Row row=rows.get(selected);
                if(row==Row.POINT)return "中键移动对焦点 · MENU 返回";
                return "中键选择 · ←→ 快速切换 · MENU 返回";
            }
            case CHOICE:{
                boolean flex=choiceRow==Row.AREA&&FocusControls.FLEX.equals(choices.get(selected))&&focus.pointAvailable();
                return flex?"↑↓ 选择 · 中键确定并移动对焦点 · MENU 返回":"↑↓ 选择 · 中键确定 · MENU 返回";
            }
            case POINT:return "方向键／拨轮移动 · 中键确定 · MENU 取消";
            default:return "";
        }
    }
    boolean afVisible(){return focus!=null&&focus.pointAvailable()&&(panel==Panel.POINT||focus.flexible());}
    boolean afEditing(){return panel==Panel.POINT;}
    int afX(){return focus==null?0:focus.x();}
    int afY(){return focus==null?0:focus.y();}
}
