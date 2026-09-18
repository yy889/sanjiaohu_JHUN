package cn.jhun.sanjiaohu;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 电费查询的运行时选择与候选项规则（与 eve_all/config.json 对应）。
 *
 * 号段表（宿舍楼 -> 照明/空调编号）由 {@link MeterData} / {@link MeterStore} 提供，
 * 这里只放「当前选中的楼/层/房」和下拉候选项范围。
 */
public final class DianfeiConfig {
    /** 可选楼层范围。 */
    public int floorFrom=2,floorTo=6;
    /** 每层的寝室号范围（末两位）。 */
    public int roomFrom=1,roomTo=25;
    /** 当前选中：宿舍楼名称、楼层、房号后两位。 */
    public String building="北区3舍";
    public int floor=4;
    public String roomNo="07";
    /** 低于该度数时告警（单位：度）。 */
    public double lowBalanceThreshold=20.0;
    /** 额外汇总到下拉里的寝室，如 "4-07"。 */
    public final List<String> rooms=new ArrayList<>();
    /** 固定参数。 */
    public String payProId="7033";
    public String schoolCode="1862";
    public String businessType="2";
    public String baseUrl="https://h5cloud.17wanxiao.com:18443/CloudPayment/user/getRoomState.do";
    public String referer="https://h5cloud.17wanxiao.com:18443/CloudPayment/bill/selectPayProject.do";

    /**
     * 解析寝室编号，支持 "4-07" / "4-7" / "4 07" / "407"。
     * 返回 {楼层, 两位房号}；无法解析返回 null。
     */
    public static int[] parseRoom(String spec){
        if(spec==null)return null;
        String value=spec.trim();
        if(value.isEmpty())return null;
        java.util.regex.Matcher dashed=java.util.regex.Pattern.compile("^(\\d)\\s*[-\\s]\\s*(\\d{1,2})$").matcher(value);
        if(dashed.matches()){
            int floor=Integer.parseInt(dashed.group(1));
            if(floor<1||floor>6)return null;
            return new int[]{floor,Integer.parseInt(dashed.group(2))};
        }
        java.util.regex.Matcher plain=java.util.regex.Pattern.compile("^(\\d)(\\d{2})$").matcher(value);
        if(plain.matches()){
            int floor=Integer.parseInt(plain.group(1));
            if(floor<1||floor>6)return null;
            return new int[]{floor,Integer.parseInt(plain.group(2))};
        }
        return null;
    }

    /** 把寝室列表规整成 "4-07" 形式，去掉写错的项。 */
    public List<String> normalizedRooms(){
        Set<String> out=new LinkedHashSet<>();
        for(String spec:rooms){
            int[] parsed=parseRoom(spec);
            if(parsed!=null)out.add(parsed[0]+"-"+pad(parsed[1]));
        }
        return new ArrayList<>(out);
    }

    /** 楼层下拉的候选项。 */
    public List<Integer> floorOptions(){
        int low=Math.max(1,Math.min(floorFrom,floorTo)),high=Math.min(6,Math.max(floorFrom,floorTo));
        List<Integer> out=new ArrayList<>();
        if(low>high)return out;
        for(int floor=low;floor<=high;floor++)out.add(floor);
        return out;
    }

    /** 指定楼层的寝室号候选项（末两位）。范围之外手写的寝室也会并进来。 */
    public List<String> roomOptions(int floor){
        int low=Math.max(1,Math.min(roomFrom,roomTo)),high=Math.min(99,Math.max(roomFrom,roomTo));
        Set<String> out=new LinkedHashSet<>();
        for(int room=low;room<=high;room++)out.add(pad(room));
        for(String spec:normalizedRooms())if(spec.startsWith(floor+"-"))out.add(spec.substring(spec.indexOf('-')+1));
        List<String> sorted=new ArrayList<>(out);
        java.util.Collections.sort(sorted);
        return sorted;
    }

    /** 完整房间号，如 4 楼 + "07" -> "407"。 */
    public String fullRoom(){return ""+floor+pad(roomNo);}

    /** 寝室号（末两位）补齐为两位。 */
    public static String pad(String roomNo){
        String value=roomNo==null?"":roomNo.trim();
        if(value.length()>=2)return value;
        return value.isEmpty()?"00":"0"+value;
    }

    static String pad(int room){return room<10?"0"+room:String.valueOf(room);}
}
