package com.samp.cef;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.TreeMap;

/**
 * Mengelola BANYAK browser (WebView) sekaligus, masing-masing dengan browser id.
 *
 * - id 0..0xFFFE = satu browser, {@link #BROWSER_ALL} = semua browser.
 * - id yang lebih besar digambar di atas id yang lebih kecil.
 * - Semua state hanya disentuh dari main thread (setiap public method memakai mMainHandler.post).
 * - Event dari server yang tiba sebelum halaman selesai dimuat ditahan lalu dikirim saat onPageFinished.
 */
public class CefJavaManager {
    private static final String TAG = "SAMP_CEF_JAVA";

    /** target "semua browser" (harus sama dengan CEF_BROWSER_ALL di include Pawn). */
    public static final int BROWSER_ALL = 0xFFFF;
    /** batas jumlah browser per client (satu WebView memakan memori GPU). */
    public static final int MAX_BROWSERS = 32;
    private static final int MAX_PENDING_EVENTS = 64;

    /**
     * Objek JS "cef" untuk HTML: cef.emit("nama", "data") mengirim event/klik ke server
     * (public OnCefEvent di Pawn). data berupa object otomatis di-JSON.stringify.
     * cef.on("nama", fn) menerima event dari server (CefSendEvent). Tidak menimpa "cef" buatan HTML sendiri.
     */
    private static final String CEF_SHIM_JS =
            "(function(){var b=window.AndroidCefBridge;if(!b||(window.cef&&window.cef.emit))return;"
          + "window.cef={emit:function(n,d){if(d===undefined||d===null)d='';"
          + "else if(typeof d!=='string')d=JSON.stringify(d);b.sendEvent(String(n),d);},"
          + "on:function(n,f){window.addEventListener(n,function(e){f(e.detail);});},"
          + "id:b.getBrowserId()};})();";

    /**
     * Sama seperti CEF_SHIM_JS, tapi untuk browser CefMobile (server-side
     * plugin, port 8778): window.cef.emit diarahkan ke CefMobileBridge
     * (POST /cef-mobile/event), BUKAN ke AndroidCefBridge/native. Hanya
     * dipakai untuk browser yang dibuat lewat openMobileBrowser(); browser
     * native (samp-mobile-cef) tetap memakai CEF_SHIM_JS di atas.
     * _dispatch ditambahkan supaya event dari server (CefMobile_SendEvent)
     * juga sampai ke halaman yang hanya mendengarkan lewat cef.on/_dispatch.
     */
    private static final String CEF_MOBILE_SHIM_JS =
            "(function(){var b=window.CefMobileBridge;if(!b||(window.cef&&window.cef.emit))return;"
          + "window.cef={emit:function(n,d){if(d===undefined||d===null)d='';"
          + "else if(typeof d!=='string')d=JSON.stringify(d);b.postEvent(String(n),d);},"
          + "on:function(n,f){window.addEventListener(n,function(e){f(e.detail);});},"
          + "_dispatch:function(e,d){window.dispatchEvent(new CustomEvent(e,{detail:d}));"
          + "if(typeof window.onCefEvent===\"function\"){window.onCefEvent(e,d);}},"
          + "id:b.getBrowserId()};})();";

    /** Dipanggil saat JS di browser CefMobile memanggil window.cef.emit(...). */
    public interface MobileEventListener {
        void onMobileEvent(int browserId, String event, String data);
    }

    /**
     * WebView yang bisa "tembus sentuhan". Kalau touch dimatikan, dispatchTouchEvent
     * return false sehingga event diteruskan ke view di bawahnya (UI game / SurfaceView
     * / Activity.onTouchEvent -> native).
     */
    private static class CefWebView extends WebView {
        private volatile boolean mTouchEnabled = true;

        CefWebView(Context context) {
            super(context);
        }

        void setTouchEnabled(boolean enabled) {
            mTouchEnabled = enabled;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            if (!mTouchEnabled) return false;
            return super.dispatchTouchEvent(ev);
        }
    }

    private static final class Browser {
        final int id;
        final CefWebView view;
        boolean shown;
        boolean focused;
        boolean pageLoaded;
        boolean initNotified;
        boolean bridgeAttached;
        // true = browser CefMobile (plugin server, port 8778): pakai
        // CEF_MOBILE_SHIM_JS + CefMobileBridge, dan JANGAN pernah
        // memberitahu native (onBrowserInit) tentang browser ini.
        boolean mobile;
        MobileEventListener mobileListener;
        final ArrayList<String[]> pendingEvents = new ArrayList<>();

        Browser(int id, CefWebView view) {
            this.id = id;
            this.view = view;
        }
    }

    private interface BrowserOp {
        void run(Browser b);
    }

    private final Activity mActivity;
    private final FrameLayout mRootLayout;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    // hanya diakses dari main thread. TreeMap = urut id (id besar di atas)
    private final TreeMap<Integer, Browser> mBrowsers = new TreeMap<>();

    private volatile CefClientManager mClientManager;

    private CefWebView mNotifyWebView;
    private boolean mNotifyWebViewReady = false;

    // true saat menu pause / peta game terbuka: semua UI CEF disembunyikan sementara
    private boolean mGamePaused = false;

    public CefJavaManager(FrameLayout rootLayout, Activity activity) {
        this.mRootLayout = rootLayout;
        this.mActivity = activity;

        // membuat WebView pertama juga "memanaskan" engine Chromium, jadi browser berikutnya lebih cepat
        mMainHandler.post(this::initNotifyWebView);
    }

    // ------------------------------------------------------------------ setup

    public void setClientManager(CefClientManager clientManager) {
        this.mClientManager = clientManager;
        mMainHandler.post(() -> {
            for (Browser b : mBrowsers.values()) attachJsBridgeIfNeeded(b);
        });
    }

    private void attachJsBridgeIfNeeded(Browser b) {
        CefClientManager cm = mClientManager;
        if (b.mobile || b.bridgeAttached || cm == null) return;
        b.view.addJavascriptInterface(new CefWebInterface(cm, b.id), "AndroidCefBridge");
        b.bridgeAttached = true;
    }

    private void attachMobileBridgeIfNeeded(Browser b) {
        if (!b.mobile || b.bridgeAttached || b.mobileListener == null) return;
        b.view.addJavascriptInterface(new CefMobileBridge(b.mobileListener, b.id), "CefMobileBridge");
        b.bridgeAttached = true;
    }

    private int dpToPx(int dp) {
        float density = mActivity.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void initNotifyWebView() {
        if (mNotifyWebView != null) return;

        mNotifyWebView = new CefWebView(mActivity);
        // overlay notifikasi hanya tampilan, tidak boleh menelan sentuhan
        mNotifyWebView.setTouchEnabled(false);
        mNotifyWebView.setFocusable(false);
        mNotifyWebView.setClickable(false);
        mNotifyWebView.setBackgroundColor(Color.TRANSPARENT);
        mNotifyWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        WebSettings settings = mNotifyWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);

        mNotifyWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                mNotifyWebViewReady = true;
                Log.i(TAG, "Notify overlay WebView ready");
            }
        });

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                dpToPx(300), dpToPx(500)
        );
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        mRootLayout.addView(mNotifyWebView, params);

        mNotifyWebView.loadUrl("file:///android_asset/html/notify.html");
    }

    // ------------------------------------------------------------------ browser lifecycle (main thread)

    private Browser createBrowser(final int id) {
        return createBrowser(id, false, null);
    }

    private Browser createBrowser(final int id, final boolean mobile,
                                  final MobileEventListener mobileListener) {
        if (mBrowsers.size() >= MAX_BROWSERS) {
            Log.e(TAG, "createBrowser(" + id + ") ditolak: sudah mencapai MAX_BROWSERS=" + MAX_BROWSERS);
            return null;
        }

        CefWebView wv = new CefWebView(mActivity);
        wv.setTouchEnabled(false);
        wv.setBackgroundColor(Color.TRANSPARENT);
        wv.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        WebSettings settings = wv.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);

        final Browser b = new Browser(id, wv);
        b.mobile = mobile;
        b.mobileListener = mobileListener;

        wv.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(TAG, "[" + id + "] console says " + consoleMessage.message() +
                        " -- From line " + consoleMessage.lineNumber() + " of " + consoleMessage.sourceId());
                return true;
            }
        });

        wv.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageCommitVisible(WebView view, String url) {
                super.onPageCommitVisible(view, url);
                view.evaluateJavascript(b.mobile ? CEF_MOBILE_SHIM_JS : CEF_SHIM_JS, null);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (mBrowsers.get(id) != b) return; // browser sudah di-destroy
                view.evaluateJavascript(b.mobile ? CEF_MOBILE_SHIM_JS : CEF_SHIM_JS, null);
                Log.d(TAG, "[" + id + "] Page loaded: " + url);

                b.pageLoaded = true;
                flushPendingEvents(b);

                // Browser CefMobile tidak dilaporkan ke native: plugin
                // samp-mobile-cef tidak tahu apa-apa tentang id ini.
                if (b.mobile) return;
                if (!b.initNotified) {
                    b.initNotified = true;
                    CefClientManager cm = mClientManager;
                    if (cm != null) cm.onBrowserInit(id, true, -1);
                }
            }

            @Override
            @SuppressWarnings("deprecation")
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                if (mBrowsers.get(id) != b) return;
                Log.e(TAG, "[" + id + "] WebView error (" + errorCode + "): " + description + " url=" + failingUrl);

                if (b.mobile) return;
                if (!b.initNotified) {
                    b.initNotified = true;
                    CefClientManager cm = mClientManager;
                    if (cm != null) cm.onBrowserInit(id, false, errorCode);
                }
            }
        });

        wv.setVisibility(View.GONE);
        mRootLayout.addView(wv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        mBrowsers.put(id, b);
        if (mobile) attachMobileBridgeIfNeeded(b);
        else attachJsBridgeIfNeeded(b);
        reorderViews();

        Log.i(TAG, "Browser " + id + " dibuat (total=" + mBrowsers.size() + ")");
        return b;
    }

    // id kecil di bawah, id besar di atas; overlay notifikasi selalu paling atas
    private void reorderViews() {
        for (Browser b : mBrowsers.values()) b.view.bringToFront();
        if (mNotifyWebView != null) mNotifyWebView.bringToFront();
    }

    private void destroyInternal(Browser b) {
        Log.i(TAG, "Destroy browser " + b.id);
        mBrowsers.remove(b.id);
        b.pendingEvents.clear();

        b.view.animate().cancel();
        b.view.stopLoading();
        b.view.setWebViewClient(new WebViewClient());
        b.view.setWebChromeClient(null);
        b.view.removeJavascriptInterface("AndroidCefBridge");
        b.view.removeJavascriptInterface("CefMobileBridge");

        ViewGroup parent = (ViewGroup) b.view.getParent();
        if (parent != null) parent.removeView(b.view);
        b.view.destroy();
    }

    private void startLoad(Browser b, String url) {
        b.pageLoaded = false;
        b.initNotified = false;
        b.pendingEvents.clear(); // event lama ditujukan ke halaman yang akan diganti
        Log.d(TAG, "[" + b.id + "] Loading URL: " + url);
        b.view.loadUrl(url);
    }

    private void showInternal(Browser b) {
        if (mGamePaused) {
            // menu pause terbuka: catat bahwa browser ini harus tampil, wujudkan saat game dilanjutkan
            b.shown = true;
            return;
        }
        if (b.shown && b.view.getVisibility() == View.VISIBLE) return;

        b.shown = true;
        b.view.animate().cancel();
        b.view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        b.view.setAlpha(0f);
        b.view.setVisibility(View.VISIBLE);
        b.view.animate().alpha(1f).setDuration(200).start();
        Log.i(TAG, "[" + b.id + "] Showing WebView");
    }

    private void hideInternal(final Browser b) {
        if (!b.shown) return;

        b.shown = false;
        b.view.animate().cancel();
        b.view.animate().alpha(0f).setDuration(150).withEndAction(() -> {
            if (!b.shown) { // belum di-show lagi selama animasi
                b.view.setVisibility(View.GONE);
                b.view.setLayerType(View.LAYER_TYPE_NONE, null);
                mRootLayout.invalidate();
            }
        }).start();
        Log.i(TAG, "[" + b.id + "] Hiding WebView");
    }

    private void forTargets(int id, String what, BrowserOp op) {
        if (id == BROWSER_ALL) {
            for (Browser b : new ArrayList<>(mBrowsers.values())) op.run(b);
            return;
        }
        Browser b = mBrowsers.get(id);
        if (b == null) {
            Log.w(TAG, what + ": browser " + id + " tidak ada (belum di-Init atau sudah di-Destroy)");
            return;
        }
        op.run(b);
    }

    // ------------------------------------------------------------------ public API (thread-safe)

    /** Buat (kalau belum ada) lalu muat url dan tampilkan. Dipanggil lagi = muat ulang url. */
    public void openBrowser(final int id, final String url, final boolean focused) {
        mMainHandler.post(() -> {
            if (id < 0 || id >= BROWSER_ALL) {
                Log.e(TAG, "openBrowser: browser id " + id + " tidak valid");
                return;
            }

            Browser b = mBrowsers.get(id);
            if (b == null) b = createBrowser(id);
            if (b == null) return;

            b.focused = focused;
            b.view.setTouchEnabled(focused);
            startLoad(b, url);
            showInternal(b);
        });
    }

    public void destroyBrowser(final int id) {
        mMainHandler.post(() -> forTargets(id, "destroyBrowser", this::destroyInternal));
    }

    /**
     * Buat (kalau belum ada) browser CefMobile lalu muat url dan tampilkan.
     * Dipanggil lagi = muat ulang url. Berbeda dengan openBrowser(): browser
     * ini memakai CefMobileBridge + CEF_MOBILE_SHIM_JS dan tidak pernah
     * dilaporkan ke native (samp-mobile-cef). Dipanggil dari CefMobileClient.
     */
    public void openMobileBrowser(final int id, final String url, final MobileEventListener listener) {
        mMainHandler.post(() -> {
            if (id < 0 || id >= BROWSER_ALL) {
                Log.e(TAG, "openMobileBrowser: browser id " + id + " tidak valid");
                return;
            }
            if (url == null || url.isEmpty()) {
                Log.e(TAG, "openMobileBrowser: url is null/empty");
                return;
            }
            if (listener == null) {
                Log.e(TAG, "openMobileBrowser: listener is null");
                return;
            }

            Browser b = mBrowsers.get(id);
            if (b != null && !b.mobile) {
                // id ini dipakai browser native: ganti jadi browser mobile
                destroyInternal(b);
                b = null;
            }
            if (b == null) b = createBrowser(id, true, listener);
            if (b == null) return;

            b.mobileListener = listener;
            attachMobileBridgeIfNeeded(b);
            b.focused = true;
            b.view.setTouchEnabled(true);
            startLoad(b, url);
            showInternal(b);
        });
    }

    public void showBrowser(final int id) {
        mMainHandler.post(() -> forTargets(id, "showBrowser", this::showInternal));
    }

    public void hideBrowser(final int id) {
        mMainHandler.post(() -> forTargets(id, "hideBrowser", this::hideInternal));
    }

    /** Ganti url browser yang sudah ada. */
    public void loadUrl(final int id, final String url) {
        if (url == null || url.isEmpty()) {
            Log.e(TAG, "loadUrl failed: url is null/empty");
            return;
        }
        mMainHandler.post(() -> forTargets(id, "loadUrl", b -> startLoad(b, url)));
    }

    /** fokus = HTML menerima sentuhan; tidak fokus = sentuhan tembus ke game. */
    public void setBrowserFocus(final int id, final boolean isFocused) {
        mMainHandler.post(() -> forTargets(id, "setBrowserFocus", b -> {
            b.focused = isFocused;
            b.view.setTouchEnabled(isFocused);
            if (isFocused) {
                b.view.requestFocus();
            } else {
                b.view.clearFocus();
            }
            Log.d(TAG, "[" + b.id + "] Browser focus set to: " + isFocused);
        }));
    }

    /**
     * Dipanggil dari native saat menu pause / peta game dibuka (paused = true) atau ditutup.
     * Semua UI CEF disembunyikan selama pause, lalu dikembalikan sesuai status show/hide dari server.
     */
    public void setGamePaused(final boolean paused) {
        mMainHandler.post(() -> {
            if (mGamePaused == paused) return;
            mGamePaused = paused;
            Log.i(TAG, "Game paused=" + paused + " -> UI CEF " + (paused ? "disembunyikan" : "dikembalikan"));

            for (Browser b : mBrowsers.values()) applyPauseVisibility(b);

            if (mNotifyWebView != null) {
                mNotifyWebView.setVisibility(paused ? View.INVISIBLE : View.VISIBLE);
            }
        });
    }

    private void applyPauseVisibility(Browser b) {
        if (mGamePaused) {
            if (b.view.getVisibility() == View.VISIBLE) b.view.setVisibility(View.INVISIBLE);
        } else if (b.shown) {
            b.view.animate().cancel();
            b.view.setAlpha(1f);
            b.view.setVisibility(View.VISIBLE);
        }
    }

    // kompatibilitas kode lama (mis. pause/resume activity): berlaku ke SEMUA browser
    public void showBrowserView() {
        showBrowser(BROWSER_ALL);
    }

    public void hideBrowserView() {
        hideBrowser(BROWSER_ALL);
    }

    /** true kalau ada minimal satu browser yang sedang tampil. Hanya untuk main thread. */
    public boolean isShow() {
        for (Browser b : mBrowsers.values()) {
            if (b.shown) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ server -> html

    public void dispatchServerEvent(final int id, final String eventName, final String eventData) {
        mMainHandler.post(() -> {
            if ("cef_ui_notification".equals(eventName)) {
                dispatchToNotifyWebView(eventName, eventData);
                return;
            }

            forTargets(id, "dispatchServerEvent(" + eventName + ")", b -> {
                if (!b.pageLoaded) {
                    // halaman belum siap: tahan, kirim saat onPageFinished
                    if (b.pendingEvents.size() >= MAX_PENDING_EVENTS) b.pendingEvents.remove(0);
                    b.pendingEvents.add(new String[]{eventName, eventData});
                    Log.d(TAG, "[" + b.id + "] event '" + eventName + "' ditahan sampai halaman selesai dimuat");
                    return;
                }
                deliverEvent(b, eventName, eventData);
            });
        });
    }

    private void flushPendingEvents(Browser b) {
        if (b.pendingEvents.isEmpty()) return;
        ArrayList<String[]> copy = new ArrayList<>(b.pendingEvents);
        b.pendingEvents.clear();
        for (String[] e : copy) deliverEvent(b, e[0], e[1]);
    }

    private void deliverEvent(Browser b, String eventName, String eventData) {
        if ("_cef_eval".equals(eventName)) {
            try {
                JSONArray arr = new JSONArray(eventData);
                String code = arr.getString(0);
                b.view.evaluateJavascript(code, null);
            } catch (JSONException e) {
                Log.e(TAG, "[" + b.id + "] Failed to parse _cef_eval payload: " + eventData, e);
            }
            return;
        }

        Log.d(TAG, "[" + b.id + "] dispatchServerEvent: " + eventName + " data=" + eventData);
        b.view.evaluateJavascript(buildEventJs(eventName, eventData), null);
    }

    /**
     * Kirim event ke halaman HTML dalam dua gaya sekaligus:
     *  1) window.addEventListener("namaEvent", e => e.detail)
     *  2) function onCefEvent(event, data) { ... } (kalau didefinisikan di HTML)
     */
    private String buildEventJs(String eventName, String eventData) {
        String n = escapeJsString(eventName);
        String d = escapeJsString(eventData);
        return "(function(){var n=\"" + n + "\",d=\"" + d + "\";"
                + "window.dispatchEvent(new CustomEvent(n,{detail:d}));"
                + "if(typeof window.onCefEvent===\"function\"){window.onCefEvent(n,d);}"
                + "})();";
    }

    private void dispatchToNotifyWebView(final String eventName, final String eventData) {
        if (mNotifyWebView == null || !mNotifyWebViewReady) {
            Log.w(TAG, "dispatchToNotifyWebView: notify overlay is not ready, event was dropped.");
            return;
        }
        mNotifyWebView.evaluateJavascript(buildEventJs(eventName, eventData), null);
    }

    // Escape agar payload aman di dalam string literal JS
    private static String escapeJsString(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }

    // ------------------------------------------------------------------ html -> server

    /**
     * Objek JS "AndroidCefBridge" untuk satu browser. id otomatis ikut terkirim,
     * jadi HTML tetap cukup memanggil AndroidCefBridge.sendEvent(nama, data).
     */
    public static class CefWebInterface {
        private final CefClientManager mClientManager;
        private final int mBrowserId;

        public CefWebInterface(CefClientManager clientManager, int browserId) {
            this.mClientManager = clientManager;
            this.mBrowserId = browserId;
        }

        @JavascriptInterface
        public void sendEvent(String eventName, String eventDataJson) {
            if (mClientManager != null) {
                mClientManager.sendClientEvent(mBrowserId, eventName, eventDataJson);
            }
        }

        /** id browser ini (sama dengan id di CefInitBrowser). */
        @JavascriptInterface
        public int getBrowserId() {
            return mBrowserId;
        }
    }

    /**
     * Objek JS "CefMobileBridge" untuk browser CefMobile. Dipanggil dari
     * CEF_MOBILE_SHIM_JS saat halaman memanggil window.cef.emit(nama, data);
     * event diteruskan ke MobileEventListener (CefMobileClient), yang
     * me-POST-nya ke /cef-mobile/event di server. Tidak ada jalur ke native.
     */
    public static class CefMobileBridge {
        private final MobileEventListener mListener;
        private final int mBrowserId;

        public CefMobileBridge(MobileEventListener listener, int browserId) {
            this.mListener = listener;
            this.mBrowserId = browserId;
        }

        @JavascriptInterface
        public void postEvent(String eventName, String eventData) {
            if (mListener != null) {
                mListener.onMobileEvent(mBrowserId,
                        eventName == null ? "" : eventName,
                        eventData == null ? "" : eventData);
            }
        }

        /** id browser ini (CefMobile memakai id 65000). */
        @JavascriptInterface
        public int getBrowserId() {
            return mBrowserId;
        }
    }
}