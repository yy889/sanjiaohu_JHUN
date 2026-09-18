package cn.jhun.sanjiaohu;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 电费查询的本地存储：设置（当前选择、阈值、参数）与号段表。
 *
 * Cookie 是登录凭证，单独加密保存在 {@link DianfeiCredentialStore}，不写进这里。
 */
final class DianfeiStore {
    private static final String PREFS="dianfei";
    private static final String KEY_METERS="meters";
    private final SharedPreferences prefs;

    DianfeiStore(Context context){prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}

    DianfeiConfig loadConfig(){
        DianfeiConfig config=new DianfeiConfig();
        // 无保存值时沿用字段默认值，避免把默认选择写成 0。
        config.floorFrom=prefs.getInt("floorFrom",config.floorFrom);
        config.floorTo=prefs.getInt("floorTo",config.floorTo);
        config.roomFrom=prefs.getInt("roomFrom",config.roomFrom);
        config.roomTo=prefs.getInt("roomTo",config.roomTo);
        config.building=prefs.getString("building",config.building);
        config.floor=prefs.getInt("floor",config.floor);
        config.roomNo=prefs.getString("roomNo",config.roomNo);
        config.lowBalanceThreshold=Double.longBitsToDouble(prefs.getLong("thresholdBits",Double.doubleToRawLongBits(config.lowBalanceThreshold)));
        config.payProId=prefs.getString("payProId",config.payProId);
        config.schoolCode=prefs.getString("schoolCode",config.schoolCode);
        config.businessType=prefs.getString("businessType",config.businessType);
        config.baseUrl=prefs.getString("baseUrl",config.baseUrl);
        config.referer=prefs.getString("referer",config.referer);
        config.rooms.clear();
        config.rooms.addAll(split(prefs.getString("rooms","4-07,4-08,4-09")));
        return config;
    }

    void saveConfig(DianfeiConfig config){
        prefs.edit()
            .putInt("floorFrom",config.floorFrom).putInt("floorTo",config.floorTo)
            .putInt("roomFrom",config.roomFrom).putInt("roomTo",config.roomTo)
            .putString("building",config.building).putInt("floor",config.floor)
            .putString("roomNo",config.roomNo)
            .putLong("thresholdBits",Double.doubleToRawLongBits(config.lowBalanceThreshold))
            .putString("payProId",config.payProId).putString("schoolCode",config.schoolCode)
            .putString("businessType",config.businessType)
            .putString("baseUrl",config.baseUrl).putString("referer",config.referer)
            .putString("rooms",join(config.rooms))
            .apply();
    }

    /**
     * 号段表。保存过就用保存的，否则用内置默认值并立即落盘，
     * 这样以后能在设置里编辑、备份、分享。
     */
    List<Meter> loadMeters(){
        String saved=prefs.getString(KEY_METERS,null);
        if(saved!=null){
            List<Meter> parsed=MeterStore.parse(saved);
            if(parsed!=null&&!parsed.isEmpty())return parsed;
        }
        List<Meter> defaults=new ArrayList<>(MeterData.defaultBuildings());
        saveMeters(defaults);
        return defaults;
    }

    void saveMeters(List<Meter> buildings){
        prefs.edit().putString(KEY_METERS,MeterStore.toJson(buildings)).apply();
    }

    /** 当前选中的宿舍楼；名称失效时退回第一栋。 */
    static Meter current(List<Meter> buildings,String name){
        if(buildings==null||buildings.isEmpty())return null;
        for(Meter meter:buildings)if(meter.name.equals(name))return meter;
        return buildings.get(0);
    }

    /** 只列出该楼确实有对应表的楼层，避免发出注定失败的请求。 */
    static List<Integer> floorsFor(Meter meter,List<Integer> candidates,boolean forLight){
        List<Integer> out=new ArrayList<>();
        if(meter==null)return out;
        for(Integer floor:candidates){
            String verify=forLight?meter.lightRoomVerify(floor,"01"):meter.acRoomVerify(floor,"01");
            if(verify!=null)out.add(floor);
        }
        return out;
    }

    /** 该楼支持查询的类型描述，用于界面提示。 */
    static String supportText(Meter meter){
        if(meter==null)return "未配置";
        return meter.supportText();
    }

    static List<String> split(String value){
        List<String> out=new ArrayList<>();
        if(value==null)return out;
        for(String part:value.split(",")){
            String trimmed=part.trim();
            if(!trimmed.isEmpty())out.add(trimmed);
        }
        return out;
    }

    static String join(List<String> values){
        StringBuilder out=new StringBuilder();
        for(String value:values){
            if(value==null||value.trim().isEmpty())continue;
            if(out.length()>0)out.append(',');
            out.append(value.trim());
        }
        return out.toString();
    }

    /** 从 Cookie 头里判断是否已包含电表查询所需的 SESSION。 */
    static boolean usableCookie(String cookie){
        if(cookie==null)return false;
        Matcher match=Pattern.compile("(?:^|;)\\s*SESSION\\s*=\\s*([^;\\s]+)").matcher(cookie);
        return match.find()&&!match.group(1).isEmpty();
    }
}
