package cn.jhun.sanjiaohu;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 电费查询：查宿舍**灯光**与**空调**两个电表的剩余电量（单位：度），余额不足时告警。
 *
 * 移植自 eve_all（Kotlin + JavaFX 桌面版），改为原生 Android 界面并沿用本应用的主题体系。
 * 会话 Cookie 通过统一身份认证自动登录换取，加密保存在本机。
 */
public final class DianfeiActivity extends Activity implements SheetHost {
    ThemePalette theme;
    DianfeiStore store;
    DianfeiConfig config;
    List<Meter> buildings;

    LinearLayout body,alertBar,cards;
    Spinner buildingPicker,floorPicker,roomPicker;
    TextView status,title,subtitle,refreshButton;
    ProgressBar progress;

    /** 界面里正在展示的楼，用于在查询返回后丢弃过期结果。 */
    Meter selected;
    int selectedFloor;
    String selectedRoom;
    String cookie;
    boolean busy,loadingCookie;

    final Handler handler=new Handler(Looper.getMainLooper());
    final ExecutorService io=Executors.newSingleThreadExecutor();
    /** 每次查询自增；旧结果回来时编号对不上就丢弃。 */
    int generation;

    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        theme=AppTheme.from(this,getSharedPreferences("settings",MODE_PRIVATE).getInt("themeColor",0xff2ecbff));
        AppTheme.applySystemBars(this,theme);
        store=new DianfeiStore(this);
        config=store.loadConfig();
        buildings=store.loadMeters();
        buildLayout();
        loadCookie();
    }

    // ---------------- 界面骨架 ----------------

    void buildLayout(){
        LinearLayout root=column();
        root.setBackgroundColor(theme.surface);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();});

        LinearLayout header=row();
        header.setPadding(dp(18),dp(10),dp(18),dp(10));
        header.addView(action("‹",()->finish()),new LinearLayout.LayoutParams(dp(44),dp(44)));
        header.getChildAt(0).setContentDescription("返回");
        LinearLayout titles=column();
        titles.setPadding(dp(14),0,dp(8),0);
        title=text("电费查询",20,theme.text,true);
        titles.addView(title);
        subtitle=text("宿舍灯光 / 空调 电表余额",11,theme.muted,false);
        titles.addView(subtitle);
        header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        header.addView(action("设置",()->showSettings()),new LinearLayout.LayoutParams(dp(56),dp(44)));
        refreshButton=action("刷新",()->query(true));
        LinearLayout.LayoutParams refreshSize=new LinearLayout.LayoutParams(dp(56),dp(44));
        refreshSize.leftMargin=dp(8);
        header.addView(refreshButton,refreshSize);
        root.addView(header);

        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(ColorStateList.valueOf(theme.primary));
        progress.setVisibility(View.GONE);
        root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        body=column();
        body.setPadding(dp(16),dp(6),dp(16),dp(20));
        scroll.addView(body);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        setContentView(root);
    }

    /** 重建选择区与卡片区；每次数据变化都整体重画，避免局部状态错乱。 */
    void renderSelections(){
        body.removeAllViews();
        selected=DianfeiStore.current(buildings,config.building);
        config.building=selected==null?config.building:selected.name;

        LinearLayout selector=panel();
        selector.setPadding(dp(16),dp(14),dp(16),dp(14));
        selector.addView(selectorRow("宿舍楼",buildingPicker=buildingSpinner()));
        gap(selector,10);
        selector.addView(selectorRow("楼层",floorPicker=floorSpinner()));
        gap(selector,10);
        selector.addView(selectorRow("寝室号",roomPicker=roomSpinner()));
        gap(selector,12);
        selector.addView(text(selected==null?"未配置宿舍楼":"房间 "+fullRoom()+"  ·  "+selected.supportText(),12,theme.muted,false));
        body.addView(selector);
        gap(body,12);

        alertBar=column();
        alertBar.setVisibility(View.GONE);
        body.addView(alertBar);

        cards=column();
        body.addView(cards);
        gap(body,12);

        status=text(cookie==null?"正在准备电费查询…":"等待查询",12,theme.muted,false);
        status.setPadding(0,dp(4),0,0);
        body.addView(status);

        syncPickersFromState();
    }

    View selectorRow(String label,Spinner spinner){
        LinearLayout line=row();
        TextView caption=text(label,13,theme.muted,false);
        line.addView(caption,new LinearLayout.LayoutParams(dp(58),-2));
        line.addView(spinner,new LinearLayout.LayoutParams(0,dp(44),1));
        return line;
    }

    Spinner buildingSpinner(){
        List<String> names=new ArrayList<>();
        for(Meter meter:buildings)names.add(meter.name);
        return spinnerFor(names,new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(position<0||position>=buildings.size())return;
                String name=buildings.get(position).name;
                if(name.equals(config.building))return;
                config.building=name;
                selected=buildings.get(position);
                store.saveConfig(config);
                renderSelections();
                query(true);
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
    }

    /** 楼层候选项只列该楼确实有表的楼层。 */
    Spinner floorSpinner(){
        final List<Integer> floors=availableFloors(true);
        List<String> labels=new ArrayList<>();
        for(Integer floor:floors)labels.add(floor+" 楼");
        return spinnerFor(labels,new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(position<0||position>=floors.size())return;
                int floor=floors.get(position);
                if(floor==config.floor)return;
                config.floor=floor;
                store.saveConfig(config);
                renderSelections();
                query(true);
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
    }

    Spinner roomSpinner(){
        final List<String> rooms=config.roomOptions(config.floor);
        List<String> labels=new ArrayList<>();
        for(String room:rooms)labels.add(room+" 室");
        return spinnerFor(labels,new AdapterView.OnItemSelectedListener(){
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long id){
                if(position<0||position>=rooms.size())return;
                String room=rooms.get(position);
                if(room.equals(config.roomNo))return;
                config.roomNo=room;
                store.saveConfig(config);
                renderSelections();
                query(true);
            }
            @Override public void onNothingSelected(AdapterView<?> parent){}
        });
    }

    Spinner spinnerFor(List<String> values,AdapterView.OnItemSelectedListener listener){
        Spinner spinner=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setBackground(shape(theme.entrySurface,13));
        spinner.setPadding(dp(10),0,dp(10),0);
        spinner.setOnItemSelectedListener(listener);
        return spinner;
    }

    /** 把当前选择写回下拉控件。 */
    void syncPickersFromState(){
        int buildingIndex=0;
        for(int i=0;i<buildings.size();i++)if(buildings.get(i).name.equals(config.building))buildingIndex=i;
        if(buildingPicker!=null)buildingPicker.setSelection(buildingIndex,false);

        List<Integer> floors=availableFloors(true);
        int floorIndex=floors.indexOf(config.floor);
        if(floorIndex<0){
            // 该楼没有当前楼层：退回第一个可用楼层并保存，避免停在无效选择上。
            if(!floors.isEmpty()){
                config.floor=floors.get(0);
                store.saveConfig(config);
                floorIndex=0;
            }
        }
        if(floorPicker!=null)floorPicker.setSelection(Math.max(0,floorIndex),false);

        List<String> rooms=config.roomOptions(config.floor);
        int roomIndex=rooms.indexOf(config.roomNo);
        if(roomIndex<0&&!rooms.isEmpty()){
            boolean present=false;
            for(String room:rooms)if(room.equals(config.roomNo))present=true;
            if(!present){config.roomNo=rooms.get(0);store.saveConfig(config);roomIndex=0;}
        }
        if(roomPicker!=null)roomPicker.setSelection(Math.max(0,roomIndex),false);
    }

    /** 该楼可选楼层：优先用实测配置，没有任何配置时退回默认范围。 */
    List<Integer> availableFloors(boolean forLight){
        List<Integer> candidates=config.floorOptions();
        if(selected==null)return candidates;
        List<Integer> supported=DianfeiStore.floorsFor(selected,candidates,forLight);
        if(!supported.isEmpty())return supported;
        List<Integer> both=DianfeiStore.floorsFor(selected,candidates,false);
        if(!both.isEmpty())return both;
        return candidates;
    }

    String fullRoom(){return config.floor+DianfeiConfig.pad(Integer.parseInt(config.roomNo));}

    // ---------------- 会话 ----------------

    /** Cookie 只从加密存储读取，不落明文设置；没有就提示登录。 */
    void loadCookie(){
        loadingCookie=true;
        updateStatus();
        io.execute(()->{
            String loaded=null;
            try{
                if(DianfeiCredentialStore.exists(this))loaded=DianfeiCredentialStore.load(this);
            }catch(Exception ignored){}
            final String value=loaded;
            handler.post(()->{
                if(isDestroyed())return;
                loadingCookie=false;
                cookie=DianfeiStore.usableCookie(value)?value:null;
                if(cookie!=null)query(false);
                else{
                    renderSelections();
                    showSignInNeeded();
                }
            });
        });
    }

    /** 用本机保存的统一认证凭证换取新的电费会话。 */
    void signIn(){
        if(busy||loadingCookie)return;
        if(!IdentityCredentialStore.exists(this)){
            Toast.makeText(this,"请先在个人页登录统一认证账号",Toast.LENGTH_LONG).show();
            return;
        }
        busy=true;
        updateStatus();
        final int id=++generation;
        io.execute(()->{
            String message=null,value=null;
            try{
                IdentityCredentialStore.Credentials credentials=IdentityCredentialStore.load(this);
                CasLogin.Outcome outcome=CasLogin.login(credentials.account,credentials.password);
                if(outcome.ok()){
                    value=outcome.cookie;
                    try{DianfeiCredentialStore.save(this,value);}catch(Exception e){value=null;message="会话保存失败，请重试";}
                }else message=outcome.message;
            }catch(Exception e){
                message="统一认证凭证无法读取，请在个人页重新登录";
            }
            final String resultMessage=message,resultCookie=value;
            handler.post(()->{
                if(isDestroyed()||id!=generation)return;
                busy=false;
                cookie=resultCookie;
                if(cookie==null){
                    updateStatus();
                    showMessage(resultMessage==null?"电费会话获取失败":resultMessage,true);
                }else query(false);
            });
        });
    }

    void forgetSession(){
        generation++;
        ArrayList<String> cookies=new ArrayList<>();
        if(cookie!=null)cookies.add(cookie);
        try{DianfeiCredentialStore.clear(this);}catch(Exception ignored){}
        cookie=null;
        busy=false;
        cleanupSessionCookies(cookies,()->{
            if(isDestroyed())return;
            renderSelections();
            showSignInNeeded();
            Toast.makeText(this,"电费会话已清除",Toast.LENGTH_SHORT).show();
        });
    }

    /** 清除 WebView 里可能残留的同一站点 Cookie，避免"看似已退出"的旧会话继续可用。 */
    void cleanupSessionCookies(List<String> cookies,Runnable done){
        if(cookies.isEmpty()){done.run();return;}
        SessionCookies.electricity(cookies,done);
    }

    void showSignInNeeded(){
        cards.removeAllViews();
        LinearLayout empty=panel();
        empty.setPadding(dp(18),dp(20),dp(18),dp(20));
        empty.addView(text("尚未登录电费服务",17,theme.text,true));
        gap(empty,8);
        empty.addView(text("电费查询使用统一身份认证账号，会话加密保存在本机。",13,theme.muted,false));
        gap(empty,16);
        empty.addView(themedButton("使用统一认证登录",()->signIn(),true),new LinearLayout.LayoutParams(-1,dp(46)));
        cards.addView(empty);
        updateStatus();
    }

    // ---------------- 查询 ----------------

    /** @param userInitiated 用户主动点击时，失败要弹提示 */
    void query(boolean userInitiated){
        if(busy||selected==null)return;
        final String lightVerify=selected.lightRoomVerify(config.floor,config.roomNo);
        final String acVerify=selected.acRoomVerify(config.floor,config.roomNo);
        if(lightVerify==null&&acVerify==null){
            renderSelections();
            showMessage(selected.hasAirConditioner()?"该宿舍楼此层空调号段未配置，可在设置里补充。":"该宿舍楼没有可查询的电表。",false);
            return;
        }
        if(cookie==null){
            if(userInitiated)signIn();else showSignInNeeded();
            return;
        }

        busy=true;
        final int id=++generation;
        updateStatus();
        final String activeCookie=cookie;
        io.execute(()->{
            final DianfeiApi.Result light=lightVerify==null?null:DianfeiApi.query(config,activeCookie,lightVerify);
            // 防风控：两个查询之间留出间隔，避免触发限流。
            if(light!=null&&acVerify!=null){
                try{Thread.sleep(1200);}catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            }
            final DianfeiApi.Result ac=acVerify==null?null:DianfeiApi.query(config,activeCookie,acVerify);
            handler.post(()->{
                if(isDestroyed()||id!=generation)return;
                busy=false;
                boolean expired=(light!=null&&light.sessionExpired())||(ac!=null&&ac.sessionExpired());
                if(expired){
                    cookie=null;
                    try{DianfeiCredentialStore.clear(this);}catch(Exception ignored){}
                    showMessage("会话已失效，请重新登录后重试。",true);
                }
                renderResults(light,ac,lightVerify,acVerify);
                if(userInitiated&&!expired){
                    boolean failed=(light!=null&&!light.ok)||(ac!=null&&!ac.ok);
                    if(failed)toastFirstFailure(light,ac);
                }
            });
        });
    }

    void renderResults(DianfeiApi.Result light,DianfeiApi.Result ac,String lightVerify,String acVerify){
        renderSelections();
        List<String> warnings=new ArrayList<>();
        checkBalance(light,"灯光",warnings);
        checkBalance(ac,"空调",warnings);
        showWarnings(warnings);

        if(acVerify!=null)cards.addView(meterCard("空调电费",acVerify,ac,0xff2f6fed));
        if(lightVerify!=null){
            if(acVerify!=null)gap(cards,10);
            cards.addView(meterCard("灯光电费",lightVerify,light,0xfff08a24));
        }
        if(acVerify==null&&selected!=null&&selected.hasAirConditioner()){
            gap(cards,10);
            cards.addView(note("该宿舍楼此层空调号段未配置，本次只查询灯光电表。"));
        }
        updateStatus();
        handler.postDelayed(this::updateStatus,1000);
    }

    void checkBalance(DianfeiApi.Result result,String label,List<String> warnings){
        if(result==null||!result.ok)return;
        if(result.quantity<config.lowBalanceThreshold)
            warnings.add(label+" "+format(result.quantity)+" 度（阈值 "+format(config.lowBalanceThreshold)+" 度）");
    }

    void showWarnings(List<String> warnings){
        alertBar.removeAllViews();
        if(warnings.isEmpty()){alertBar.setVisibility(View.GONE);return;}
        LinearLayout bar=row();
        bar.setPadding(dp(14),dp(12),dp(14),dp(12));
        bar.setBackground(shape(blend(theme.error,theme.surface,.14),16));
        View dot=new View(this);
        dot.setBackground(shape(theme.error,6));
        bar.addView(dot,new LinearLayout.LayoutParams(dp(10),dp(10)));
        TextView words=text("余额不足："+join(warnings),13,theme.text,true);
        words.setLineSpacing(dp(3),1);
        LinearLayout.LayoutParams wordsSize=new LinearLayout.LayoutParams(0,-2,1);
        wordsSize.leftMargin=dp(10);
        bar.addView(words,wordsSize);
        alertBar.addView(bar);
        alertBar.setVisibility(View.VISIBLE);
    }

    View meterCard(String label,String verify,DianfeiApi.Result result,int accent){
        LinearLayout card=panel();
        card.setPadding(dp(16),dp(16),dp(16),dp(16));

        LinearLayout head=row();
        View stripe=new View(this);
        stripe.setBackground(shape(accent,4));
        head.addView(stripe,new LinearLayout.LayoutParams(dp(4),dp(30)));
        LinearLayout headWords=column();
        LinearLayout.LayoutParams headSize=new LinearLayout.LayoutParams(0,-2,1);
        headSize.leftMargin=dp(11);
        headWords.addView(text(label,16,theme.text,true));
        gap(headWords,3);
        headWords.addView(text(verify,11,theme.muted,false));
        head.addView(headWords,headSize);
        head.addView(statusPill(result));
        card.addView(head);
        gap(card,14);

        if(result==null){
            card.addView(text("未查询",22,theme.muted,true));
            return card;
        }
        if(!result.ok){
            TextView failure=text(result.message,14,theme.error,false);
            failure.setLineSpacing(dp(3),1);
            card.addView(failure);
            if(result.sessionExpired()){
                gap(card,12);
                card.addView(themedButton("重新登录电费服务",()->signIn(),false),new LinearLayout.LayoutParams(-1,dp(44)));
            }
            return card;
        }

        boolean low=result.quantity<config.lowBalanceThreshold;
        LinearLayout amount=row();
        TextView number=text(format(result.quantity),30,low?theme.error:theme.deepAccent,true);
        amount.addView(number);
        TextView unit=text(" "+result.unit,13,theme.muted,false);
        unit.setPadding(0,dp(12),0,0);
        amount.addView(unit);
        card.addView(amount);
        gap(card,8);
        card.addView(text("电表 "+result.description+" · "+stamp(),11,theme.muted,false));

        if(low){
            gap(card,12);
            TextView warn=text("⚠  剩余 "+format(result.quantity)+" 度，已低于阈值 "+format(config.lowBalanceThreshold)+" 度",12,theme.error,false);
            warn.setLineSpacing(dp(3),1);
            card.addView(warn);
        }
        return card;
    }

    TextView statusPill(DianfeiApi.Result result){
        String label;
        int color;
        if(result==null){label="未查询";color=theme.muted;}
        else if(!result.ok){label="查询失败";color=theme.error;}
        else if(result.quantity<config.lowBalanceThreshold){label="余额不足";color=theme.error;}
        else{label="正常";color=0xff1f8f4e;}
        TextView pill=text(label,11,color==theme.error?theme.error:color,true);
        pill.setPadding(dp(10),dp(5),dp(10),dp(5));
        pill.setBackground(shape(blend(color,theme.surface,.16),12));
        return pill;
    }

    View note(String message){
        LinearLayout box=column();
        box.setPadding(dp(14),dp(12),dp(14),dp(12));
        box.setBackground(shape(theme.entrySurface,14));
        box.addView(text(message,12,theme.muted,false));
        return box;
    }

    void toastFirstFailure(DianfeiApi.Result light,DianfeiApi.Result ac){
        String message=null;
        if(light!=null&&!light.ok)message=light.message;
        else if(ac!=null&&!ac.ok)message=ac.message;
        if(message!=null)Toast.makeText(this,message,Toast.LENGTH_LONG).show();
    }

    void showMessage(String message,boolean failure){
        cards.removeAllViews();
        LinearLayout box=panel();
        box.setPadding(dp(18),dp(20),dp(18),dp(20));
        box.addView(text(failure?"暂时无法查询":"提示",17,theme.text,true));
        gap(box,8);
        TextView words=text(message,13,theme.muted,false);
        words.setLineSpacing(dp(3),1);
        box.addView(words);
        gap(box,16);
        box.addView(themedButton(failure?"使用统一认证登录":"重新查询",()->{if(failure)signIn();else query(true);},true),new LinearLayout.LayoutParams(-1,dp(46)));
        cards.addView(box);
        updateStatus();
    }

    void updateStatus(){
        if(status==null)return;
        StringBuilder words=new StringBuilder();
        if(loadingCookie)words.append("正在读取本机会话…");
        else if(busy)words.append("正在查询电表…");
        else if(cookie==null)words.append("未登录电费服务");
        else words.append("房间 ").append(fullRoom());
        if(selected!=null)words.append(" · ").append(selected.name);
        if(!busy&&!loadingCookie)words.append(" · 单位：度");
        status.setText(words.toString());
        if(refreshButton!=null){
            boolean enabled=!busy&&!loadingCookie&&cookie!=null;
            refreshButton.setEnabled(enabled);
            refreshButton.setAlpha(enabled?1f:.45f);
        }
        if(progress!=null)progress.setVisibility(busy||loadingCookie?View.VISIBLE:View.GONE);
    }

    // ---------------- 设置 ----------------

    void showSettings(){
        UiSheet sheet=new UiSheet(this,"电费查询设置","号段表与候选项",.76f);
        LinearLayout content=sheet.body;

        content.addView(text("当前选择",15,theme.text,true));
        gap(content,8);
        TextView room=text(selected==null?"未配置":selected.name+" · "+config.floor+" 楼 · "+fullRoom()+" 室",13,theme.muted,false);
        content.addView(room);
        gap(content,6);
        content.addView(text("宿舍楼与寝室号直接在下拉里选，改动会立刻重新查询。",12,theme.muted,false));
        gap(content,18);

        content.addView(text("候选项范围",15,theme.text,true));
        gap(content,10);
        final int[] floorFrom={config.floorFrom},floorTo={config.floorTo},roomFrom={config.roomFrom},roomTo={config.roomTo};
        content.addView(stepper("楼层范围","层",1,6,floorFrom,floorTo,()->{config.floorFrom=floorFrom[0];config.floorTo=floorTo[0];}));
        gap(content,10);
        content.addView(stepper("寝室号范围","室",1,99,roomFrom,roomTo,()->{config.roomFrom=roomFrom[0];config.roomTo=roomTo[0];}));
        gap(content,18);

        content.addView(text("告警阈值",15,theme.text,true));
        gap(content,10);
        LinearLayout thresholdRow=row();
        final TextView thresholdValue=text(format(config.lowBalanceThreshold)+" 度",15,theme.deepAccent,true);
        thresholdRow.addView(thresholdValue,new LinearLayout.LayoutParams(0,-2,1));
        final double[] threshold={config.lowBalanceThreshold};
        thresholdRow.addView(smallButton("−",()->{threshold[0]=Math.max(0,threshold[0]-5);thresholdValue.setText(format(threshold[0])+" 度");}));
        LinearLayout.LayoutParams plusSize=new LinearLayout.LayoutParams(dp(40),dp(40));
        plusSize.leftMargin=dp(8);
        thresholdRow.addView(smallButton("＋",()->{threshold[0]=Math.min(500,threshold[0]+5);thresholdValue.setText(format(threshold[0])+" 度");}),plusSize);
        content.addView(thresholdRow);
        gap(content,6);
        content.addView(text("低于该度数时卡片变红并给出告警。",12,theme.muted,false));
        gap(content,18);

        content.addView(text("接口参数",15,theme.text,true));
        gap(content,6);
        content.addView(text("schoolcode "+config.schoolCode+" · payProId "+config.payProId+" · businesstype "+config.businessType,12,theme.muted,false));
        gap(content,18);

        content.addView(text("号段表",15,theme.text,true));
        gap(content,6);
        content.addView(text("共 "+buildings.size()+" 栋宿舍楼。照明与空调是两套独立编号；空调号段未配置的楼层会提示。",12,theme.muted,false));
        gap(content,10);
        content.addView(text(selectedSupportSummary(),12,theme.muted,false));
        gap(content,18);

        content.addView(text("会话",15,theme.text,true));
        gap(content,6);
        content.addView(text(cookie==null?"尚未登录电费服务。":"电费会话已加密保存在本机，过期后需要重新登录。",12,theme.muted,false));
        gap(content,12);
        sheet.actions(this,"保存设置",()->{
            config.lowBalanceThreshold=threshold[0];
            store.saveConfig(config);
            sheet.dialog.dismiss();
            syncPickersFromState();
            query(true);
        });
        gap(sheet.footer,8);
        LinearLayout extra=row();
        extra.addView(themedButton("重新登录电费服务",()->{sheet.dialog.dismiss();signIn();},false),new LinearLayout.LayoutParams(0,dp(44),1));
        LinearLayout.LayoutParams clearSize=new LinearLayout.LayoutParams(0,dp(44),1);
        clearSize.leftMargin=dp(9);
        extra.addView(themedButton("清除电费会话",()->{sheet.dialog.dismiss();forgetSession();},false),clearSize);
        sheet.footer.addView(extra);
        showSheet(sheet);
    }

    String selectedSupportSummary(){
        if(selected==null)return "未选择宿舍楼。";
        StringBuilder words=new StringBuilder(selected.name+"：");
        words.append(selected.hasLight()?"照明表可用":"无照明表");
        words.append("，");
        words.append(selected.hasAirConditioner()?"空调表可用":"无空调表");
        if(selected.hasAirConditioner()){
            List<Integer> configured=new ArrayList<>(selected.acSeg.keySet());
            java.util.Collections.sort(configured);
            words.append(configured.isEmpty()?"（空调号段未配置）":"（空调号段：楼层 "+joinInts(configured)+"）");
        }
        return words.toString();
    }

    static String joinInts(List<Integer> values){
        StringBuilder out=new StringBuilder();
        for(Integer value:values){
            if(out.length()>0)out.append("、");
            out.append(value);
        }
        return out.toString();
    }

    /** 成对的 − / ＋ 调节器，用于楼层或寝室号范围。 */
    View stepper(String label,String unit,int min,int max,int[] low,int[] high,Runnable onChange){
        LinearLayout box=column();
        box.addView(text(label,13,theme.muted,false));
        gap(box,6);
        LinearLayout line=row();
        final TextView display=text(low[0]+" ~ "+high[0]+" "+unit,15,theme.deepAccent,true);
        line.addView(display,new LinearLayout.LayoutParams(0,-2,1));
        line.addView(smallButton("−",()->{
            if(high[0]-low[0]>0){high[0]=Math.max(low[0],high[0]-1);display.setText(low[0]+" ~ "+high[0]+" "+unit);onChange.run();}
        }));
        LinearLayout.LayoutParams plusSize=new LinearLayout.LayoutParams(dp(40),dp(40));
        plusSize.leftMargin=dp(8);
        line.addView(smallButton("＋",()->{
            if(high[0]<max){high[0]=Math.min(max,high[0]+1);display.setText(low[0]+" ~ "+high[0]+" "+unit);onChange.run();}
        }),plusSize);
        box.addView(line);
        return box;
    }

    void showSheet(UiSheet sheet){
        sheet.dialog.setOnDismissListener(dialog->{if(!isDestroyed())syncPickersFromState();});
        sheet.show(this);
    }

    // ---------------- 小工具 ----------------

    static String format(double value){
        return String.format(java.util.Locale.CHINA,"%.2f",value);
    }

    static String stamp(){
        return new java.text.SimpleDateFormat("HH:mm:ss",java.util.Locale.CHINA).format(new java.util.Date());
    }

    static String join(List<String> values){
        StringBuilder out=new StringBuilder();
        for(String value:values){
            if(out.length()>0)out.append("；");
            out.append(value);
        }
        return out.toString();
    }

    /** 按比例混合两种颜色，用于给状态胶囊与告警条配浅底。 */
    static int blend(int color,int base,double amount){
        int out=0xff000000;
        for(int shift:new int[]{16,8,0}){
            int a=(color>>shift)&255,b=(base>>shift)&255;
            out|=((int)Math.round(a*amount+b*(1-amount)))<<shift;
        }
        return out;
    }

    LinearLayout panel(){
        LinearLayout view=column();
        view.setBackground(shape(theme.panel,22));
        return view;
    }

    // ---- SheetHost ----
    @Override public ThemePalette palette(){return theme;}
    @Override public int ink(){return theme.text;}
    @Override public int muted(){return theme.muted;}
    @Override public int screenWidth(){return getResources().getDisplayMetrics().widthPixels;}

    @Override public LinearLayout column(){
        LinearLayout view=new LinearLayout(this);
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    @Override public LinearLayout row(){
        LinearLayout view=new LinearLayout(this);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    void gap(LinearLayout parent,int size){
        parent.addView(new View(this),new LinearLayout.LayoutParams(1,dp(size)));
    }

    @Override public TextView label(String value,int size,int color,boolean bold){
        return text(value,size,color,bold);
    }

    TextView text(String value,int size,int color,boolean bold){
        TextView view=new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if(bold)view.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        return view;
    }

    TextView action(String label,Runnable run){
        TextView view=text(label,label.length()==1?24:14,theme.deepAccent,true);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setContentDescription(label);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf((theme.primary&0xffffff)|0x33000000),shape(theme.entrySurface,15),shape(theme.rippleMask,15)));
        view.setOnClickListener(v->run.run());
        return view;
    }

    @Override public TextView themedButton(String label,Runnable run,boolean filled){
        TextView view=text(label,14,filled?theme.onPrimary:theme.deepAccent,true);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setContentDescription(label);
        GradientDrawable surface=shape(filled?theme.primary:theme.entrySurface,15);
        if(!filled)surface.setStroke(dp(1),theme.outline);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(((filled?theme.onPrimary:theme.primary)&0xffffff)|0x22000000),surface,shape(theme.rippleMask,15)));
        view.setOnClickListener(v->run.run());
        return view;
    }

    TextView smallButton(String label,Runnable run){
        TextView view=text(label,18,theme.deepAccent,true);
        view.setGravity(Gravity.CENTER);
        view.setFocusable(true);
        view.setContentDescription(label);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf((theme.primary&0xffffff)|0x33000000),shape(theme.entrySurface,12),shape(theme.rippleMask,12)));
        view.setOnClickListener(v->run.run());
        return view;
    }

    @Override public GradientDrawable shape(int color,int radius){
        GradientDrawable drawable=new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    @Override public int dp(float value){return (int)(getResources().getDisplayMetrics().density*value+.5f);}

    @Override protected void onDestroy(){
        generation++;
        handler.removeCallbacksAndMessages(null);
        io.shutdown();
        super.onDestroy();
    }
}
