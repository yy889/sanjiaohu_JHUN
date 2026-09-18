package cn.jhun.sanjiaohu;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 电费查询会话 Cookie 的加密存储。
 *
 * 与 {@link IdentityCredentialStore} 一样，只在工作线程调用，
 * 磁盘上只出现密文，密钥留在 Android Keystore。
 */
final class DianfeiCredentialStore {
    private static final String ALIAS="cn.jhun.sanjiaohu.dianfei.v1";

    private DianfeiCredentialStore(){}

    private static AtomicFile file(Context context){
        return new AtomicFile(new File(context.getNoBackupFilesDir(),"dianfei-session.enc"));
    }

    static boolean exists(Context context){return file(context).getBaseFile().exists();}

    private static SecretKey key(boolean create)throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        if(!create)throw new IOException("会话密钥不可用");
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build());
        return generator.generateKey();
    }

    static synchronized void save(Context context,String cookie)throws Exception{
        if(cookie==null||cookie.isEmpty())throw new IllegalArgumentException("Cookie 不能为空");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        DataOutputStream data=new DataOutputStream(bytes);
        data.writeUTF(cookie);
        data.close();
        byte[] plain=bytes.toByteArray();
        try{
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,key(true));
            byte[] encrypted=cipher.doFinal(plain),iv=cipher.getIV();
            AtomicFile target=file(context);
            FileOutputStream output=null;
            try{
                output=target.startWrite();
                output.write(1);
                output.write(iv.length);
                output.write(iv);
                output.write(encrypted);
                target.finishWrite(output);
            }catch(Exception e){
                if(output!=null)target.failWrite(output);
                throw e;
            }
        }finally{
            Arrays.fill(plain,(byte)0);
        }
    }

    static synchronized String load(Context context)throws Exception{
        byte[] encoded=file(context).readFully();
        if(encoded.length<31||encoded[0]!=1||encoded[1]!=12)throw new IOException("会话格式错误");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,encoded,2,12));
        byte[] plain=cipher.doFinal(encoded,14,encoded.length-14);
        try(DataInputStream input=new DataInputStream(new ByteArrayInputStream(plain))){
            return input.readUTF();
        }finally{
            Arrays.fill(plain,(byte)0);
        }
    }

    static synchronized void clear(Context context){file(context).delete();}
}
