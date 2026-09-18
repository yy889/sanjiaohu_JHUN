package cn.jhun.sanjiaohu;

import java.util.List;

/** 电费接口解析、候选项规则与号段表读写的回归检查（全部使用合成数据）。 */
public final class DianfeiApiTest {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}

    public static void main(String[] args){
        // ---- 成功响应 ----
        DianfeiApi.Result ok=DianfeiApi.interpret("{\"returncode\":\"100\",\"returnmsg\":\"SUCCESS\",\"quantity\":\"52.90\",\"quantityunit\":\"度\",\"canbuy\":\"true\",\"description\":\"407\"}");
        check(ok.ok,"100 应判为成功");
        check(Math.abs(ok.quantity-52.90)<1e-9,"quantity 应解析为 52.90");
        check("度".equals(ok.unit),"单位应为度");
        check("407".equals(ok.description),"description 应为 407");
        check(ok.canBuy,"canbuy=true 应被识别");
        check(!ok.sessionExpired(),"成功不应判为会话失效");

        // ---- 真实读数为 0：房间存在，电量真的是 0，不是失败 ----
        DianfeiApi.Result zero=DianfeiApi.interpret("{\"returncode\":\"100\",\"returnmsg\":\"SUCCESS\",\"quantity\":\"0.0\",\"quantityunit\":\"度\",\"canbuy\":\"true\",\"description\":\"222\"}");
        check(zero.ok&&zero.quantity==0.0,"读数为 0 是真实的没电，不是查询失败");
        check(zero.canBuy,"读数为 0 时 canbuy 仍为 true");

        // ---- 房间不存在：code=FAIL ----
        DianfeiApi.Result fail=DianfeiApi.interpret("{\"returncode\":\"FAIL\",\"returnmsg\":null,\"quantity\":null,\"canbuy\":\"false\",\"description\":null}");
        check(!fail.ok,"FAIL 应判为失败");
        check("FAIL".equals(fail.code),"失败码应为 FAIL");
        check(!fail.sessionExpired(),"表号错误不是会话失效");

        // ---- 会话失效：非 JSON 的“系统繁忙”文本 ----
        for(String body:new String[]{"系统繁忙，请稍后重试","<html>RspBaseVO</html>","  系统繁忙  "}){
            DianfeiApi.Result expired=DianfeiApi.interpret(body);
            check(!expired.ok,"非 JSON 应判为失败");
            check(expired.sessionExpired(),"非 JSON 应判为会话失效");
            check(expired.message.contains("重新登录"),"会话失效应提示重新登录");
        }

        // ---- 空响应 ----
        check(!DianfeiApi.interpret("").ok,"空响应应判为失败");
        check("EMPTY".equals(DianfeiApi.interpret("   ").code),"空白响应码应为 EMPTY");

        // ---- 数字字段不带引号也要能解析 ----
        DianfeiApi.Result bare=DianfeiApi.interpret("{\"returncode\":\"100\",\"quantity\":52.9,\"description\":\"407\"}");
        check(bare.ok&&Math.abs(bare.quantity-52.9)<1e-9,"裸数字 quantity 应可解析");
        check("度".equals(bare.unit),"缺省单位应回退为度");

        // ---- 字段抽取：不得把别的键的值串进来 ----
        String payload="{\"quantity\":\"12.5\",\"quantityunit\":\"度\",\"canbuy\":\"true\",\"description\":\"407\"}";
        check("12.5".equals(DianfeiApi.field(payload,"quantity")),"应取到 quantity");
        check("度".equals(DianfeiApi.field(payload,"quantityunit")),"quantity 与 quantityunit 不能混淆");
        check(DianfeiApi.field(payload,"missing")==null,"缺失字段应返回 null");
        check(DianfeiApi.field("{\"returnmsg\":null}","returnmsg")==null,"null 应返回 null");
        // 子串键名不应被误当作目标键。
        check(DianfeiApi.field("{\"xquantity\":\"9\"}","quantity")==null,"子串键名不应命中");

        // ---- 寝室编号解析 ----
        for(String spec:new String[]{"4-07","4-7","4 07","407"}){
            int[] parsed=DianfeiConfig.parseRoom(spec);
            check(parsed!=null&&parsed[0]==4&&parsed[1]==7,"应解析 "+spec);
        }
        for(String spec:new String[]{null,"","0-07","7-07","4-007","abc","4-"}){
            check(DianfeiConfig.parseRoom(spec)==null,"不应解析 "+spec);
        }

        // ---- 候选项规则 ----
        DianfeiConfig config=new DianfeiConfig();
        config.floorFrom=2;config.floorTo=6;
        check(config.floorOptions().size()==5,"默认楼层候选项应为 2~6");
        check(config.floorOptions().get(0)==2,"楼层起点应为 2");
        config.floorFrom=6;config.floorTo=2;
        check(config.floorOptions().size()==5&&config.floorOptions().get(0)==2,"楼层范围应自动纠正顺序");
        config.roomFrom=1;config.roomTo=25;
        check(config.roomOptions(4).size()==25,"默认寝室候选项应为 25 个");
        check("07".equals(config.roomOptions(4).get(6)),"寝室号应补零");
        // 范围之外手写的寝室要并进来。
        config.rooms.add("4-30");
        check(config.roomOptions(4).contains("30"),"手写的额外寝室应出现在候选项中");
        check(!config.roomOptions(5).contains("30"),"额外寝室只应出现在对应楼层");
        config.rooms.add("写错的项");
        check(config.normalizedRooms().size()==1,"写错的寝室项应被丢弃");

        config.floor=4;config.roomNo="07";
        check("407".equals(config.fullRoom()),"完整房间号应为 407");
        config.roomNo="7";
        check("407".equals(config.fullRoom()),"单位数房号应补齐");

        // ---- Cookie 可用性判定 ----
        check(DianfeiStore.usableCookie("SESSION=abc; sid=x; b_host=y"),"含 SESSION 的 Cookie 应可用");
        check(DianfeiStore.usableCookie("sid=x; SESSION = abc"),"SESSION 前后空格应容忍");
        check(!DianfeiStore.usableCookie("sid=x; b_host=y"),"缺少 SESSION 应判为不可用");
        check(!DianfeiStore.usableCookie("SESSION="),"空 SESSION 应判为不可用");
        check(!DianfeiStore.usableCookie(null),"null Cookie 应判为不可用");
        check(!DianfeiStore.usableCookie("MYSESSION=abc"),"子串不应被误认为 SESSION");

        // ---- 支持查询的楼层：不得为未配置号段的楼层返回楼层 ----
        Meter north3=new Meter("北区3舍","3","15",MeterData.north3Segments());
        List<Integer> lightFloors=DianfeiStore.floorsFor(north3,java.util.Arrays.asList(2,3,4,5,6),true);
        check(lightFloors.size()==5,"有照明表的楼所有楼层都可查");
        List<Integer> acFloors=DianfeiStore.floorsFor(north3,java.util.Arrays.asList(2,3,4,5,6),false);
        check(acFloors.size()==5,"北区3舍 2~6 层号段都已配置");
        Meter north1=new Meter("北区1舍","1","13",null);
        check(DianfeiStore.floorsFor(north1,java.util.Arrays.asList(2,3,4,5,6),false).isEmpty(),"号段未配置的楼不应给出空调楼层");
        check(DianfeiStore.floorsFor(north1,java.util.Arrays.asList(2,3,4,5,6),true).size()==5,"有照明表的楼仍可给照明楼层");

        // ---- 号段表 JSON 往返 ----
        List<Meter> original=MeterData.defaultBuildings();
        String json=MeterStore.toJson(original);
        List<Meter> parsed=MeterStore.parse(json);
        check(parsed!=null&&parsed.size()==original.size(),"号段表应能往返，实际 "+(parsed==null?-1:parsed.size()));
        Meter parsedNorth3=null;
        for(Meter meter:parsed)if(meter.name.equals("北区3舍"))parsedNorth3=meter;
        check(parsedNorth3!=null,"号段表往返后应保留北区3舍");
        check("3".equals(parsedNorth3.lightId)&&"15".equals(parsedNorth3.acId),"往返后编号应保持");
        check(north3.acRoomVerify(4,"07").equals(parsedNorth3.acRoomVerify(4,"07")),"往返后 4 楼空调表号应一致");
        check(north3.acRoomVerify(6,"07").equals(parsedNorth3.acRoomVerify(6,"07")),"往返后 6 楼空调表号应一致");
        // 只有空调表的楼往返后不能被当成有照明表。
        Meter parsedSouth1=null;
        for(Meter meter:parsed)if(meter.name.equals("南区1舍"))parsedSouth1=meter;
        check(parsedSouth1!=null&&parsedSouth1.lightId==null,"往返后 null 照明 id 应保持为 null");
        check(parsedSouth1.lightRoomVerify(4,"07")==null,"往返后南区1舍仍不应有照明表号");

        // 损坏的输入应回退为 null，由调用方使用内置默认值。
        check(MeterStore.parse("not json")==null,"损坏内容应返回 null");
        check(MeterStore.parse("{\"buildings\":[]}")==null,"空表应返回 null");
        check(MeterStore.parse(null)==null,"null 应返回 null");

        System.out.println("Dianfei API and config: "+checks+" checks passed (synthetic meter data)");
    }
}
