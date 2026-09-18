package cn.jhun.sanjiaohu;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 学校统一身份认证（CAS）登录，用来自动换取电表查询所需的 Cookie。
 *
 * 流程（复现 eve_all/src/CasLogin.kt 的实测链路）：
 *   1. GET  /authserver/login?service=...   拿 lt / execution / salt 和 JSESSIONID
 *   2. 用 salt 做 AES 加密密码，POST 表单
 *   3. CAS 验证通过后 302 回 service，并带上 ticket
 *   4. hub 校验 ticket 后返回页面，其中用 JS POST /bsacs/redirect.action
 *   5. 响应 JSON 的 url 指向 open.17wanxiao.com/api/authorize，再 302 到 h5cloud
 *   6. 访问缴费页 —— **关键**：SESSION 是在这一步才下发的
 *
 * ⚠ 密码只在内存中使用，不写入任何文件、不落日志。
 */
final class CasLogin {
    private CasLogin(){}

    private static final String AUTH_BASE="http://authserver.jhun.edu.cn";
    private static final String LOGIN_PATH="/authserver/login";
    private static final String HUB="https://hub.17wanxiao.com";

    /** 登录入口。ticket 最终会带回这个 service，其中的 flag 决定后续身份。 */
    static final String SERVICE=HUB+"/bsacs/light.action?flag=cassso30_jhdxjrxyjf&ecardFunc=index";

    /**
     * 缴费页。**必须访问它才能拿到 SESSION / sid**。
     *
     * 实测：CAS 登录只会在 authserver 下发 CASTGC、在 hub 下发 JSESSIONID，
     * 而电表查询真正需要的 SESSION 是访问这个页面时才由 h5cloud 下发的。
     */
    static final String PAY_PAGE=
        "https://h5cloud.17wanxiao.com:18443/CloudPayment/bill/selectPayProject.do"+
        "?txcode=2&interurl=substituted_pay&payProId=7033&amtflag=0&payamt="+
        "&payproname=%E7%94%A8%E7%94%B5%E6%94%AF%E5%87%BA"+
        "&img=https://payicons.59wanmei.com/cloudpayment/images/project/img-nav_2.png&subPayProId=";

    private static final String CHARS="ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678";
    private static final SecureRandom RANDOM=new SecureRandom();

    // 电表查询真正需要的三个 Cookie。
    private static final String[] WANTED={"SESSION","sid","b_host"};

    /** 登录结果。 */
    static final class Outcome {
        final String cookie,message;
        private Outcome(String cookie,String message){this.cookie=cookie;this.message=message;}
        boolean ok(){return cookie!=null;}
        static Outcome success(String cookie){return new Outcome(cookie,"");}
        static Outcome failure(String message){return new Outcome(null,message);}
    }

    /** 在单次登录过程中累积 Cookie 的极简 Cookie 罐。 */
    private static final class Jar {
        private final Map<String,String> cookies=new LinkedHashMap<>();
        void absorb(HttpURLConnection connection){
            Map<String,List<String>> headers=connection.getHeaderFields();
            if(headers==null)return;
            for(Map.Entry<String,List<String>> header:headers.entrySet()){
                if(header.getKey()==null||!"Set-Cookie".equalsIgnoreCase(header.getKey()))continue;
                for(String raw:header.getValue()){
                    if(raw==null)continue;
                    String pair=raw.split(";",2)[0].trim();
                    int equals=pair.indexOf('=');
                    if(equals<=0)continue;
                    String name=pair.substring(0,equals).trim(),value=pair.substring(equals+1).trim();
                    if(name.isEmpty())continue;
                    // 服务器用 Max-Age=0 清 Cookie 时同样要跟随。
                    if(value.isEmpty())cookies.remove(name);else cookies.put(name,value);
                }
            }
        }
        String header(){
            StringBuilder out=new StringBuilder();
            for(Map.Entry<String,String> cookie:cookies.entrySet()){
                if(out.length()>0)out.append("; ");
                out.append(cookie.getKey()).append('=').append(cookie.getValue());
            }
            return out.toString();
        }
        /** 只保留电表查询需要的 Cookie，避免把无关凭据写进存储。 */
        String meterCookie(){
            StringBuilder out=new StringBuilder();
            for(String name:WANTED){
                String value=cookies.get(name);
                if(value==null||value.isEmpty())continue;
                if(out.length()>0)out.append("; ");
                out.append(name).append('=').append(value);
            }
            return out.toString();
        }
        List<String> names(){return new ArrayList<>(cookies.keySet());}
    }

    /** 一个已打开的登录会话：先 open，再 submit。 */
    static final class Session {
        private final Jar jar;
        private final String loginUrl,username,salt,lt,execution,eventId;
        final boolean captchaNeeded;
        private Session(Jar jar,String loginUrl,String username,String salt,String lt,String execution,String eventId,boolean captchaNeeded){
            this.jar=jar;this.loginUrl=loginUrl;this.username=username;this.salt=salt;
            this.lt=lt;this.execution=execution;this.eventId=eventId;this.captchaNeeded=captchaNeeded;
        }
        Outcome submit(String password){
            return doSubmit(this,username,password);
        }
    }

    // ---------------- 加密 ----------------

    private static String randomString(int length){
        StringBuilder out=new StringBuilder(length);
        for(int i=0;i<length;i++)out.append(CHARS.charAt(RANDOM.nextInt(CHARS.length())));
        return out.toString();
    }

    /** 按页面的 encryptAES 规则加密密码。salt 为空时原样返回。 */
    static String encryptPassword(String password,String salt){
        if(salt==null||salt.trim().isEmpty())return password;
        try{
            // Base64( AES-CBC-PKCS7( 随机64字符 + 明文密码, key=salt, iv=随机16字符 ) )
            SecretKeySpec key=new SecretKeySpec(salt.trim().getBytes("UTF-8"),"AES");
            Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE,key,new IvParameterSpec(randomString(16).getBytes("UTF-8")));
            byte[] encrypted=cipher.doFinal((randomString(64)+password).getBytes("UTF-8"));
            return android.util.Base64.encodeToString(encrypted,android.util.Base64.NO_WRAP);
        }catch(Exception e){
            return password;
        }
    }

    // ---------------- 解析 ----------------

    private static String field(String html,String name){
        Matcher match=Pattern.compile("name=\""+Pattern.quote(name)+"\"[^>]*value=\"([^\"]*)\"").matcher(html);
        return match.find()?match.group(1):null;
    }

    private static String fieldById(String html,String id){
        Matcher match=Pattern.compile("id=\""+Pattern.quote(id)+"\"[^>]*value=\"([^\"]*)\"").matcher(html);
        return match.find()?match.group(1):null;
    }

    /**
     * 取加密 salt。
     *
     * 服务器会按 User-Agent 返回**两个版本**的登录页：
     *   桌面版: <input id="pwdDefaultEncryptSalt" value="...">
     *   手机版: <script> var pwdDefaultEncryptSalt = "..."; </script>
     *
     * 两种都要能解析 —— 只认 input 的话，手机版会拿到空 salt，
     * 结果是密码没加密直接提交，服务端判为密码错误。
     */
    static String readSalt(String html){
        String byId=fieldById(html,"pwdDefaultEncryptSalt");
        if(byId!=null&&!byId.trim().isEmpty())return byId;
        Matcher script=Pattern.compile("pwdDefaultEncryptSalt\\s*=\\s*[\"']([^\"']+)[\"']").matcher(html);
        if(script.find()&&!script.group(1).trim().isEmpty())return script.group(1);
        String byName=field(html,"pwdDefaultEncryptSalt");
        if(byName!=null&&!byName.trim().isEmpty())return byName;
        return "";
    }

    /** 从 HTML 里摘出给用户看的错误提示。 */
    static String errorText(String html){
        if(html==null)return null;
        String[] patterns={
            "id=\"showErrorTip\"[^>]*>([^<]+)<",
            "id=\"msg\"[^>]*>([^<]+)<",
            "class=\"auth_error\"[^>]*>([^<]+)<",
            "class=\"error\"[^>]*>([^<]+)<"
        };
        for(String pattern:patterns){
            Matcher match=Pattern.compile(pattern).matcher(html);
            if(match.find()){
                String text=match.group(1).trim();
                if(!text.isEmpty())return text;
            }
        }
        for(String keyword:new String[]{"用户名或密码错误","密码错误","验证码错误","账号被锁定","用户不存在","已锁定","登录失败","用户名不能为空"}){
            if(html.contains(keyword))return keyword;
        }
        return null;
    }

    // ---------------- 登录 ----------------

    /** 打开登录页，创建会话。会顺带问一次 needCaptcha.html 判断该账号是否需要验证码。 */
    static Session open(String username){
        Jar jar=new Jar();
        String loginUrl=AUTH_BASE+LOGIN_PATH+"?service="+encode(SERVICE);
        String page;
        try{
            page=send(jar,loginUrl,null,false);
        }catch(Exception e){
            return null;
        }
        if(page==null)return null;

        String salt=readSalt(page);
        if(salt.trim().isEmpty())return null;
        String lt=field(page,"lt");
        if(lt==null)return null;
        String execution=field(page,"execution");
        String eventId=field(page,"_eventId");

        boolean need=false;
        try{
            String answer=send(jar,AUTH_BASE+"/authserver/needCaptcha.html?username="+encode(username)+"&_="+System.currentTimeMillis(),loginUrl,true);
            need=answer!=null&&"true".equalsIgnoreCase(answer.trim());
        }catch(Exception ignored){}
        return new Session(jar,loginUrl,username,salt,lt,execution==null?"e1s1":execution,eventId==null?"submit":eventId,need);
    }

    /** 需要验证码时返回提示，调用方应改走网页登录。 */
    static Outcome login(String username,String password){
        if(username==null||username.trim().isEmpty())return Outcome.failure("请填写学号");
        if(password==null||password.isEmpty())return Outcome.failure("请填写密码");
        Session session=open(username.trim());
        if(session==null)return Outcome.failure("无法打开登录页，请检查网络或使用网页登录");
        if(session.captchaNeeded)return Outcome.failure("该账号需要验证码，请改用网页登录");
        return session.submit(password);
    }

    private static Outcome doSubmit(Session session,String username,String password){
        Jar jar=session.jar;
        String loginUrl=session.loginUrl;

        String form="username="+encode(username)
            +"&password="+encode(encryptPassword(password,session.salt))
            +"&passwordEncrypt="
            +"&lt="+encode(session.lt)
            +"&dllt=userNamePasswordLogin"
            +"&execution="+encode(session.execution)
            +"&_eventId="+encode(session.eventId)
            +"&rmShown=1";

        String body;
        String location;
        try{
            HttpURLConnection connection=post(jar,loginUrl,form,loginUrl);
            int status=connection.getResponseCode();
            location=connection.getHeaderField("Location");
            body=readBody(connection);
            // 仍停在登录页 -> 没通过。要把原因说清楚。
            if(status==200&&location==null){
                String error=errorText(body);
                if(error!=null)return Outcome.failure(error);
                if(session.captchaNeeded)return Outcome.failure("可能需要验证码，请改用网页登录");
                return Outcome.failure("账号或密码不正确");
            }
        }catch(Exception e){
            return Outcome.failure("提交登录失败："+e.getClass().getSimpleName());
        }

        // ---- 跟随跳转 ----
        String pending=location,current=loginUrl,lastError="";
        for(int hop=0;hop<15;hop++){
            String target=pending!=null?resolve(current,pending):current;
            pending=null;
            if(target==null)return Outcome.failure("跳转地址无法解析");
            try{
                HttpURLConnection connection=get(jar,target);
                int status=connection.getResponseCode();
                body=readBody(connection);
                current=connection.getURL().toString();
                String next=connection.getHeaderField("Location");
                connection.disconnect();
                if(next!=null&&status>=300&&status<=399){pending=next;continue;}
                break;
            }catch(Exception e){
                lastError=e.getClass().getSimpleName();
                break;
            }
        }

        // ---- hub 拿到 ticket 后用 JS 发起 redirect.action ----
        //
        // 少了这一步，就只会有 hub 自己的 JSESSIONID，永远拿不到 SESSION。
        String payUrl=null;
        if(body!=null&&body.contains("redirect.action")){
            String data=null;
            Matcher quoted=Pattern.compile("data\\s*:\\s*'data=([^']+)'").matcher(body);
            if(quoted.find())data=quoted.group(1);
            else{
                Matcher plain=Pattern.compile("data=([A-Za-z0-9%._\\-]+)").matcher(body);
                if(plain.find())data=plain.group(1);
            }
            if(data!=null){
                try{
                    HttpURLConnection connection=post(jar,HUB+"/bsacs/redirect.action","data="+data,current);
                    connection.setRequestProperty("X-Requested-With","XMLHttpRequest");
                    connection.setRequestProperty("Origin",HUB);
                    connection.setRequestProperty("Accept","*/*");
                    String json=readBody(connection);
                    Matcher url=Pattern.compile("\"url\"\\s*:\\s*\"([^\"]+)\"").matcher(json==null?"":json);
                    if(url.find())payUrl=url.group(1).replace("\\/","/").replace("\\u0026","&").replace("\\u003d","=");
                    else lastError="redirect.action 未返回跳转地址";
                }catch(Exception e){
                    lastError="redirect.action 失败 "+e.getClass().getSimpleName();
                }
            }else lastError="hub 页面里没找到 redirect 参数";
        }

        // ---- 沿跳转链走到 h5cloud，让 SESSION 落地 ----
        if(payUrl!=null){
            String next=payUrl;
            for(int hop=0;hop<10&&next!=null;hop++){
                try{
                    HttpURLConnection connection=get(jar,next);
                    int status=connection.getResponseCode();
                    body=readBody(connection);
                    current=connection.getURL().toString();
                    String location2=connection.getHeaderField("Location");
                    connection.disconnect();
                    next=(location2!=null&&status>=300&&status<=399)?resolve(current,location2):null;
                }catch(Exception e){
                    lastError="跳转 h5cloud 失败 "+e.getClass().getSimpleName();
                    break;
                }
            }
        }

        // ---- 再访问一次缴费页，确保 SESSION 已下发 ----
        try{
            HttpURLConnection connection=get(jar,PAY_PAGE);
            connection.setRequestProperty("Referer",HUB+"/");
            body=readBody(connection);
            current=connection.getURL().toString();
            connection.disconnect();
        }catch(Exception e){
            if(lastError.isEmpty())lastError="缴费页请求失败 "+e.getClass().getSimpleName();
        }

        // ---- 汇总 cookie ----
        String cookie=jar.meterCookie();
        if(!cookie.contains("SESSION=")){
            String detail=jar.names().isEmpty()?"（服务器未下发任何 Cookie）":"（只收到："+join(jar.names())+"）";
            String error=errorText(body);
            String why=lastError.isEmpty()?"":"；"+lastError;
            return Outcome.failure(error!=null?error:"登录成功但未拿到 SESSION "+detail+why);
        }
        return Outcome.success(cookie);
    }

    // ---------------- HTTP ----------------

    private static HttpURLConnection get(Jar jar,String address)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent",DianfeiApi.USER_AGENT);
        connection.setRequestProperty("Accept","text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        applyCookies(jar,connection);
        jar.absorb(connection);
        return connection;
    }

    private static HttpURLConnection post(Jar jar,String address,String form,String referer)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("User-Agent",DianfeiApi.USER_AGENT);
        connection.setRequestProperty("Content-Type","application/x-www-form-urlencoded");
        if(referer!=null)connection.setRequestProperty("Referer",referer);
        applyCookies(jar,connection);
        OutputStream output=connection.getOutputStream();
        try{output.write(form.getBytes("UTF-8"));}finally{try{output.close();}catch(Exception ignored){}}
        jar.absorb(connection);
        return connection;
    }

    private static String send(Jar jar,String address,String referer,boolean ajax)throws Exception{
        HttpURLConnection connection=get(jar,address);
        if(referer!=null)connection.setRequestProperty("Referer",referer);
        if(ajax)connection.setRequestProperty("X-Requested-With","XMLHttpRequest");
        connection.getResponseCode();
        String body=readBody(connection);
        connection.disconnect();
        return body;
    }

    private static void applyCookies(Jar jar,HttpURLConnection connection){
        String cookie=jar.header();
        if(!cookie.isEmpty())connection.setRequestProperty("Cookie",cookie);
    }

    private static String readBody(HttpURLConnection connection){
        InputStream input=null;
        try{
            input=connection.getInputStream();
        }catch(Exception e){
            input=connection.getErrorStream();
        }
        if(input==null)return "";
        try{
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            byte[] chunk=new byte[4096];
            int read;
            while((read=input.read(chunk))!=-1)buffer.write(chunk,0,read);
            return buffer.toString("UTF-8");
        }catch(Exception e){
            return "";
        }finally{
            try{input.close();}catch(Exception ignored){}
        }
    }

    private static String resolve(String base,String location){
        try{return new URL(new URL(base),location).toString();}catch(Exception e){return null;}
    }

    private static String join(List<String> values){
        StringBuilder out=new StringBuilder();
        for(String value:values){
            if(out.length()>0)out.append("、");
            out.append(value);
        }
        return out.toString();
    }

    private static String encode(String value){
        try{return URLEncoder.encode(value==null?"":value,"UTF-8");}catch(Exception e){return "";}
    }
}
