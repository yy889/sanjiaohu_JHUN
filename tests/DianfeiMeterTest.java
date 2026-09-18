package cn.jhun.sanjiaohu;

import java.util.List;

/** 电表表号生成与宿舍楼号段表的回归检查（使用合成数据，不含真实电量）。 */
public final class DianfeiMeterTest {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}

    public static void main(String[] args){
        List<Meter> buildings=MeterData.defaultBuildings();
        check(buildings.size()==39,"内置号段表应包含 39 栋宿舍楼，实际 "+buildings.size());

        // ---- 照明表号：第 3 段与房间号首位必须同时等于楼层 ----
        Meter north3=new Meter("北区3舍","3","15",MeterData.north3Segments());
        check("3-3--4-407".equals(north3.lightRoomVerify(4,"07")),"北区3舍 4 楼灯光应为 3-3--4-407");
        check("3-3--1-107".equals(north3.lightRoomVerify(1,"07")),"1 楼灯光应为 3-3--1-107");
        check("3-3--6-607".equals(north3.lightRoomVerify(6,"07")),"6 楼灯光应为 3-3--6-607");
        check("3-3--5-507".equals(north3.lightRoomVerify(5,"7")),"房间号应补齐为两位");
        // 中间必须是两个连字符。
        check(!north3.lightRoomVerify(4,"07").equals("3-3-4-407"),"中间必须是两个连字符 --");

        // ---- 空调表号：第三段来自号段表 ----
        check("1-15--45-407".equals(north3.acRoomVerify(4,"07")),"北区3舍 4 楼空调应为 1-15--45-407");
        check("1-15--42-107".equals(north3.acRoomVerify(1,"07")),"1 层号段应为 42");
        check("1-15--47-607".equals(north3.acRoomVerify(6,"07")),"6 层号段应为 47");
        // 号段没配置的楼层必须返回 null，不能发无效请求。
        check(null==north3.acRoomVerify(7,"07"),"无号段的楼层应返回 null");

        Meter north1=new Meter("北区1舍","1","13",null);
        check("3-1--4-407".equals(north1.lightRoomVerify(4,"07")),"北区1舍照明 id 应为 1");
        check(null==north1.acRoomVerify(4,"07"),"号段未配置时空调表号应为 null");
        check("照明 + 空调(号段待填)".equals(north1.supportText()),"号段待填的提示文案");

        // ---- 只有空调表 / 只有照明表的楼 ----
        Meter south1=new Meter("南区1舍",null,"1",null);
        check(null==south1.lightRoomVerify(4,"07"),"南区1舍没有照明表");
        check(!south1.hasLight()&&south1.hasAirConditioner(),"南区1舍应只有空调表");
        check("仅空调".equals(south1.supportText()),"仅空调的提示文案");

        Meter canteen=new Meter("食堂公寓照明","19",null,null);
        check("3-19--4-407".equals(canteen.lightRoomVerify(4,"07")),"食堂公寓照明表号");
        check(null==canteen.acRoomVerify(4,"07"),"食堂公寓照明没有空调表");
        check("仅照明".equals(canteen.supportText()),"仅照明的提示文案");

        // ---- 编号体系：同一栋楼照明与空调 id 不同 ----
        for(Meter meter:buildings){
            if(meter.lightId!=null&&meter.acId!=null){
                if(meter.name.equals("北区3舍"))check(!"15".equals(meter.lightId),"北区3舍照明 id 不是空调 id");
                check(meter.lightRoomVerify(4,"07").startsWith("3-"+meter.lightId+"--"),meter.name+" 照明表号前缀");
                if(!meter.acSeg.isEmpty())check(meter.acRoomVerify(4,"07").startsWith("1-"+meter.acId+"--"),meter.name+" 空调表号前缀");
            }
        }

        // ---- 遍历各楼各层：能生成的表号结构必须合法 ----
        int generated=0;
        for(Meter meter:buildings){
            for(int floor=1;floor<=6;floor++){
                String light=meter.lightRoomVerify(floor,"07");
                if(light!=null){
                    generated++;
                    check(light.matches("3-[0-9A-Za-z]+--"+floor+"-"+floor+"07"),"非法照明表号 "+light);
                }
                String ac=meter.acRoomVerify(floor,"07");
                if(ac!=null){
                    generated++;
                    check(ac.matches("1-[0-9A-Za-z]+--\\d+-"+floor+"07"),"非法空调表号 "+ac);
                }
            }
        }
        // 1~6 层 x 39 栋：有照明表的楼出灯光表号，空调号段只配了北区3舍。
        check(generated>=120&&generated<=260,"表号生成数量应在合理范围，实际 "+generated);

        // ---- 内置数据与 eve_all/meters.json 的关键条目一致 ----
        check("3".equals(find(buildings,"北区3舍").lightId),"北区3舍照明 id");
        check("15".equals(find(buildings,"北区3舍").acId),"北区3舍空调 id");
        check("13".equals(find(buildings,"北区1舍").acId),"北区1舍空调 id");
        check("27".equals(find(buildings,"北区15A舍").acId),"北区15A舍空调 id");
        check("1".equals(find(buildings,"南区1舍").acId),"南区1舍空调 id");
        check(find(buildings,"南区1舍").lightId==null,"南区1舍没有照明 id");
        check("19".equals(find(buildings,"食堂公寓照明").lightId),"食堂公寓照明 id");

        System.out.println("Dianfei meters: "+checks+" checks passed (synthetic meter numbers)");
    }

    static Meter find(List<Meter> buildings,String name){
        for(Meter meter:buildings)if(meter.name.equals(name))return meter;
        throw new AssertionError("missing building "+name);
    }
}
