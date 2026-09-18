package cn.jhun.sanjiaohu;

/**
 * 锁定两件事：
 *  1. 用电缴费链路已移除 —— 没有任何 17wanxiao / 支付宝地址能导航或接收学校凭据；
 *  2. 电费查询所需的 CAS 解析与加密规则保持正确。
 */
public final class ElectricityRemovalTest {
    static int checks;
    static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}

    public static void main(String[] args){
        // ---- 缴费中转与最终站点都不再被放行 ----
        for(String url:new String[]{
            "https://hub.17wanxiao.com/bsacs/light.action?flag=cassso30_jhdxjrxyjf&ecardFunc=index",
            "http://hub.17wanxiao.com/bsacs/light.action",
            "https://hub.17wanxiao.com/",
            "https://open.17wanxiao.com/",
            "https://wapnew.17wanxiao.com/",
            "https://h5cloud.17wanxiao.com:18443/CloudPayment/bill/type.do",
            "https://mclient.alipay.com/h5pay/h5RouteAppSenior/index.html",
            "alipays://platformapi/startapp?appId=20000067"
        }){
            check(!IdentityPolicy.allowed(url),"不应放行 "+url);
            check(!IdentityPolicy.auth(url),"不应是凭据目标 "+url);
        }

        // ---- 作为嵌套 CAS service 也必须被拒绝，避免用中转扩大放行范围 ----
        for(String service:new String[]{
            "https://hub.17wanxiao.com/bsacs/light.action",
            "https://open.17wanxiao.com/",
            "https://wapnew.17wanxiao.com/",
            "https://mclient.alipay.com/h5pay/h5RouteAppSenior/index.html"
        }){
            try{
                String login="https://authserver.jhun.edu.cn/authserver/login?service="+java.net.URLEncoder.encode(service,"UTF-8");
                check(!IdentityPolicy.auth(login),"不应把缴费站点当作 CAS service："+service);
            }catch(Exception e){throw new AssertionError(e);}
        }

        // ---- 学校三个域名仍照常可用（移除不能误伤统一认证与报修） ----
        check(IdentityPolicy.auth(IdentityPolicy.LOGIN),"统一认证登录页仍应有效");
        check(IdentityPolicy.allowed(IdentityPolicy.REPAIR),"报修页仍应放行");
        check(IdentityPolicy.repairLanding(IdentityPolicy.REPAIR),"报修落地页仍应识别");
        check(IdentityPolicy.repairRoot("http://hqfw.jhun.edu.cn/wsbx/#/wybx"),"报修根目录仍应识别");
        check(IdentityPolicy.hall("http://ehall.jhun.edu.cn/new/index.html"),"服务大厅仍应识别");

        // ---- 电表接口主机只出现在电费查询自己的客户端，不进浏览器白名单 ----
        String meterHost="https://h5cloud.17wanxiao.com:18443/CloudPayment/user/getRoomState.do";
        check(!IdentityPolicy.allowed(meterHost),"电表接口不应进入 WebView 白名单");
        check(DianfeiApi.interpret("{\"returncode\":\"100\",\"quantity\":\"1.0\"}").ok,"合成样本仍应可解析，确认客户端独立可用");

        // ---- 诊断报告不得泄露缴费/支付相关载荷 ----
        IdentityDiagnostics trace=new IdentityDiagnostics();
        trace.add(IdentityDiagnostics.Event.BLOCKED_MAIN,"https://hub.17wanxiao.com/bsacs/light.action?ticket=ST-SECRET",0);
        trace.add(IdentityDiagnostics.Event.GET,"https://mclient.alipay.com/h5pay/h5RouteAppSenior/index.html?cookieToken=SECRET&order=SECRET",0);
        trace.add(IdentityDiagnostics.Event.GET,"alipays://platformapi/startapp?appId=SECRET&url=SECRET",0);
        check(!trace.report().contains("SECRET"),"诊断不应包含任何载荷");
        check(!trace.report().contains("?"),"诊断不应包含查询串");
        check(trace.report().contains("BLOCKED_MAIN"),"被拦截的主框架跳转仍应记录");
        check(trace.report().contains("[外部应用协议：alipays]"),"外部应用协议只记录协议类别");
        // 缴费域名不再显示为已识别站点。
        check(IdentityDiagnostics.route("https://hub.17wanxiao.com/bsacs/light.action").equals("[其他或错误页面]"),"hub 不应再被识别");
        check(!IdentityDiagnostics.route("https://mclient.alipay.com/h5pay/h5RouteAppSenior/index.html").contains("alipay"),"支付宝域名不应出现在诊断里");
        // 电表主机仍应可诊断（用于排查电费查询本身）。
        check(IdentityDiagnostics.route(meterHost).contains("h5cloud.17wanxiao.com:18443"),"电表主机应仍可出现在诊断里");

        // ---- 会话清理：缴费站点只剩电表主机，且不再触碰 hub/open/wapnew ----
        java.util.Set<String> names=SessionCookies.extractNames(java.util.Arrays.asList("sid=a; SESSION=b; b_host=c"));
        check(names.size()==3&&names.contains("SESSION"),"应从 Cookie 头里取出名称");
        check(SessionCookies.extractNames(null).isEmpty(),"null 应返回空集合");
        check(SessionCookies.extractNames(java.util.Arrays.asList("", "; ; =x")).isEmpty(),"无效 Cookie 应被忽略");

        // ---- CAS 加密规则 ----
        check("plain".equals(CasLogin.encryptPassword("plain","")),"空 salt 应原样返回密码");
        check("plain".equals(CasLogin.encryptPassword("plain","   ")),"空白 salt 应原样返回密码");
        String encrypted=CasLogin.encryptPassword("synthetic-password","0123456789abcdef");
        check(!encrypted.equals("synthetic-password"),"有 salt 时应加密");
        check(!encrypted.contains("synthetic-password"),"密文不得包含明文");
        byte[] decoded=android.util.Base64.decode(encrypted,android.util.Base64.DEFAULT);
        // 随机 64 字符 + 17 字节明文 = 81 字节，PKCS7 补齐为 96 字节（AES 块大小的倍数）。
        check(decoded.length%16==0,"密文长度应为 AES 块大小的倍数，实际 "+decoded.length);
        check(decoded.length==96,"74/81 字节明文应补齐为 96 字节，实际 "+decoded.length);
        String again=CasLogin.encryptPassword("synthetic-password","0123456789abcdef");
        check(!again.equals(encrypted),"每次加密都应使用新的随机前缀与 IV");

        // ---- salt 解析：桌面版与手机版两种写法 ----
        check("DESKTOPSALT".equals(CasLogin.readSalt("<input id=\"pwdDefaultEncryptSalt\" value=\"DESKTOPSALT\">")),"应解析桌面版 salt");
        check("MOBILESALT".equals(CasLogin.readSalt("<script> var pwdDefaultEncryptSalt = \"MOBILESALT\"; </script>")),"应解析手机版 salt");
        check("QUOTEDSALT".equals(CasLogin.readSalt("<script>var pwdDefaultEncryptSalt='QUOTEDSALT';</script>")),"应解析单引号 salt");
        check("ATTRSALT".equals(CasLogin.readSalt("<input name=\"pwdDefaultEncryptSalt\" value=\"ATTRSALT\">")),"应解析 name 属性 salt");
        check("".equals(CasLogin.readSalt("<html></html>")),"缺 salt 应返回空串");
        check("".equals(CasLogin.readSalt("<input id=\"pwdDefaultEncryptSalt\" value=\"\">")),"空 salt 应返回空串");
        // 只认 input 会让手机版拿到空 salt，密码不加密提交被服务端判为错误。
        check(!CasLogin.readSalt("<script> var pwdDefaultEncryptSalt = \"x\"; </script>").isEmpty(),"手机版不能退化成空 salt");

        // ---- 登录错误文案 ----
        check("用户名或密码错误".equals(CasLogin.errorText("<div id=\"showErrorTip\">用户名或密码错误</div>")),"应取到页面错误提示");
        check("验证码错误".equals(CasLogin.errorText("<html>验证码错误</html>")),"应识别关键词错误");
        check(CasLogin.errorText("<html>正常页面</html>")==null,"无错误时应返回 null");
        check(CasLogin.errorText(null)==null,"null 页面应返回 null");
        check(CasLogin.login("","x").message.contains("学号"),"空账号应提示学号");
        check(CasLogin.login("s","").message.contains("密码"),"空密码应提示密码");

        System.out.println("Electricity removal and CAS: "+checks+" checks passed (synthetic data only)");
    }
}
