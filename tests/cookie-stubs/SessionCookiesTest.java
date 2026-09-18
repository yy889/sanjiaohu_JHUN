package cn.jhun.sanjiaohu;
import android.webkit.*;
public final class SessionCookiesTest {
    public static void main(String[] args){
        boolean[] done={false};SessionCookies.teaching(()->done[0]=true);if(!done[0])throw new AssertionError("Missing completion");
        int checks=1;for(String write:CookieManager.INSTANCE.writes){if(!write.startsWith("https://jwxt.jhun.edu.cn/")||!write.contains("Max-Age=0"))throw new AssertionError("Teaching cleanup crossed scope");checks++;}
        for(String origin:WebStorage.INSTANCE.deleted){if(!origin.endsWith("://jwxt.jhun.edu.cn"))throw new AssertionError("Wrong storage removed");checks++;}
        CookieManager.INSTANCE.writes.clear();WebStorage.INSTANCE.deleted.clear();done[0]=false;SessionCookies.identity(()->done[0]=true);if(!done[0])throw new AssertionError("Missing completion");checks++;
        for(String write:CookieManager.INSTANCE.writes){if(write.contains("jwxt")||write.contains("gis.jhun")||write.contains("Domain=.jhun.edu.cn")||!write.contains("Max-Age=0"))throw new AssertionError("Identity cleanup crossed scope");checks++;}
        for(String origin:WebStorage.INSTANCE.deleted){if(origin.contains("jwxt")||origin.contains("gis.jhun"))throw new AssertionError("Other account storage removed");checks++;}
        // 用电缴费已移除：统一认证清理不得再触碰任何 17wanxiao 站点。
        for(String origin:WebStorage.INSTANCE.deleted){if(origin.contains("17wanxiao"))throw new AssertionError("Identity cleanup must not touch electricity hosts");checks++;}
        for(String write:CookieManager.INSTANCE.writes){if(write.contains("17wanxiao"))throw new AssertionError("Identity cleanup must not touch electricity cookies");checks++;}
        // 返回报修落地页后不留下缴费站点。
        if(!WebStorage.INSTANCE.deleted.contains("https://hqfw.jhun.edu.cn"))throw new AssertionError("Repair storage should be cleared");checks++;
        CookieManager.INSTANCE.writes.clear();WebStorage.INSTANCE.deleted.clear();done[0]=false;
        SessionCookies.electricity(java.util.Arrays.asList("sid=synthetic; SESSION=synthetic; b_host=synthetic"),()->done[0]=true);
        if(!done[0])throw new AssertionError("Missing electricity completion");checks++;
        if(!WebStorage.INSTANCE.deleted.contains("https://h5cloud.17wanxiao.com:18443"))throw new AssertionError("Meter storage retained");checks++;
        // 电费会话只清电表主机自己的路径，且 Cookie Domain 不含端口。
        for(String write:CookieManager.INSTANCE.writes){
            if(!write.startsWith("https://h5cloud.17wanxiao.com:18443/"))throw new AssertionError("Electricity cleanup crossed host: "+write);checks++;
            if(!write.contains("Max-Age=0"))throw new AssertionError("Electricity cookie not expired");checks++;
            if(write.contains("Domain=h5cloud.17wanxiao.com:18443")||write.contains("Domain=.h5cloud.17wanxiao.com:18443"))throw new AssertionError("Cookie Domain incorrectly includes port");checks++;
        }
        for(String name:new String[]{"SESSION","sid","b_host"})if(CookieManager.INSTANCE.writes.stream().noneMatch(s->s.contains(name+"=; Max-Age=0")))throw new AssertionError("App-held cookie not cleared: "+name);checks++;
        for(String write:CookieManager.INSTANCE.writes){if(write.contains("17wanxiao.com/bsacs")||write.contains("open.17wanxiao.com")||write.contains("wapnew.17wanxiao.com"))throw new AssertionError("Removed payment hosts must not be touched");checks++;}
        System.out.println("Session cleanup scopes: "+checks+" fixture checks passed");
    }
}
