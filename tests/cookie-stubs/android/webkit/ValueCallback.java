package android.webkit;
/** Test-only callback fixture; CookieManager.setCookie reports success through it. */
public interface ValueCallback<T> {
    void onReceiveValue(T value);
}
