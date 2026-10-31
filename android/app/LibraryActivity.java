package org.hongen.sonylutprobe;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

/** Product entry point. File and preview work stay off the UI thread. */
public final class LibraryActivity extends Activity {
    private static final int CAPTURE_REQUEST=1,GALLERY_REQUEST=2;
    private final Handler ui=new Handler();
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private CameraPage page;private LutLibrary library;private PhotoCatalog catalog;private LutPreview previews;
    private List<LutLibrary.Entry> luts=new ArrayList<LutLibrary.Entry>();
    private List<PhotoCatalog.Pair> photos=new ArrayList<PhotoCatalog.Pair>();
    private List<File> imports=new ArrayList<File>();private List<String> importResults=new ArrayList<String>();
    private final LutLibrary.Verifier verifier=new LutLibrary.Verifier(){public int inspect(File file)throws Exception{return LutBinaryCache.inspect(file);}};
    private final PreviewRequest previewRequests=new PreviewRequest();
    private String mode="loading",previous="library",notice="",previewId="",importFocusId;private int selection,lastSelection;private boolean busy,destroyed,importDone;
    private String diagnosticExport="";
    private boolean displayStarted,exiting;private String selectedId;
    private LutLibrary.Entry previewEntry;
    private NativePreviewProfile fittedPreview;
    private PreviewProfileStore.Choice previewChoice;
    private String fitError="";
    private boolean previewForCapture,capturePending,captureAway;
    private SonyAppPolicy.ExitRequest exitRequest;
    private final Runnable exitTimeout=new Runnable(){public void run(){
        if(!destroyed&&exiting&&displayStarted){
            if(exitRequest!=null)exitRequest.cancel();
            exiting=false;android.util.Log.e("LutUI","native_exit_not_acknowledged");
            page.busy=false;
            showExitFailure("相机未响应退出请求，可重试或打开测试连接。");
        }
    }};
    @Override public void onCreate(Bundle state){super.onCreate(state);
        if(state!=null){capturePending=state.getBoolean("capture_pending");captureAway=state.getBoolean("capture_away");}
        try{UiFont.get(this);}catch(RuntimeException e){android.util.Log.e("LutLibrary","font_failed",e);android.widget.TextView error=new android.widget.TextView(this);error.setText("Font could not load. Please update LUT Camera.\nMENU: Exit");setContentView(error);return;}
        page=new CameraPage(this);setContentView(page);
        page.tap=new CameraPage.Tap(){public void select(int row){selection=row;render();action();}};
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);initializeLibrary();
    }
    private void initializeLibrary(){
        previewRequests.next();previewId="";if(page!=null)page.preview=null;if(previews!=null){previews.clear();previews=null;}
        mode="loading";runTask("正在打开 LUT 库",new Task(){public void run()throws Exception{
            previews=new LutPreview(LibraryActivity.this);library=new LutLibrary(ProductFiles.library());catalog=new PhotoCatalog(ProductFiles.photos(),Environment.getExternalStorageDirectory());
            library.retireBuiltinSamples(ProductFiles.builtins());
            String defaultId=null;
            for(BuiltinLuts.Entry entry:BuiltinLuts.read(getAssets().open("CATALOG.JSN"))){String id=installBuiltin(entry);if(entry.selected)defaultId=id;}
            selectedId=library.selectedId();if(library.find(selectedId)==null)selectedId=defaultId;luts=library.entries();
        }},new Runnable(){public void run(){mode="library";selection=indexOf(selectedId);render();requestPreview();}});
    }
    private String installBuiltin(BuiltinLuts.Entry item)throws Exception{
        LutLibrary.Entry existing=library.find(item.sha256);
        if(existing!=null){if(existing.size!=item.grid)throw new IOException("内置 LUT 阶数不符");return existing.id;}
        File builtins=ProductFiles.builtins();LutLibrary.ensureDirectory(builtins);
        File input=Assets.extract(new File(getApplicationInfo().sourceDir),item.asset,builtins);
        LutLibrary.Entry entry=library.importFile(input,item.name,item.source,verifier).entry;
        if(!entry.id.equals(item.sha256)||entry.size!=item.grid)throw new IOException("内置 LUT 与内容清单不符");return entry.id;
    }
    private int indexOf(String id){for(int i=0;i<luts.size();i++)if(luts.get(i).id.equals(id))return i;return 0;}
    interface Task {void run()throws Exception;}
    private void runTask(String label,final Task task,final Runnable complete){if(busy)return;busy=true;page.busy=true;page.previewMode=false;page.title=label;page.subtitle="";page.rows=new ArrayList<CameraPage.Row>();page.empty=label;page.note="";page.footer="处理中";page.invalidate();
        io.execute(new Runnable(){public void run(){try{task.run();ui.post(new Runnable(){public void run(){if(destroyed)return;busy=false;page.busy=false;complete.run();}});}
            catch(final Exception e){SupportDiagnostics.failure("library_operation",e);android.util.Log.e("LutLibrary","operation_failed",e);ui.post(new Runnable(){public void run(){if(destroyed)return;busy=false;page.busy=false;showNotice("操作未完成",explain(e));}});}}});
    }
    private static String explain(Exception e){String text=e.getMessage();if(text==null)text=e.getClass().getSimpleName();
        if(text.contains("truncated")||text.contains("too many cube rows"))return "数据行数与阶数不符，请重新导出 CUBE";
        if(text.contains("1D / combined"))return "只支持独立 3D LUT，请导出为 3D CUBE";
        if(text.contains("cube size"))return "支持 2–65 阶 3D LUT，请检查 LUT_3D_SIZE";
        if(text.contains("domain"))return "输入范围无效，请检查 DOMAIN_MIN / MAX";
        if(text.contains("cube data row")||text.contains("cube output"))return "CUBE 数值格式不正确，请重新导出";
        if(text.contains("ENOSPC"))return "存储空间不足，请检查 SD 卡剩余空间";
        return LutLibrary.label(text);
    }
    private void showNotice(String title,String detail){previous=mode.equals("notice")?previous:mode;mode="notice";selection=0;notice=detail;render();page.title=title;page.invalidate();}
    private void render(){if(destroyed||busy)return;List<CameraPage.Row> rows=new ArrayList<CameraPage.Row>();
        page.previewMode=mode.equals("library");page.section=mode.equals("library")?"LUT 库 · "+luts.size():"LUT CAMERA";page.title="选择你的色彩";page.subtitle="方向键 / 拨轮选择 · 中央键开始拍摄";page.empty="还没有 LUT";
        page.note="24MP · 本机最高 JPEG 画质 · 原片与成片分别保存";page.footer="↑↓ 选择   ● 拍摄   ← 相册   → 导入   MENU 更多";
        page.emptyDetail="右键导入 · 中央键确认";
        if(mode.equals("library")){
            for(LutLibrary.Entry e:luts)rows.add(new CameraPage.Row(e.name,e.source+(e.id.equals(selectedId)?" · 当前":""),e.size+"³"));
        }else if(mode.equals("import")){
            page.section="导入 LUT";page.title="加入新的色彩";page.subtitle="将 .cube / .cub 文件放入 SD 卡根目录的 LUTS 文件夹";
            rows.add(new CameraPage.Row("扫描 LUTS 文件夹","检查文件并显示可导入的 LUT","扫描"));
            rows.add(new CameraPage.Row("返回 LUT 库","已导入的色彩可以继续使用","返回"));
            page.note="支持 2–65³ 独立 3D CUBE · 不覆盖导入源文件";page.footer="↑↓ 选择   ● 确认   MENU 返回";
        }else if(mode.equals("files")){
            page.section="导入 LUT";page.title=importDone?"导入完成":"发现 "+imports.size()+" 个 LUT";page.subtitle=importDone?notice:"可全部导入，也可只选择其中一个";
            rows.add(new CameraPage.Row(importDone?(importFocusId==null?"返回 LUT 库":"使用导入的 LUT"):imports.isEmpty()?"重新扫描":"全部导入",importDone?"返回后已定位到新增色彩":"完整校验后加入卡内 LUT 库",importDone?"完成":"导入"));
            for(int i=0;i<imports.size();i++){String result=importResults.get(i);boolean bad=result.startsWith("失败");rows.add(new CameraPage.Row(imports.get(i).getName(),result,bad?"查看原因":result.equals("等待导入")?"导入":"已处理",!bad));}
            page.note=importDone?"新增、重复和失败文件已分别核对":notice;page.footer="↑↓ 选择   ● 确认   → 重扫   MENU 返回";
        }else if(mode.equals("gallery")){
            page.section="照片";page.title="拍过的色彩";page.subtitle="每一组都保留 LUT 成片与原片";page.empty="还没有照片";page.emptyDetail="拍下第一张照片后，在这里回看";
            SimpleDateFormat date=new SimpleDateFormat("MM.dd  HH:mm",Locale.CHINA);
            for(PhotoCatalog.Pair p:photos)rows.add(new CameraPage.Row(p.lut,(p.time>946684800000L?date.format(new Date(p.time)):"日期未设置")+" · "+p.graded.getName(),p.grid+"³"));
            page.note=photos.size()+" 组照片 · 仅列出本应用拍摄的内容";page.footer="↑↓ 选择   ● 打开   MENU 返回";
        }else if(mode.equals("options")){
            page.section="更多";page.title="拍摄之外";page.subtitle="LUT Camera · "+version();
            rows.add(new CameraPage.Row("导入 LUT","从 SD 卡 LUTS 文件夹加入色彩","打开"));
            rows.add(new CameraPage.Row("使用说明","色彩输入、按键和保存方式","查看"));
            rows.add(new CameraPage.Row("回到 LUT 库","继续选择或拍摄","返回"));
            rows.add(new CameraPage.Row("退出应用","回到相机原生拍摄界面","退出"));
            if(SonyAppPolicy.hasDebugHelper(this))rows.add(new CameraPage.Row("测试连接","打开已安装的相机调试工具","打开"));
            rows.add(new CameraPage.Row("当前 LUT 的取景","近似色彩或原色 · 每个 LUT 单独记忆","设置"));
            rows.add(new CameraPage.Row("导出诊断日志","出问题后导出日志包，交给开发者分析","导出"));
            page.note="当前验证机型 Sony A6300";page.footer="↑↓ 选择   ● 确认   MENU 返回";
        }else if(mode.equals("diagnostics")){
            page.section="诊断日志";page.title=diagnosticExport.length()==0?"导出问题记录":"日志包已导出";
            page.subtitle=diagnosticExport.length()==0?"自动保留最近的启动、拍摄和错误记录":"SD 卡 / SONYLUT / DIAG / REPORT.ZIP";
            rows.add(new CameraPage.Row("导出日志包","将 REPORT.ZIP 发给开发者，并描述问题发生前的操作","导出"));
            rows.add(new CameraPage.Row("返回 LUT 库","继续选择或拍摄","返回"));
            page.note="不包含照片或 LUT 文件，不会自动上传";page.footer="↑↓ 选择   ● 确认   MENU 返回";
        }else if(mode.equals("preview_options")||mode.equals("preview_warning")||mode.equals("preview_error")){
            page.section="取景预览";page.title=previewEntry.name;page.subtitle=mode.equals("preview_warning")?"预览与成片可能有较明显差异":mode.equals("preview_error")?"近似预览暂未生成":"只改变取景方式，不改变照片效果";
            boolean enabled=previewChoice==null||previewChoice.enabled;
            rows.add(new CameraPage.Row(mode.equals("preview_error")?"重新生成近似预览":mode.equals("preview_warning")?"继续使用近似预览":"近似色彩预览",mode.equals("preview_error")?fitError:"使用机内自动拟合的矩阵与曲线",previewChoice!=null&&enabled?"当前":"选择"));
            rows.add(new CameraPage.Row(mode.equals("preview_warning")||mode.equals("preview_error")?"使用原色取景拍摄":"原色取景","照片仍应用完整 LUT",previewChoice!=null&&!enabled?"当前":"选择"));
            rows.add(new CameraPage.Row("返回 LUT 库","保留当前选择","返回"));
            page.note=previewChoice==null?"预览偏好无法读取，请重新选择":fittedPreview!=null&&fittedPreview.largeError()?"近似效果差异较大，仍可自由选择使用":"半按对焦时恢复原色 · 成片使用完整 LUT";
            page.footer="↑↓ 选择   ● 确认   MENU 返回";
        }else if(mode.equals("exit_error")){
            page.section="退出应用";page.title="退出未完成";page.subtitle=notice;
            rows.add(new CameraPage.Row("重试退出","重新结束当前应用会话","重试"));
            if(SonyAppPolicy.hasDebugHelper(this))rows.add(new CameraPage.Row("打开测试连接","恢复连接以便检查与修复","打开"));
            rows.add(new CameraPage.Row("返回 LUT 库","已有照片和 LUT 保持不变","返回"));
            page.note="";page.footer="↑↓ 选择   ● 确认   MENU 返回";
        }else if(mode.equals("help")){
            page.section="使用说明";page.title="四步，把色彩带进照片";page.subtitle="选择 LUT → 半按对焦 → 全按拍摄 → 回看成片";
            rows.add(new CameraPage.Row("使用普通照片 LUT","输入是索尼显影后的 8 位图像；Log LUT 需先适配","输入"));
            rows.add(new CameraPage.Row("自动曝光与亮度","取景时左右键 / 拨轮调 EV，中央键归零","拍摄"));
            rows.add(new CameraPage.Row("一次快门，两张 JPEG","原片和 LUT 成片都保存在 DCIM","保存"));
            rows.add(new CameraPage.Row("快速对照与继续拍摄","左右翻图，中央键对照，半按返回取景","回看"));
            page.note="效果参考图不是实时取景；成片保持 6000 × 4000";page.footer="MENU 返回";
        }else{
            page.section="提示";page.title="请检查后重试";page.subtitle=notice;page.note="已有 LUT 和照片均已保留";
            rows.add(new CameraPage.Row(previous.equals("loading")?"重试":"返回",notice,"确认"));rows.add(new CameraPage.Row("导出诊断日志","将问题记录保存到 SD 卡","导出"));page.footer=previous.equals("loading")?"● 重试   MENU 退出":"● 返回   MENU 返回";
        }
        selection=UiList.clamp(selection,rows.size());page.rows=rows;page.selected=selection;page.setContentDescription(page.title);page.invalidate();
        SupportDiagnostics.event("ui_page","mode="+mode+" selection="+selection+" rows="+rows.size());
        android.util.Log.i("LutUI","page_ready mode="+mode+" selection="+selection+" rows="+rows.size());
    }
    private String version(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(Exception e){return "UI 预览";}}
    private void libraryPage(){mode="library";selection=UiList.clamp(lastSelection,luts.size());render();requestPreview();}
    private void requestPreview(){
        if(!mode.equals("library")||luts.isEmpty()||previews==null)return;
        final LutLibrary.Entry entry=luts.get(selection);if(entry.id.equals(previewId))return;previewId=entry.id;final long token=previewRequests.next();
        Bitmap cached=previews.cached(entry.id);
        if(cached!=null){previews.pin(entry.id);page.preview=cached;page.previewLoading=false;page.invalidate();return;}
        page.preview=null;page.previewLoading=true;page.previewMessage="效果参考";page.invalidate();
        io.execute(new Runnable(){public void run(){
            if(!previewRequests.current(token))return;
            try{final Bitmap rendered=previews.render(entry);ui.post(new Runnable(){public void run(){
                if(destroyed||!previewRequests.current(token)){rendered.recycle();return;}
                if(previews.store(entry.id,rendered)){previews.pin(entry.id);page.preview=rendered;page.previewLoading=false;page.invalidate();}
            }});}catch(final Exception e){ui.post(new Runnable(){public void run(){if(!destroyed&&previewRequests.current(token)){page.previewLoading=false;page.previewMessage="预览不可用";page.invalidate();android.util.Log.w("LutUI","lut_preview_failed",e);}}});}
        }});
    }
    private void scan(){mode="files";selection=0;runTask("正在扫描 SD 卡",new Task(){public void run()throws Exception{
        imports=LutLibrary.scan(new File(Environment.getExternalStorageDirectory(),"LUTS"));importResults=new ArrayList<String>();for(File f:imports)importResults.add("等待导入");importDone=false;importFocusId=null;
    }},new Runnable(){public void run(){notice=imports.isEmpty()?"没有找到文件：请将 CUBE 直接放入 LUTS 文件夹":"支持 .cube / .cub；TITLE 将作为显示名称";render();}});}
    private void importFiles(final int index){runTask("正在校验并导入 LUT",new Task(){public void run()throws Exception{
        int added=0,existing=0,failed=0;importFocusId=null;
        for(int i=0;i<imports.size();i++){if(index>=0&&i!=index)continue;
            final String fileName=imports.get(i).getName();final String position=(i+1)+" / "+imports.size();
            ui.post(new Runnable(){public void run(){if(!destroyed&&busy){page.subtitle=position;page.empty=fileName;page.footer="正在导入，请勿取出 SD 卡";page.invalidate();}}});
            try{LutLibrary.Imported result=library.importFile(imports.get(i),null,"自定义 LUT",verifier);
                if(importFocusId==null)importFocusId=result.entry.id;
                ui.post(new Runnable(){public void run(){if(!destroyed&&busy){page.footer="正在生成近似预览 · 仅首次需要";page.invalidate();}}});
                String previewResult=" · 预览已生成";
                try{PreviewFitting.ensure(result.entry);}catch(Exception error){previewResult=" · 预览待重试";android.util.Log.w("SonyPreviewFit","import_fit_failed",error);}
                importResults.set(i,(result.added?"已导入 · ":"已存在 · ")+result.entry.name+previewResult);if(result.added)added++;else existing++;
            }catch(Exception e){failed++;importResults.set(i,"失败 · "+explain(e));android.util.Log.w("LutLibrary","import_failed "+imports.get(i).getName(),e);}
        }
        luts=library.entries();notice="新增 "+added+" · 已存在 "+existing+" · 失败 "+failed;importDone=index<0;
    }},new Runnable(){public void run(){if(index>=0&&importFocusId!=null){selectedId=importFocusId;lastSelection=indexOf(importFocusId);libraryPage();}else{selection=0;render();}}});}
    private void finishImport(){if(importFocusId!=null){selectedId=importFocusId;lastSelection=indexOf(importFocusId);}libraryPage();}
    private void gallery(){lastSelection=selection;mode="gallery";selection=0;runTask("正在打开照片",new Task(){public void run()throws Exception{photos=catalog.list();}},new Runnable(){public void run(){render();}});}
    private void openGallery(String pairId){openGallery(pairId,0);}
    private void openGallery(String pairId,int offset){startActivityForResult(new Intent(this,GalleryActivity.class).putExtra("pair_id",pairId).putExtra("page_offset",offset),GALLERY_REQUEST);}
    private void startCapture(final LutLibrary.Entry entry){
        selectedId=entry.id;lastSelection=selection;
        previewEntry=entry;previewForCapture=true;fitError="";fittedPreview=null;
        runTask("正在准备 "+entry.name,new Task(){public void run()throws Exception{
            SupportDiagnostics.event("capture_requested","lut_id="+entry.id+" grid="+entry.size);
            library.select(entry.id);previewChoice=PreviewProfileStore.choice(entry);
            if(previewChoice.enabled)try{SupportDiagnostics.event("preview_fit_begin","");fittedPreview=PreviewFitting.ensure(entry);SupportDiagnostics.event("preview_fit_ready","");}catch(Exception e){SupportDiagnostics.failure("preview_fit",e);fitError=explain(e);android.util.Log.w("SonyPreviewFit","prepare_failed",e);}
        }},new Runnable(){public void run(){
            if(previewChoice.enabled&&fittedPreview==null){mode="preview_error";selection=0;render();return;}
            if(previewChoice.enabled&&fittedPreview.largeError()&&previewChoice.acknowledged<fittedPreview.fitVersion){mode="preview_warning";selection=0;render();return;}
            launchCapture(entry);
        }});
    }
    private void launchCapture(LutLibrary.Entry entry){
            capturePending=true;captureAway=false;
            mode="library";selection=indexOf(entry.id);render();startActivityForResult(new Intent(LibraryActivity.this,PhotoActivity.class).putExtra("lut_id",entry.id)
                    .putExtra("develop_animation",getIntent().getBooleanExtra("develop_animation",true))
                    .putExtra("develop_animation_alternate",getIntent().getBooleanExtra("develop_animation_alternate",false)),CAPTURE_REQUEST);
    }
    private void previewSettings(){
        if(luts.isEmpty())return;previewForCapture=false;previewEntry=luts.get(UiList.clamp(lastSelection,luts.size()));fitError="";fittedPreview=null;
        runTask("正在读取预览设置",new Task(){public void run()throws Exception{
            try{previewChoice=PreviewProfileStore.choice(previewEntry);}catch(IOException damaged){previewChoice=null;notice="预览偏好无法读取，请重新选择";}
            try{fittedPreview=PreviewProfileStore.load(previewEntry);}catch(IOException e){fitError=explain(e);}
        }},new Runnable(){public void run(){mode="preview_options";selection=0;render();}});
    }
    private void choosePreview(final boolean enabled){
        final boolean capture=previewForCapture;fitError="";
        runTask(enabled?"正在准备近似预览":"正在保存取景方式",new Task(){public void run()throws Exception{
            if(enabled)try{fittedPreview=PreviewFitting.ensure(previewEntry);}catch(Exception e){fitError=explain(e);return;}
            PreviewProfileStore.choose(previewEntry,enabled,fittedPreview==null?0:fittedPreview.fitVersion);
            previewChoice=PreviewProfileStore.choice(previewEntry);
        }},new Runnable(){public void run(){
            if(fitError.length()>0){mode="preview_error";selection=0;render();return;}
            if(capture)launchCapture(previewEntry);else{mode="preview_options";selection=enabled?0:1;render();}
        }});
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==CAPTURE_REQUEST){capturePending=false;captureAway=false;}
        if(request==CAPTURE_REQUEST&&data!=null&&data.hasExtra("startup_failure")){
            mode="library";render();requestPreview();
            String detail=data.getStringExtra("startup_failure");
            if(data.getBooleanExtra("startup_diagnostic",false))detail+="\n\n返回后：MENU → 导出诊断日志";
            new android.app.AlertDialog.Builder(this).setTitle(data.getStringExtra("startup_title"))
                .setMessage(detail).setPositiveButton("返回 LUT 库",null).show();return;
        }
        if(request==CAPTURE_REQUEST&&result==RESULT_CANCELED){showInterruptedCapture();return;}
        if(result!=RESULT_OK||data==null)return;
        if(request==CAPTURE_REQUEST&&data.getBooleanExtra("open_gallery",false))openGallery(null,data.getIntExtra("page_offset",0));
        else if(request==GALLERY_REQUEST&&data.getBooleanExtra("return_capture",false)){mode="library";selection=indexOf(selectedId);if(!luts.isEmpty())startCapture(luts.get(selection));}
    }
    private void action(){if(busy)return;
        if(mode.equals("library")){if(luts.isEmpty())return;startCapture(luts.get(selection));
        }else if(mode.equals("import")){if(selection==0)scan();else libraryPage();
        }else if(mode.equals("files")){if(selection==0){if(importDone)finishImport();else if(!imports.isEmpty())importFiles(-1);else scan();}
            else if(importResults.get(selection-1).startsWith("失败"))showNotice("这个文件未能导入",importResults.get(selection-1));else if(!importDone)importFiles(selection-1);
        }else if(mode.equals("gallery")){if(!photos.isEmpty())openGallery(photos.get(selection).id);
        }else if(mode.equals("options")){if(selection==0){mode="import";selection=0;}else if(selection==1){mode="help";selection=0;}else if(selection==2){libraryPage();return;}else if(selection==3){exitToCamera();return;}else if(selection==page.rows.size()-1){mode="diagnostics";selection=0;render();return;}else if(selection==4&&SonyAppPolicy.hasDebugHelper(this)){openDebugHelper();return;}else{previewSettings();return;}render();
        }else if(mode.equals("diagnostics")){if(selection==0)exportDiagnostics();else libraryPage();
        }else if(mode.equals("preview_options")||mode.equals("preview_warning")||mode.equals("preview_error")){
            if(selection==2)libraryPage();else choosePreview(selection==0);
        }else if(mode.equals("exit_error")){if(selection==0)exitToCamera();else if(selection==1&&SonyAppPolicy.hasDebugHelper(this))openDebugHelper();else libraryPage();
        }else if(mode.equals("help")){mode="options";selection=1;render();
        }else if(mode.equals("notice")){if(selection==1){mode="diagnostics";selection=0;render();return;}if(previous.equals("loading")){initializeLibrary();return;}mode=previous;selection=0;render();}
    }
    private void exportDiagnostics(){
        runTask("正在导出诊断日志",new Task(){public void run()throws Exception{
            try{diagnosticExport=SupportDiagnostics.exportReport().getName();}
            catch(IOException error){throw new IOException("无法导出到 SD 卡，请检查写保护和剩余空间后重试。",error);}
        }},new Runnable(){public void run(){mode="diagnostics";selection=1;render();}});
    }
    private void exitToCamera(){
        if(exiting||busy)return;exiting=true;previewRequests.next();
        if(page!=null){page.busy=true;page.title="正在返回相机";page.subtitle="正在结束应用会话";page.invalidate();}
        exitRequest=new SonyAppPolicy.ExitRequest(this,new SonyAppPolicy.ExitListener(){public void failed(Exception e){
            exiting=false;ui.removeCallbacks(exitTimeout);android.util.Log.e("LutUI","native_exit_failed",e);
            if(page!=null){page.busy=false;showExitFailure(explain(e));}
        }});
        ui.postDelayed(exitTimeout,12000);exitRequest.start();
    }
    private void showExitFailure(String detail){mode="exit_error";selection=0;notice=detail;render();}
    private void openDebugHelper(){
        try{SonyAppPolicy.openDebugHelper(this);}
        catch(RuntimeException error){android.util.Log.e("LutUI","debug_helper_start_failed",error);showExitFailure("测试连接未能打开，请检查安装状态。");}
    }
    @Override public void onBackPressed(){if(exiting)return;if(page==null){exitToCamera();return;}if(busy)return;if(mode.equals("library")){lastSelection=selection;mode="options";selection=0;render();}
        else if(mode.equals("notice")){if(previous.equals("loading"))exitToCamera();else action();}else if(mode.equals("help")){mode="options";selection=1;render();}else libraryPage();}
    private void moveSelection(int delta){if(page.rows.isEmpty())return;selection=UiList.move(selection,delta,page.rows.size());render();if(mode.equals("library"))requestPreview();}
    @Override public boolean dispatchKeyEvent(KeyEvent e){if(exiting)return true;int key=UiKeys.key(e),dial=UiKeys.dial(e);if(key==UiKeys.NONE&&dial==0)return super.dispatchKeyEvent(e);if(e.getAction()!=KeyEvent.ACTION_DOWN)return true;if(page==null){if(key==UiKeys.BACK)exitToCamera();return true;}if(busy)return true;
        if(key==UiKeys.PLAY){openGallery(null);}
        else if(dial!=0)moveSelection(dial);
        else if(key==UiKeys.UP||key==UiKeys.DOWN)moveSelection(key==UiKeys.UP?-1:1);
        else if(e.getRepeatCount()==0){if(key==UiKeys.BACK)onBackPressed();else if(key==UiKeys.OK)action();
            else if(key==UiKeys.RIGHT&&(mode.equals("library")||mode.equals("files"))){if(mode.equals("files"))scan();else{lastSelection=selection;mode="import";selection=0;render();}}
            else if(key==UiKeys.LEFT&&mode.equals("library"))gallery();}
        return true;
    }
    private void showInterruptedCapture(){
        SupportDiagnostics.event("capture_interrupted","capture returned without a normal result");
        android.util.Log.w("LutLibrary","capture_interrupted_notice");
        if(page!=null&&!busy){mode="library";render();requestPreview();}
        new android.app.AlertDialog.Builder(this).setTitle("拍摄会话意外结束")
            .setMessage("相机未能正常完成拍摄会话。\n\n请先设为 P 模式、关闭 HDR，再重新进入。\n若仍发生，请按 MENU → 导出诊断日志。")
            .setPositiveButton("返回 LUT 库",null).show();
    }
    @Override protected void onSaveInstanceState(Bundle state){
        state.putBoolean("capture_pending",capturePending);state.putBoolean("capture_away",captureAway);super.onSaveInstanceState(state);
    }
    @Override protected void onResume(){super.onResume();
        if(capturePending&&captureAway){capturePending=false;captureAway=false;showInterruptedCapture();}
if(exiting&&page!=null){page.busy=false;exiting=false;render();}exiting=false;SonyAppPolicy.register(this);if(page!=null)try{page.startDisplayTracking();displayStarted=true;}catch(ClassNotFoundException e){android.util.Log.i("LutLibrary","non-Sony UI preview");}catch(Exception e){showNotice("屏幕初始化失败",explain(e));}}
    @Override protected void onPause(){if(capturePending)captureAway=true;ui.removeCallbacks(exitTimeout);if(exitRequest!=null)exitRequest.cancel();if(exiting)android.util.Log.i("LutUI","native_exit_activity_paused");if(displayStarted){page.stopDisplayTracking();displayStarted=false;}super.onPause();}
    @Override protected void onDestroy(){destroyed=true;previewRequests.next();io.shutdown();ui.removeCallbacksAndMessages(null);if(page!=null)page.preview=null;if(previews!=null)previews.clear();super.onDestroy();}
}
