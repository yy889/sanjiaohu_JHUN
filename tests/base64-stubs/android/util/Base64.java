package android.util;
/** Test-only Base64 fixture mirroring the Android API surface the app uses. */
public final class Base64 {
    public static final int DEFAULT=0,NO_WRAP=2;
    private Base64(){}
    public static byte[] decode(String value,int flags){return java.util.Base64.getMimeDecoder().decode(value);}
    public static String encodeToString(byte[] value,int flags){
        java.util.Base64.Encoder encoder=flags==NO_WRAP?java.util.Base64.getEncoder():java.util.Base64.getMimeEncoder();
        return encoder.encodeToString(value);
    }
}
