package cn.jhun.sanjiaohu;

import android.webkit.CookieManager;
import java.util.*;

/** Remove each site's cookies, preserving the other account's host and path scopes. */
final class SessionCookies {
    static void teaching(Runnable done){clear(new String[]{"jwxt.jhun.edu.cn"},new String[]{"/","/cas","/cas/","/frame","/frame/"},done);}
    static void identity(Runnable done){clear(new String[]{"authserver.jhun.edu.cn","ehall.jhun.edu.cn","hqfw.jhun.edu.cn"},new String[]{"/","/authserver","/authserver/","/new","/new/","/wsbx","/wsbx/","/wsbx/html/yd","/wsbx/html/yd/"},done);}
    /** Remove the dormitory electricity session, which stays scoped to its own host. */
    static void electricity(Runnable done){clear(new String[]{"h5cloud.17wanxiao.com:18443"},new String[]{"/"},done);}
    /**
     * Remove the dormitory electricity session, additionally naming the cookies the app
     * itself holds. The meter query runs outside the WebView jar, so a site-scoped sweep
     * alone can miss names the browser never stored.
     */
    static void electricity(java.util.List<String> cookies,Runnable done){
        java.util.Set<String> names=extractNames(cookies);
        if(names.isEmpty()){electricity(done);return;}
        clear(new String[]{"h5cloud.17wanxiao.com:18443"},new String[]{"/"},names,done);
    }
    /** Pull cookie names out of one or more "name=value; name=value" headers. */
    static java.util.Set<String> extractNames(java.util.List<String> cookies){
        java.util.Set<String> names=new java.util.HashSet<>();
        if(cookies==null)return names;
        for(String header:cookies){
            if(header==null)continue;
            for(String pair:header.split(";")){
                int equals=pair.indexOf('=');
                if(equals>0){
                    String name=pair.substring(0,equals).trim();
                    if(!name.isEmpty())names.add(name);
                }
            }
        }
        return names;
    }
    private static void clear(String[] authorities,String[] paths,Runnable done){clear(authorities,paths,null,done);}
    private static void clear(String[] authorities,String[] paths,Set<String> extraNames,Runnable done){
        CookieManager jar=CookieManager.getInstance();List<String[]> removals=new ArrayList<>();
        for(String authority:authorities){String host=java.net.URI.create("https://"+authority).getHost();android.webkit.WebStorage.getInstance().deleteOrigin("https://"+authority);android.webkit.WebStorage.getInstance().deleteOrigin("http://"+authority);Set<String> names=new HashSet<>();
            if(extraNames!=null)names.addAll(extraNames);
            for(String scheme:new String[]{"http","https"})for(String path:paths){String cookies=jar.getCookie(scheme+"://"+authority+path);if(cookies!=null)for(String cookie:cookies.split(";")){int eq=cookie.indexOf('=');if(eq>0)names.add(cookie.substring(0,eq).trim());}}
            // Cookie Domain has no port; WebStorage and request URLs retain the port.
            for(String name:names)for(String path:paths)for(String domain:new String[]{"","; Domain="+host,"; Domain=."+host})removals.add(new String[]{"https://"+authority+path,name+"=; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path="+path+domain});
        }
        removeNext(jar,removals,0,done);
    }
    private static void removeNext(CookieManager jar,List<String[]> work,int index,Runnable done){if(index==work.size()){jar.flush();done.run();return;}String[] item=work.get(index);jar.setCookie(item[0],item[1],ok->removeNext(jar,work,index+1,done));}
}
