package cn.jhun.sanjiaohu;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置宿舍楼号段表（来自实测数据，与 eve_all/meters.json 一致）。
 *
 * 左侧照明编号来自「第二个数字」接口，右侧空调编号来自「空调」接口，按**楼名**配对
 * （两边编号体系不同）。
 *
 * acSeg 目前只填了实测确认的北区3舍（42~47），其余楼层的号段需要实测后补。
 */
final class MeterData {
    private MeterData(){}

    static final Map<String,String> CATEGORIES;
    static {
        Map<String,String> categories=new LinkedHashMap<>();
        categories.put("1","学生空调/南区1-12舍、北区18-24舍空调和照明/商住区");
        categories.put("2","南校区照明");
        categories.put("3","北校区照明");
        CATEGORIES=Collections.unmodifiableMap(categories);
    }

    /** 北区3舍实测确认的空调号段：楼层 -> 第三个数字。 */
    static Map<Integer,String> north3Segments(){
        Map<Integer,String> segments=new LinkedHashMap<>();
        segments.put(1,"42");segments.put(2,"43");segments.put(3,"44");
        segments.put(4,"45");segments.put(5,"46");segments.put(6,"47");
        return segments;
    }

    static List<Meter> defaultBuildings(){
        List<Meter> list=new ArrayList<>();
        // 北区（照明 id 齐全）
        list.add(meter("北区1舍","1","13"));
        list.add(meter("北区2舍","2","14"));
        list.add(new Meter("北区3舍","3","15",north3Segments()));
        list.add(meter("北区4舍","4","16"));
        list.add(meter("北区5舍","5","17"));
        list.add(meter("北区6舍","6","18"));
        list.add(meter("北区7舍","7","19"));
        list.add(meter("北区8舍","8","20"));
        list.add(meter("北区9舍","9","21"));
        list.add(meter("北区10舍","10","22"));
        list.add(meter("北区11舍","11","23"));
        list.add(meter("北区12舍","12","24"));
        list.add(meter("北区13舍","13","25"));
        list.add(meter("北区14舍","14","26"));
        list.add(meter("北区15A舍","15A","27"));
        list.add(meter("北区15B舍","15B","28"));
        list.add(meter("北区16舍","16","29"));
        list.add(meter("北区17舍","17","30"));
        list.add(meter("北区18舍","18","31"));
        // 以下来自空调列表，照明列表里没有；照明 id 待补
        list.add(meter("北区19舍",null,"32"));
        list.add(meter("北区20舍",null,"33"));
        list.add(meter("北区21舍",null,"35"));
        list.add(meter("北区22舍",null,"37"));
        list.add(meter("北区23舍",null,"36"));
        list.add(meter("北区24舍",null,"34"));
        // 食堂公寓
        list.add(meter("食堂公寓照明","19",null));
        list.add(meter("食堂公寓空调","20",null));
        // 南区：只有空调列表给了编号，照明 id 待补
        for(int i=1;i<=12;i++)list.add(meter("南区"+i+"舍",null,String.valueOf(i)));
        return Collections.unmodifiableList(list);
    }

    private static Meter meter(String name,String lightId,String acId){
        return new Meter(name,lightId,acId,null);
    }

    /** 默认候选项：可选楼层与每层寝室号范围。 */
    static final List<Integer> DEFAULT_FLOORS=Collections.unmodifiableList(Arrays.asList(2,3,4,5,6));
}
