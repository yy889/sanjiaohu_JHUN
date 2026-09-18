package cn.jhun.sanjiaohu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 号段表（宿舍楼 -> 照明/空调编号）的解析与序列化。
 *
 * 采用与项目其余部分一致的做法：手写极简 JSON，不引入任何外部依赖。
 * 文件格式与 eve_all/meters.json 相同：
 * <pre>
 * {"buildings":[{"name":"北区3舍","lightId":"3","acId":"15","acSeg":{"4":"45"}}]}
 * </pre>
 */
final class MeterStore {
    private MeterStore(){}

    private static String quote(String value){
        String escaped=value.replace("\\","\\\\").replace("\"","\\\"")
            .replace("\n","\\n").replace("\r","").replace("\t","\\t");
        return "\""+escaped+"\"";
    }

    static String toJson(List<Meter> buildings){
        StringBuilder json=new StringBuilder();
        json.append("{\n");
        json.append("  \"_说明\": \"宿舍楼号段表。lightId=照明编号, acId=空调编号, 两者编号体系不同。acSeg 是楼层->空调第三段。\",\n");
        json.append("  \"buildings\": [\n");
        for(int i=0;i<buildings.size();i++){
            Meter meter=buildings.get(i);
            json.append("    {");
            json.append("\"name\": ").append(quote(meter.name)).append(", ");
            json.append("\"lightId\": ").append(meter.lightId==null?"null":quote(meter.lightId)).append(", ");
            json.append("\"acId\": ").append(meter.acId==null?"null":quote(meter.acId)).append(", ");
            json.append("\"acSeg\": {");
            boolean first=true;
            for(Map.Entry<Integer,String> entry:meter.acSeg.entrySet()){
                if(!first)json.append(", ");
                first=false;
                json.append(quote(String.valueOf(entry.getKey()))).append(": ").append(quote(entry.getValue()));
            }
            json.append("}}");
            if(i!=buildings.size()-1)json.append(",");
            json.append("\n");
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    /** 解析号段表；解析不出任何有效楼时返回 null，由调用方回退到内置默认值。 */
    static List<Meter> parse(String text){
        if(text==null)return null;
        Matcher array=Pattern.compile("\"buildings\"\\s*:\\s*\\[(.*)]",Pattern.DOTALL).matcher(text);
        if(!array.find())return null;

        // 按顶层 {} 切对象。两个易错点：
        //  1) 嵌套大括号要保留，否则 acSeg 匹配不到
        //  2) 每个新对象必须清空缓冲，否则内容会累积
        List<String> objects=new ArrayList<>();
        int depth=0;StringBuilder current=new StringBuilder();
        for(int i=0;i<array.group(1).length();i++){
            char ch=array.group(1).charAt(i);
            if(ch=='{'){depth++;if(depth==1)current.setLength(0);else current.append(ch);}
            else if(ch=='}'){depth--;if(depth==0)objects.add(current.toString());else current.append(ch);}
            else if(depth>=1)current.append(ch);
        }

        List<Meter> buildings=new ArrayList<>();
        for(String object:objects){
            String name=string(object,"name");
            if(name==null||name.trim().isEmpty())continue;
            Map<Integer,String> segments=new LinkedHashMap<>();
            Matcher body=Pattern.compile("\"acSeg\"\\s*:\\s*\\{([^}]*)\\}").matcher(object);
            if(body.find()){
                Matcher pairs=Pattern.compile("\"(\\d+)\"\\s*:\\s*\"([^\"]+)\"").matcher(body.group(1));
                while(pairs.find()){
                    try{segments.put(Integer.parseInt(pairs.group(1)),pairs.group(2));}catch(NumberFormatException ignored){}
                }
            }
            buildings.add(new Meter(name,string(object,"lightId"),string(object,"acId"),segments));
        }
        return buildings.isEmpty()?null:buildings;
    }

    /** 取 "key": "value" 里的 value（不匹配 null）。 */
    private static String string(String text,String key){
        Matcher match=Pattern.compile("\""+Pattern.quote(key)+"\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(text);
        if(!match.find())return null;
        return match.group(1)
            .replace("\\n","\n").replace("\\t","\t")
            .replace("\\\"","\"").replace("\\\\","\\");
    }
}
