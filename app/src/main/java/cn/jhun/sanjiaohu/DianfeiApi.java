package cn.jhun.sanjiaohu;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 电表查询接口客户端（与 eve_all/src/Api.kt 对应）。
 *
 * 接口: getRoomState.do
 * 返回: {"returncode":"100","quantity":"52.90","quantityunit":"度","description":"407",...}
 *   returncode=100  -> 成功
 *   returncode=FAIL -> roomverify 不对
 *   非 JSON 的 "系统繁忙" 文本 -> Cookie 失效
 *
 * quantity 的单位是**度（kWh）**，不是元。
 */
final class DianfeiApi {
    private DianfeiApi(){}

    /** 一次查询的结果。 */
    static final class Result {
        final boolean ok;
        final double quantity;
        final String unit,description,code,message;
        final boolean canBuy;

        private Result(boolean ok,double quantity,String unit,String description,boolean canBuy,String code,String message){
            this.ok=ok;this.quantity=quantity;this.unit=unit;this.description=description;
            this.canBuy=canBuy;this.code=code;this.message=message;
        }
        static Result success(double quantity,String unit,String description,boolean canBuy){
            return new Result(true,quantity,unit,description,canBuy,"100","");
        }
        static Result failure(String code,String message){
            return new Result(false,0,"度","",false,code,message);
        }
        /** 会话失效是唯一需要重新登录的失败类型。 */
        boolean sessionExpired(){return "NOT_JSON".equals(code);}
    }

    static final String USER_AGENT=
        "Mozilla/5.0 (Linux; Android 16; 2510DRK44C Build/BP2A.250605.031.A3; wv) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/143.0.7499.192 " +
        "Mobile Safari/537.36 cpdaily/9.9.20 wisedu/9.9.20";

    /** 查询一个电表。阻塞调用，必须在工作线程执行。 */
    static Result query(DianfeiConfig config,String cookie,String roomVerify){
        String address=config.baseUrl
            +"?payProId="+encode(config.payProId)
            +"&schoolcode="+encode(config.schoolCode)
            +"&businesstype="+encode(config.businessType)
            +"&roomverify="+encode(roomVerify);
        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)new URL(address).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(25000);
            connection.setRequestProperty("User-Agent",USER_AGENT);
            connection.setRequestProperty("Accept","application/json");
            connection.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7");
            connection.setRequestProperty("X-Requested-With","XMLHttpRequest");
            connection.setRequestProperty("Referer",config.referer);
            connection.setRequestProperty("Cookie",cookie);
            String body=read(connection);
            return interpret(body);
        }catch(Exception e){
            return Result.failure("NET","网络错误: "+e.getClass().getSimpleName()+(e.getMessage()==null?"":" "+e.getMessage()));
        }finally{
            if(connection!=null)connection.disconnect();
        }
    }

    /** 把接口正文解释成结果。独立出来便于用固定样本做回归测试。 */
    static Result interpret(String body){
        if(body==null||body.trim().isEmpty())return Result.failure("EMPTY","服务器返回空内容");

        // 非 JSON -> 通常是 "系统繁忙，请稍后重试"，即会话失效
        if(!body.trim().startsWith("{")){
            String hint=body.contains("系统繁忙")||body.contains("RspBaseVO")
                ?"会话已失效，请重新登录后重试"
                :truncate(body,200);
            return Result.failure("NOT_JSON",hint);
        }

        String code=field(body,"returncode");
        String message=field(body,"returnmsg");
        String quantity=field(body,"quantity");
        String unit=field(body,"quantityunit");
        boolean canBuy="true".equalsIgnoreCase(field(body,"canbuy"));

        if("100".equals(code)){
            double value=0;
            try{if(quantity!=null)value=Double.parseDouble(quantity);}catch(NumberFormatException ignored){}
            return Result.success(value,unit==null||unit.isEmpty()?"度":unit,orEmpty(field(body,"description")),canBuy);
        }
        String reason;
        if("FAIL".equals(code))reason="电表编号无效（roomverify 格式或数值不对）";
        else if(body.contains("系统繁忙"))reason="会话已失效，请重新登录后重试";
        else if(message!=null&&!message.isEmpty())reason=message;
        else reason="接口返回 "+(code==null||code.isEmpty()?"未知":code);
        return Result.failure(code==null?"":code,reason);
    }

    /**
     * 从 JSON 文本里取字符串或数字字段，不引第三方库。
     * 该接口的字段都是扁平标量，因此无需完整 JSON 解析器。
     */
    static String field(String text,String key){
        Matcher match=Pattern.compile("\""+Pattern.quote(key)+"\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|null|(-?[0-9.eE+]+))").matcher(text);
        if(!match.find())return null;
        String whole=match.group(1);
        if(whole==null||"null".equals(whole))return null;
        if(whole.startsWith("\""))return match.group(2);
        String bare=match.group(3);
        return bare==null||bare.isEmpty()?null:bare;
    }

    private static String orEmpty(String value){return value==null?"":value;}
    private static String truncate(String value,int limit){return value.length()<=limit?value:value.substring(0,limit);}
    private static String encode(String value){
        try{return URLEncoder.encode(value==null?"":value,"UTF-8");}catch(Exception e){return "";}
    }

    private static String read(HttpURLConnection connection)throws Exception{
        InputStream input=null;
        try{
            int status=connection.getResponseCode();
            input=status>=400?connection.getErrorStream():connection.getInputStream();
            if(input==null)return "";
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            byte[] chunk=new byte[4096];
            int read;
            while((read=input.read(chunk))!=-1)buffer.write(chunk,0,read);
            return buffer.toString("UTF-8");
        }finally{
            if(input!=null)try{input.close();}catch(Exception ignored){}
        }
    }
}
