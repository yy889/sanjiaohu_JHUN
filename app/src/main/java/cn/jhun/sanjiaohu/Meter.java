package cn.jhun.sanjiaohu;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 电表表号（roomverify）的结构。
 *
 *     大分类 - 编号 -- 楼层 - 房间
 *       3   -  3   --  4   - 407
 *
 * **重要**：照明和空调是两套**独立**的编号体系，同一栋楼在两边编号不同。
 *
 *   北区3舍：照明 id=3    空调 id=15
 *   北区1舍：照明 id=1    空调 id=13
 *
 * 已实测确认：
 *   灯光 `3-3--4-407`   大分类3(北校区照明) 照明id3(北区3舍) 楼层4 房间407
 *   空调 `1-15--45-407` 大分类1(学生空调)   空调id15(北区3舍) 号段45 房间407
 *
 * 注意中间是**两个连字符** `--`，写成单个会返回 FAIL。
 */
public final class Meter {
    /** 宿舍楼名称，用于界面显示与跨表关联，如 "北区3舍"。 */
    public final String name;
    /** 照明编号（大分类 2/3 下使用）。null = 该楼无照明表。 */
    public final String lightId;
    /** 空调编号（大分类 1 下使用）。null = 该楼无空调表。 */
    public final String acId;
    /** 空调号段：楼层 -> 第三个数字。每栋楼不同，未配置的楼层会提示未配置。 */
    public final Map<Integer,String> acSeg;

    public Meter(String name,String lightId,String acId,Map<Integer,String> acSeg){
        this.name=name==null?"":name;
        this.lightId=blankToNull(lightId);
        this.acId=blankToNull(acId);
        this.acSeg=acSeg==null?Collections.<Integer,String>emptyMap():Collections.unmodifiableMap(new LinkedHashMap<>(acSeg));
    }

    /** 编号为空或字面量 "null" 都视为该楼没有这类表。 */
    private static String blankToNull(String value){
        if(value==null)return null;
        String trimmed=value.trim();
        if(trimmed.isEmpty()||"null".equalsIgnoreCase(trimmed))return null;
        return trimmed;
    }

    /** 灯光表号；无照明编号返回 null。实测所有楼的灯光第三段都等于楼层本身。 */
    public String lightRoomVerify(int floor,String room){
        if(lightId==null)return null;
        // 第 3 段与房间号首位必须同时等于楼层，只改一边会返回 FAIL。
        return "3-"+lightId+"--"+floor+"-"+floor+pad(room);
    }

    /** 空调表号；无空调编号或该楼层号段未配置时返回 null。 */
    public String acRoomVerify(int floor,String room){
        if(acId==null)return null;
        String segment=acSeg.get(floor);
        if(segment==null||segment.isEmpty())return null;
        return "1-"+acId+"--"+segment+"-"+floor+pad(room);
    }

    public boolean hasLight(){return lightId!=null;}
    public boolean hasAirConditioner(){return acId!=null;}

    /** 该楼支持的查询类型描述，用于界面提示。 */
    public String supportText(){
        if(lightId!=null&&hasConfiguredSegment())return "照明 + 空调";
        if(lightId!=null&&acId!=null)return "照明 + 空调(号段待填)";
        if(lightId!=null)return "仅照明";
        if(acId!=null)return "仅空调";
        return "未配置";
    }

    private boolean hasConfiguredSegment(){
        for(String value:acSeg.values())if(value!=null&&!value.isEmpty())return true;
        return false;
    }

    /** 房间号补齐为两位，如 "7" -> "07"。 */
    static String pad(String room){
        String value=room==null?"":room.trim();
        if(value.length()>=2)return value;
        return value.isEmpty()?"":(value.length()==1?"0"+value:value);
    }
}
