package app.kursor;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.graphics.Bitmap;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.text.InputType;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kursor — eğitim videoları için kumandayla kullanılan TV tarayıcısı.
 * Kursor — a remote-driven TV browser for learning videos.
 * Her sitede çalışan genel video katmanı (assets/kursor/core.js) ve popüler platformlar için
 * site modülleri (assets/kursor/sites/*.js) sayfaya yüklenir. Oturum bilgileri yalnızca cihazdaki
 * WebView deposunda kalır; harici bir sunucuya gönderilmez.
 *
 * İmleç modu : yön tuşları imleci gezdirir, OK tıklar, kenara gelince sayfa kayar.
 * Video modu : sayfada büyük bir video oynayınca kendiliğinden açılır. OK alt menü,
 *              sol/sağ 10 sn, aşağı tam ekran, yukarı imlece döner.
 * Uzun OK    : video modunda altyazı modu; imleç modunda video varsa video moduna geçer,
 *              yoksa ana sayfaya döner.
 * Menü tuşu  : video modunda alt menü, diğer sayfalarda yazı boyutu.
 */
public class MainActivity extends Activity {

    private static final String HOME = "file:///android_asset/kursor/home.html";
    private static final int[] WIDTHS = {1024, 1280, 1536};
    private static final long LONG_PRESS_MS = 650;
    private static final int SEEK_SEC = 10;
    private static final int FAST_SEEK_SEC = 30;

    private FrameLayout root;
    private WebView web;
    private CursorView cursor;
    private TextView hint;
    private View customView;
    private WebChromeClient.CustomViewCallback customCallback;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    /** Dokunmatik cihaz (telefon/tablet): imleç yok, üstte adres çubuğu var. TV'de false. */
    private boolean touchUi = false;
    private LinearLayout toolbar;
    private EditText addressBar;
    private TextView videoFab;
    private int insetTop = 0, insetBottom = 0;

    private float cx, cy;
    private boolean videoMode = false;
    private long okDownTime = 0;
    private boolean okLongHandled = false;
    private long lastBackTime = 0;
    private int widthIndex = 1;

    // Reklam engelleme / ad blocking
    /** Ana çerçevedeki sayfanın ana makinesi (istek süzme için) / host of the main-frame page */
    private volatile String pageHost = "";
    private volatile boolean onHomePage = true;
    private volatile boolean adOn = true;
    private volatile Set<String> adOffSites = new HashSet<>();
    private final AtomicInteger blockedCount = new AtomicInteger();
    private String adScript = null;
    private TextView homeFab;
    private long menuDownTime = 0;
    private boolean menuLongHandled = false;

    // ------------------------------------------------------------------ lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("kursor", MODE_PRIVATE);
        widthIndex = prefs.getInt("widthIndex", 1);
        if (widthIndex < 0 || widthIndex >= WIDTHS.length) widthIndex = 1;

        PackageManager pm = getPackageManager();
        touchUi = !pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                && pm.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        web = new WebView(this);
        root.addView(web, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cursor = new CursorView(this);
        root.addView(cursor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        hint = new TextView(this);
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        int p = dp(14);
        hint.setPadding(p * 2, p, p * 2, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xCC000000);
        bg.setCornerRadius(dp(12));
        hint.setBackground(bg);
        hint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = dp(40);
        root.addView(hint, hlp);

        buildPlayerMenu();
        if (touchUi) {
            cursor.setVisibility(View.GONE);
            buildToolbar();
        }

        adOn = prefs.getBoolean("adblock", true);
        adOffSites = new HashSet<>(prefs.getStringSet("adOffSites", new HashSet<String>()));
        buildHomeFab();

        setContentView(root);
        setupWebView();
        Filters.start(this);
        handler.postDelayed(filterTick, 3600_000L);
        setupBackHandling();

        // Ekran boyutu değişirse (ör. TV klavyesi açılıp kapanınca) sayfayı yeniden tam ekrana ölçekle.
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (!nudging && ((r - l) != (or - ol) || (b - t) != (ob - ot))) {
                    handler.removeCallbacks(relayout);
                    handler.postDelayed(relayout, 250);
                }
            }
        });

        root.post(new Runnable() {
            @Override public void run() {
                cx = root.getWidth() / 2f;
                cy = root.getHeight() / 2f;
                cursor.moveTo(cx, cy);
            }
        });

        if (!touchUi) handler.postDelayed(viewportGuard, 3000);
        handler.postDelayed(errorGuard, 4000);
        handler.postDelayed(videoProbe, 2500);

        if (savedInstanceState != null) {
            web.restoreState(savedInstanceState);
        } else {
            web.loadUrl(HOME);
        }
        if (!touchUi) showHint(getString(R.string.hint_welcome), 6000);
        handler.postDelayed(firstRunNotice, 1500);
        // Uygulama her açıldığında güncelleme kontrol edilir.
        prefs.edit().putLong("lastUpdateCheck", 0).apply();
        handler.postDelayed(updateCheck, 6000);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        // TV: masaüstü görünümü; telefon/tablet: sitenin kendi mobil görünümü.
        if (!touchUi) s.setUserAgentString(desktopUserAgent(s.getUserAgentString()));
        else { s.setSupportZoom(true); s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false); }
        s.setAllowFileAccess(true);   // yalnızca uygulama içindeki ana sayfa (file:///android_asset) için

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true);

        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();

        web.addJavascriptInterface(new SubBridge(), "KursorSub");
        web.addJavascriptInterface(new AdBridge(), "KursorAd");
        // Hizmet çalışanlarının (service worker) istekleri de süzülsün / also filter service-worker requests
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                ServiceWorkerController.getInstance().setServiceWorkerClient(new ServiceWorkerClient() {
                    @Override public WebResourceResponse shouldInterceptRequest(WebResourceRequest req) {
                        return filterRequest(req);
                    }
                });
            } catch (Throwable ignored) { }
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                String scheme = u.getScheme();
                // http(s) her zaman uygulama içinde açılsın; intent://, mailto: vb. yok sayılır.
                // Web sayfaları uygulama içindeki dosyalara (file://) yönlendiremez.
                if ("file".equals(scheme)) return !HOME.equals(u.toString()) || !isAppPage(view.getUrl());
                return !("http".equals(scheme) || "https".equals(scheme));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                setPage(url);
                blockedCount.set(0);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                return filterRequest(req);
            }

            @Override
            public void onPageCommitVisible(WebView view, String url) {
                injectAdblock();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                setPage(url);
                injectAdblock();
                viewportFixes = 0;
                applyViewport();
                onUrlChanged(url);
                injectKursor();
                CookieManager.getInstance().flush();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                // Tek sayfa uygulamaları (YouTube, Udemy…): sayfa değişimleri burada yakalanır.
                setPage(url);
                viewportFixes = 0;
                applyViewport();
                onUrlChanged(url);
                injectKursor();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) {
                    showHint(getString(R.string.err_page), 6000);
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // Şifreli (Widevine) videolar için korumalı medya izni.
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                request.grant(new String[]{PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID});
                            }
                        });
                        return;
                    }
                }
                request.deny();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customCallback = callback;
                root.addView(view, 1, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                web.setVisibility(View.INVISIBLE);
                cursor.bringToFront();
                menuBar.bringToFront();
                menuScroll.bringToFront();
                hint.bringToFront();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customCallback != null) customCallback.onCustomViewHidden();
                customCallback = null;
                web.setVisibility(View.VISIBLE);
                web.requestFocus();
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        CookieManager.getInstance().flush();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
        handler.removeCallbacks(updateCheck);
        handler.postDelayed(updateCheck, 8000);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && ACTION_INSTALL_STATUS.equals(intent.getAction())) onInstallStatus(intent);
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        int action = e.getAction();
        boolean down = action == KeyEvent.ACTION_DOWN;
        boolean up = action == KeyEvent.ACTION_UP;

        // PC modu: gerçek klavyeden gelen tuşlar sayfaya olduğu gibi gider (oklar, Enter, Tab…), kısayollar hariç.
        // PC mode: keys from a real keyboard go straight to the page (arrows, Enter, Tab…), except shortcuts.
        if (!touchUi && isPcKeyboard(e)) {
            if (down && pcShortcut(e)) return true;
            if (code == KeyEvent.KEYCODE_ESCAPE && down) {
                if (menuScroll.getVisibility() == View.VISIBLE) { hidePopup(); return true; }
                if (menuVisible()) { hideMenu(); return true; }
                if (isFullscreen()) { exitFullscreen(); return true; }
            }
            if (code != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(e);
        }
        // Bazı TV kutuları fare tıklamasını OK tuşu olarak gönderir: farenin olduğu yere tıkla.
        // Some TV boxes send a mouse click as an OK key: click where the mouse is.
        if (!touchUi && (e.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                && (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER)) {
            if (up) click();
            return true;
        }
        // Kumandaya dönüldü: imleci yeniden göster / back to the remote: show the on-screen cursor again
        if (!touchUi && mouseMode && down && isDpadOrOk(code)) {
            mouseMode = false;
            if (!videoMode) cursor.setVisibility(View.VISIBLE);
            cursor.moveTo(cx, cy);
            return true;
        }

        // Medya tuşları her modda videoyu kontrol eder.
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                if (down && e.getRepeatCount() == 0) {
                    String a = code == KeyEvent.KEYCODE_MEDIA_PLAY ? "play"
                            : code == KeyEvent.KEYCODE_MEDIA_PAUSE ? "pause" : "toggle";
                    videoCommand(a);
                }
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (down) videoCommand(String.valueOf(FAST_SEEK_SEC));
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (down) videoCommand(String.valueOf(-FAST_SEEK_SEC));
                return true;
            case KeyEvent.KEYCODE_MENU:
                // Kısa: video menüsü / yazı boyutu. Basılı tut: reklam engelleme penceresi.
                // Short: video menu / text size. Hold: ad blocking dialog.
                if (down && e.getRepeatCount() == 0) {
                    menuDownTime = SystemClock.uptimeMillis();
                    menuLongHandled = false;
                } else if (down && !menuLongHandled && SystemClock.uptimeMillis() - menuDownTime >= LONG_PRESS_MS) {
                    menuLongHandled = true;
                    if (videoMode) { if (menuVisible()) hideMenu(); else showMenu(); } else showAdDialog();
                } else if (up && !menuLongHandled) {
                    if (videoMode) { if (menuVisible()) hideMenu(); else showMenu(); } else cycleZoom();
                }
                return true;
            case KeyEvent.KEYCODE_CAPTIONS:
                if (down && e.getRepeatCount() == 0) toggleSubtitles();
                return true;
            case KeyEvent.KEYCODE_PAGE_DOWN:
            case KeyEvent.KEYCODE_CHANNEL_DOWN:
                if (down) scrollAt(0, 0.8f);
                return true;
            case KeyEvent.KEYCODE_PAGE_UP:
            case KeyEvent.KEYCODE_CHANNEL_UP:
                if (down) scrollAt(0, -0.8f);
                return true;
            case KeyEvent.KEYCODE_BACK:
                // Android 13+: geri tuşu OnBackInvokedCallback ile gelir (setupBackHandling).
                if (Build.VERSION.SDK_INT >= 33) return super.dispatchKeyEvent(e);
                if (up) onBackKey();
                return true;
        }

        boolean isOk = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        if (isOk) {
            if (down) {
                if (e.getRepeatCount() == 0) {
                    okDownTime = e.getEventTime();
                    okLongHandled = false;
                } else if (!okLongHandled && e.getEventTime() - okDownTime >= LONG_PRESS_MS) {
                    okLongHandled = true;
                    onLongOk();
                }
            } else if (up) {
                if (!okLongHandled) {
                    if (e.getEventTime() - okDownTime >= LONG_PRESS_MS) {
                        onLongOk(); // tekrar göndermeyen kumandalar için
                    } else if (videoMode) {
                        onMenuOk();
                    } else {
                        click();
                    }
                }
                okLongHandled = false;
            }
            return true;
        }

        boolean isDpad = code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN;
        if (isDpad) {
            if (!down) return true;
            if (videoMode && menuVisible()) {
                onMenuDpad(code);
                return true;
            }
            if (videoMode) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) videoCommand(String.valueOf(-SEEK_SEC));
                else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) videoCommand(String.valueOf(SEEK_SEC));
                else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    if (e.getRepeatCount() == 0) toggleFullscreen();
                } else if (isFullscreen()) {
                    if (e.getRepeatCount() == 0) cycleVideoZoom();
                } else { userLeftVideo = true; setVideoMode(false, true); }
                return true;
            }
            moveCursor(code, e.getRepeatCount());
            return true;
        }

        return super.dispatchKeyEvent(e);
    }

    /** Uzun OK: video modunda altyazı modu; imleç modunda video varsa video modu, yoksa ana sayfa. */
    private void onLongOk() {
        if (menuVisible()) { onMenuOk(); return; }
        if (videoMode) {
            toggleSubtitles();
        } else if (hasVideo) {
            userLeftVideo = false;
            setVideoMode(true, true);
        } else {
            goHome();
        }
    }

    private void onBackKey() {
        if (menuScroll.getVisibility() == View.VISIBLE) {
            hidePopup();
            return;
        }
        if (menuVisible()) {
            hideMenu();
            return;
        }
        if (isFullscreen()) {
            exitFullscreen();
            return;
        }
        if (videoMode) {
            userLeftVideo = true;
            setVideoMode(false, true);
            return;
        }
        if (web.canGoBack()) {
            web.goBack();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastBackTime < 2000) {
            finish();
        } else {
            lastBackTime = now;
            showHint(getString(R.string.exit_again), 2000);
        }
    }

    // ------------------------------------------------------------------ cursor

    private void moveCursor(int code, int repeat) {
        float step = dp(10) + Math.min(repeat, 25) * dp(2.5f);
        float w = root.getWidth(), h = root.getHeight();
        float edge = dp(24);
        float dx = 0, dy = 0;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dx = -step; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = step; break;
            case KeyEvent.KEYCODE_DPAD_UP: dy = -step; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dy = step; break;
        }
        // Kenara dayalıysa sayfayı kaydır
        if (dy > 0 && cy >= h - edge) { scrollAt(0, 0.12f + Math.min(repeat, 10) * 0.01f); return; }
        if (dy < 0 && cy <= edge) { scrollAt(0, -(0.12f + Math.min(repeat, 10) * 0.01f)); return; }
        if (dx > 0 && cx >= w - edge) { scrollAt(0.15f, 0); return; }
        if (dx < 0 && cx <= edge) { scrollAt(-0.15f, 0); return; }

        cx = Math.max(dp(2), Math.min(w - dp(2), cx + dx));
        cy = Math.max(dp(2), Math.min(h - dp(2), cy + dy));
        cursor.moveTo(cx, cy);
        hover();
    }

    private View target() {
        return customView != null ? customView : web;
    }

    private void hover() {
        long t = SystemClock.uptimeMillis();
        MotionEvent ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, cx, cy, 0);
        ev.setSource(InputDevice.SOURCE_MOUSE);
        target().dispatchGenericMotionEvent(ev);
        ev.recycle();
    }

    private void click() {
        if (homeFab != null && homeFab.getVisibility() == View.VISIBLE
                && cx >= homeFab.getX() && cx <= homeFab.getX() + homeFab.getWidth()
                && cy >= homeFab.getY() && cy <= homeFab.getY() + homeFab.getHeight()) {
            cursor.pulse();
            goHome();
            return;
        }
        View v = target();
        long t = SystemClock.uptimeMillis();
        MotionEvent downEv = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, cx, cy, 0);
        downEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        v.dispatchTouchEvent(downEv);
        downEv.recycle();
        MotionEvent upEv = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, cx, cy, 0);
        upEv.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        v.dispatchTouchEvent(upEv);
        upEv.recycle();
        cursor.pulse();
    }

    /** İmlecin altındaki kaydırılabilir alanı (ör. müfredat paneli) ya da sayfayı kaydırır. */
    private void scrollAt(float fx, float fy) {
        float w = Math.max(1, root.getWidth()), h = Math.max(1, root.getHeight());
        String js = String.format(Locale.US,
                "(function(px,py,fx,fy){var x=px*innerWidth,y=py*innerHeight,dx=fx*innerWidth,dy=fy*innerHeight;"
                + "var el=document.elementFromPoint(x,y);"
                + "function ok(e){var s=getComputedStyle(e),o=dy?s.overflowY:s.overflowX;"
                + "return (o=='auto'||o=='scroll'||o=='overlay')&&(dy?e.scrollHeight>e.clientHeight+2:e.scrollWidth>e.clientWidth+2);}"
                + "while(el&&el!==document.body&&el!==document.documentElement){if(ok(el)){el.scrollBy(dx,dy);return;}el=el.parentElement;}"
                + "window.scrollBy(dx,dy);})(%f,%f,%f,%f)",
                cx / w, cy / h, fx, fy);
        web.evaluateJavascript(js, null);
    }

    // ------------------------------------------------------------------ video

    /** Sayfada video var mı (Kursor.state ile her 1,5 sn'de bir kontrol edilir) */
    private boolean hasVideo = false;
    /** Kullanıcı bu sayfada video modundan çıktıysa, sayfa değişene kadar kendiliğinden girilmez. */
    private boolean userLeftVideo = false;
    private String probeUrl = null;

    private void onUrlChanged(String url) {
        if (addressBar != null && !addressBar.hasFocus()) addressBar.setText(isAppPage(url) ? "" : url);
        String key = lectureKey(url);
        if (!key.equals(probeUrl)) {
            probeUrl = key;
            userLeftVideo = false;
            if (videoMode) {
                // Yeni sayfa (ör. sonraki ders): oynatıcı yeniden kurulur, kaliteyi tekrar seç.
                handler.removeCallbacks(highQuality);
                handler.postDelayed(highQuality, 3000);
            }
        }
    }

    private boolean isAppPage(String url) {
        return url != null && url.startsWith("file:///android_asset/");
    }

    private void goHome() {
        if (isFullscreen()) exitFullscreen();
        if (videoMode) setVideoMode(false, false);
        web.loadUrl(HOME);
    }

    /**
     * Sayfadaki ana videoyu izler: büyük bir video oynamaya başlayınca video moduna geçer,
     * video sayfadan kalkınca imleç moduna döner.
     */
    private final Runnable videoProbe = new Runnable() {
        @Override public void run() {
            handler.postDelayed(this, 1500);
            updateHomeFab();
            if (web == null || customView != null && videoMode) return;
            web.evaluateJavascript("window.Kursor?Kursor.state():''", new ValueCallback<String>() {
                @Override public void onReceiveValue(String v) {
                    if (v == null) return;
                    try {
                        String raw = v.length() > 2 && v.startsWith("\"") ? new JSONArray("[" + v + "]").getString(0) : "";
                        if (raw.isEmpty()) { hasVideo = false; return; }
                        JSONObject st = new JSONObject(raw);
                        hasVideo = st.optBoolean("video");
                        boolean big = st.optBoolean("big"), playing = !st.optBoolean("paused", true);
                        if (!videoMode && hasVideo && big && playing && !userLeftVideo) setVideoMode(true, true);
                        else if (videoMode && !hasVideo && !wantFull) setVideoMode(false, false);
                    } catch (Exception ignored) { }
                }
            });
        }
    };

    private void setVideoMode(boolean on, boolean announce) {
        // Fare kullanılırken video modu otomatik açılmaz: sitenin kendi oynatıcı düğmeleri kullanılır.
        // With a mouse, video mode does not take over: the site's own player controls are used.
        if (on && mouseMode) return;
        videoMode = on;
        if (!on) hideMenu();
        if (!touchUi) cursor.setVisibility(on || mouseMode ? View.INVISIBLE : View.VISIBLE);
        if (videoFab != null) { videoFab.setVisibility(on ? View.VISIBLE : View.GONE); videoFab.bringToFront(); }
        if (on) {
            // Video modunda önce en yüksek kaliteyi seç (destekleyen sitelerde), ardından tam ekrana geç.
            handler.removeCallbacks(highQuality);
            handler.removeCallbacks(autoFullscreen);
            handler.postDelayed(highQuality, 2000);
            if (!touchUi) handler.postDelayed(autoFullscreen, 4000);
        } else {
            handler.removeCallbacks(highQuality);
            handler.removeCallbacks(autoFullscreen);
            if (isFullscreen()) exitFullscreen();
        }
        if (announce) {
            if (on) {
                showHint(getString(R.string.hint_video_mode), 4000);
            } else {
                showHint(getString(R.string.hint_cursor_mode), 2000);
            }
        }
    }

    // ------------------------------------------------------------------ fullscreen

    private boolean cssFull = false;
    /** Tam ekran görüntü modları: object-fit + büyütme oranı */
    private static final String[] ZOOM_FIT = {"contain", "cover", "contain", "contain"};
    private static final float[] ZOOM_SCALE = {1f, 1f, 1.2f, 1.4f};
    /** Kullanıcı tam ekran istiyor mu? (▼ veya Geri ile kapatılana kadar true) */
    private boolean wantFull = false;
    private int fullBadCount = 0;

    private final Runnable autoFullscreen = new Runnable() {
        @Override public void run() {
            if (videoMode && !isFullscreen()) enterFullscreen();
        }
    };

    /** Site oynatıcıyı yeniden kurarsa (kalite değişimi, sonraki ders) tam ekranı geri getirir. */
    private final Runnable fullWatchdog = new Runnable() {
        @Override public void run() {
            if (!wantFull || !videoMode) return;
            if (customView == null) {
                String js = "(function(){var c=document.querySelector('[data-kursor-full]');"
                        + "if(!c||!document.getElementById('kursor-full'))return 'lost';"
                        + "var v=window.Kursor?(Kursor.video()||(Kursor.embed()||{}).frame||null):null;"
                        + "if(!v||!c.contains(v)||!v.hasAttribute('data-kursor-vid'))return 'lost';"
                        + "var vr=v.getBoundingClientRect();"
                        + "if(vr.width<innerWidth*0.9||vr.height<innerHeight*0.9)return 'small';return 'ok';})()";
                web.evaluateJavascript(js, new ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        if (!wantFull || !videoMode || customView != null || v == null) return;
                        if (v.contains("ok")) { fullBadCount = 0; return; }
                        fullBadCount++;
                        // Önce tam ekranı yeniden kur; hâlâ küçükse sayfa yüksekliğini yeniden hesaplat.
                        if (fullBadCount >= 2 && v.contains("small")) {
                            viewportFixes = 0;
                            nudgeWebView();
                        } else {
                            applyCssFullscreen(true, true);
                        }
                    }
                });
            }
            handler.postDelayed(this, 1500);
        }
    };

    // ------------------------------------------------------------------ playback error recovery

    /** Son bilinen oynatma konumu (sn) ve ait olduğu ders adresi */
    private double lastPos = 0;
    private String lastPosUrl = null;
    /** Yeniden yüklemeden sonra gidilecek konum */
    private double resumeAt = -1;
    private String resumeUrl = null;
    private int errorRecoveries = 0;
    private String errorUrl = null;
    private long lastRecovery = 0;

    private static String lectureKey(String url) {
        if (url == null) return "";
        int q = url.indexOf('?'); if (q >= 0) url = url.substring(0, q);
        int h = url.indexOf('#'); if (h >= 0) url = url.substring(0, h);
        return url;
    }

    /**
     * Udemy modülünde: oynatıcı "Video hatası / beklenmedik bir hata oluştu" penceresi gösterirse
     * dersi kendiliğinden yeniden yükler ve videoyu kaldığı saniyeden sürdürür.
     */
    private final Runnable errorGuard = new Runnable() {
        @Override public void run() {
            handler.postDelayed(this, 2000);
            if (!videoMode || web == null) return;
            final String key = lectureKey(web.getUrl());
            String seek = (resumeAt > 0 && key.equals(resumeUrl)) ? String.valueOf(resumeAt) : "-1";
            String js = "(function(){var R=" + seek + ";"
                    + "var err=false,re=/(video hatas|video error|oynatmay. birka|unexpected error|beklenmedik bir hata)/i;"
                    + "var ud=!!(window.Kursor&&Kursor.site()&&Kursor.site().id=='udemy');"
                    + "var ds=ud?document.querySelectorAll('[role=dialog],[role=alertdialog],[class*=modal],[class*=error],[class*=alert]'):[];"
                    + "for(var i=0;i<ds.length&&!err;i++){var d=ds[i];if(d.offsetParent===null&&getComputedStyle(d).position!=='fixed')continue;"
                    + "var t=(d.textContent||'');if(t.length<600&&re.test(t))err=true;}"
                    + "var v=window.Kursor?Kursor.video():null;"
                    + "if(v&&" + speedStr() + "!==1&&Math.abs(v.playbackRate-" + speedStr() + ")>0.01)v.playbackRate=" + speedStr() + ";"
                    + "if(err)return 'err';if(!v)return 'none';"
                    + "if(R>0){if(v.readyState>=1&&isFinite(v.duration)&&v.duration>0){"
                    + "if(Math.abs(v.currentTime-R)>4)v.currentTime=Math.min(R,v.duration-1);"
                    + "var p=v.play();if(p&&p.catch)p.catch(function(){});return 'resumed';}return 'wait';}"
                    + "return 't:'+v.currentTime;})()";
            web.evaluateJavascript(js, new ValueCallback<String>() {
                @Override public void onReceiveValue(String v) {
                    if (v == null || !videoMode) return;
                    v = v.replace("\"", "");
                    if (v.startsWith("t:")) {
                        try {
                            double t = Double.parseDouble(v.substring(2));
                            if (t > 1) { lastPos = t; lastPosUrl = key; }
                        } catch (NumberFormatException ignored) { }
                    } else if (v.equals("resumed")) {
                        resumeAt = -1; resumeUrl = null;
                        showHint(getString(R.string.video_resumed), 2500);
                    } else if (v.equals("err")) {
                        recoverFromVideoError(key);
                    }
                }
            });
        }
    };

    private void recoverFromVideoError(String key) {
        long now = System.currentTimeMillis();
        if (now - lastRecovery < 15000) return;       // yeniden yükleme sürerken tekrar tetikleme
        if (!key.equals(errorUrl)) { errorUrl = key; errorRecoveries = 0; }
        if (errorRecoveries >= 3) {
            if (errorRecoveries == 3) {
                errorRecoveries++;
                showHint(getString(R.string.video_failed), 8000);
            }
            return;
        }
        errorRecoveries++;
        lastRecovery = now;
        resumeAt = key.equals(lastPosUrl) ? lastPos : -1;
        resumeUrl = key;
        showHint(getString(R.string.video_error_reload, errorRecoveries), 4000);
        web.reload();
    }

    // ------------------------------------------------------------------ Turkish subtitles

    private String subScript = null;
    private final ExecutorService subExec = Executors.newSingleThreadExecutor();

    /** Altyazı modu: "auto" (yalnızca yabancı dilli derslerde), "off" (hiç), "on" (her zaman). */
    private String subMode() {
        String m = prefs.getString("subMode", "auto");
        return "off".equals(m) || "on".equals(m) ? m : "auto";
    }

    /** Altyazı hedef dili: menüden seçilen dil; seçilmediyse TV'nin dili (Türkçe değilse İngilizce). */
    private String subLang() {
        String def = "tr".equals(Locale.getDefault().getLanguage()) ? "tr" : "en";
        return prefs.getString("subLang", def);
    }

    private String kursorScript = null;

    /** Genel video katmanını, site modüllerini ve altyazı katmanını tek parça olarak okur. */
    private String kursorScript() {
        if (kursorScript != null) return kursorScript;
        StringBuilder sb = new StringBuilder();
        try {
            sb.append(new String(readAll(getAssets().open("kursor/core.js")), "UTF-8")).append("\n;");
            String[] sites = getAssets().list("kursor/sites");
            if (sites != null) for (String f : sites) {
                if (f.endsWith(".js")) sb.append(new String(readAll(getAssets().open("kursor/sites/" + f)), "UTF-8")).append("\n;");
            }
            sb.append(new String(readAll(getAssets().open("kursor/subtitles.js")), "UTF-8"));
        } catch (Exception ex) { return ""; }
        kursorScript = sb.toString();
        return kursorScript;
    }

    /** Kursor katmanını sayfaya yükler (zaten yüklüyse yalnızca ayarları tazeler). */
    private void injectKursor() {
        String url = web.getUrl();
        if (url == null || isAppPage(url)) return;
        web.evaluateJavascript("if(!window.Kursor){" + kursorScript() + "}"
                + ";window.__kSub&&window.__kSub.setLang('" + subLang() + "');"
                + "window.__kSub&&window.__kSub.setSize('" + subSize() + "');"
                + "window.__kSub&&window.__kSub.setMode('" + subMode() + "');"
                + "window.Kursor&&" + speedStr() + "!==1&&(Kursor._rate=" + speedStr() + ");", null);
    }

    /** Uzun OK: Otomatik → Kapalı → Her zaman açık → Otomatik */
    private void toggleSubtitles() {
        String cur = subMode();
        setSubMode("auto".equals(cur) ? "off" : "off".equals(cur) ? "on" : "auto");
    }

    private void setSubMode(String next) {
        prefs.edit().putString("subMode", next).apply();
        injectKursor();
        web.evaluateJavascript("window.__kSub&&window.__kSub.setMode('" + next + "')", null);
        int id = "off".equals(next) ? R.string.subs_off : "on".equals(next) ? R.string.subs_on : R.string.subs_auto;
        showHint(getString(id), 3000);
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36");
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            return new String(readAll(c.getInputStream()), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    /** Google Çeviri (gtx) ile metni hedef dile çevirir. */
    private static String translateText(String q, String tl) throws Exception {
        String body = httpGet("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + tl + "&dt=t&q="
                + URLEncoder.encode(q, "UTF-8"));
        JSONArray segs = new JSONArray(body).getJSONArray(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segs.length(); i++) {
            Object o = segs.getJSONArray(i).opt(0);
            if (o instanceof String) sb.append((String) o);
        }
        return sb.toString();
    }

    /** Yedek: her satırı ayrı q parametresiyle gönderir, cevap satır satır gelir. */
    private static List<String> translateDict(List<String> src, String tl) throws Exception {
        StringBuilder u = new StringBuilder("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=" + tl);
        for (String s : src) u.append("&q=").append(URLEncoder.encode(s.replace('\n', ' '), "UTF-8"));
        JSONArray a = new JSONArray(httpGet(u.toString()));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            Object o = a.get(i);
            out.add(o instanceof JSONArray ? ((JSONArray) o).optString(0, "") : String.valueOf(o));
        }
        if (out.size() != src.size()) throw new java.io.IOException("line count mismatch");
        return out;
    }

    /** Satırları tek istekte çevirir; satır sayısı tutmazsa ikiye bölerek tekrar dener. */
    private static List<String> translateLines(List<String> src, String tl) throws Exception {
        List<String> out = new ArrayList<>();
        if (src.isEmpty()) return out;
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < src.size(); i++) {
            if (i > 0) q.append('\n');
            q.append(src.get(i).replace('\n', ' '));
        }
        String res;
        try {
            res = translateText(q.toString(), tl);
        } catch (Exception ex) {
            return translateDict(src, tl);   // yedek çeviri adresi
        }
        String[] parts = res.split("\n", -1);
        List<String> clean = new ArrayList<>();
        for (String p : parts) clean.add(p.trim());
        while (clean.size() > src.size() && clean.get(clean.size() - 1).isEmpty()) clean.remove(clean.size() - 1);
        if (clean.size() == src.size()) return clean;
        if (src.size() == 1) {
            out.add(res.replace('\n', ' ').trim());
            return out;
        }
        int mid = src.size() / 2;
        out.addAll(translateLines(src.subList(0, mid), tl));
        out.addAll(translateLines(src.subList(mid, src.size()), tl));
        return out;
    }

    private void subCallback(final String id, final boolean ok, final String data) {
        handler.post(new Runnable() {
            @Override public void run() {
                web.evaluateJavascript("window.__kSubCb&&window.__kSubCb(" + JSONObject.quote(id) + ","
                        + ok + "," + JSONObject.quote(data == null ? "" : data) + ")", null);
            }
        });
    }

    /** subtitles.js ile uygulama arasındaki köprü (ağ istekleri CORS'a takılmasın diye burada yapılır). */
    private class SubBridge {
        @JavascriptInterface
        public void translate(final String id, final String json) {
            subExec.execute(new Runnable() {
                @Override public void run() {
                    try {
                        JSONArray a = new JSONArray(json);
                        List<String> src = new ArrayList<>();
                        for (int i = 0; i < a.length(); i++) src.add(a.optString(i, ""));
                        JSONArray res = new JSONArray();
                        for (String s : translateLines(src, subLang())) res.put(s);
                        subCallback(id, true, res.toString());
                    } catch (Exception ex) {
                        subCallback(id, false, String.valueOf(ex.getMessage()));
                    }
                }
            });
        }

        @JavascriptInterface
        public void status(final String key) {
            handler.post(new Runnable() {
                @Override public void run() {
                    int id = "preparing".equals(key) ? R.string.sub_preparing
                            : "ready".equals(key) ? R.string.sub_ready
                            : R.string.sub_failed;
                    if (videoMode) showHint(getString(id), 3000);
                }
            });
        }
    }

    // ------------------------------------------------------------------ player menu (VLC tarzı alt menü)

    private static final String[] MENU_IDS = {"prev", "back", "play", "fwd", "next", "speed", "subs", "zoom", "full"};
    private static final String[] SPEEDS = {"0.75", "1", "1.25", "1.5", "1.75", "2"};
    private static final String[] SUB_LANGS = {"tr", "en", "de", "fr", "es", "it", "pt", "ru", "ar", "nl"};
    private static final String[] SUB_LANG_NAMES = {"Türkçe", "English", "Deutsch", "Français", "Español",
            "Italiano", "Português", "Русский", "العربية", "Nederlands"};
    private static final String[] SUB_SIZES = {"0.8", "1", "1.3", "1.6"};
    private static final long MENU_HIDE_MS = 6000;

    private LinearLayout menuBar, menuRow, menuPopup;
    private ScrollView menuScroll;   // açılır liste uzun olursa kaydırılır
    private TextView menuTime;
    private ProgressBar menuProgress;
    private final List<TextView> menuItems = new ArrayList<>();
    private final List<TextView> popupItems = new ArrayList<>();
    private final List<String> popupValues = new ArrayList<>();
    private int menuIndex = 2, popupIndex = 0;
    private String popupKind = null;
    private boolean videoPaused = false;

    private float speed() {
        try { return Float.parseFloat(prefs.getString("speed", "1")); } catch (Exception e) { return 1f; }
    }

    /** Altyazı yazı boyutu çarpanı (varsayılan 1 = orta). */
    private String subSize() { return prefs.getString("subSize", "1"); }

    private String speedStr() { return String.format(Locale.US, "%.2f", speed()); }

    private static String speedLabel(String v) {
        return v.replace(".", ",") + "×";
    }

    private GradientDrawable roundBg(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** Alt menüyü (ilerleme çubuğu + düğmeler) ve açılır listeyi kurar; başlangıçta gizlidir. */
    private void buildPlayerMenu() {
        menuBar = new LinearLayout(this);
        menuBar.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xEE000000, 0xB0000000, 0x00000000});
        menuBar.setBackground(fade);
        menuBar.setPadding(dp(32), dp(36), dp(32), dp(20));
        menuBar.setVisibility(View.GONE);

        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        timeRow.setGravity(Gravity.CENTER_VERTICAL);
        menuTime = new TextView(this);
        menuTime.setTextColor(Color.WHITE);
        menuTime.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        menuTime.setText("0:00 / 0:00");
        menuTime.setPadding(0, 0, dp(16), 0);
        timeRow.addView(menuTime);
        menuProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        menuProgress.setMax(1000);
        menuProgress.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFF26B3A));
        menuProgress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x66FFFFFF));
        timeRow.addView(menuProgress, new LinearLayout.LayoutParams(0, dp(8), 1f));
        menuBar.addView(timeRow);

        menuRow = new LinearLayout(this);
        menuRow.setOrientation(LinearLayout.HORIZONTAL);
        menuRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(14);
        for (int i = 0; i < MENU_IDS.length; i++) {
            TextView t = new TextView(this);
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            t.setPadding(dp(14), dp(8), dp(14), dp(8));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = lp.rightMargin = dp(4);
            menuRow.addView(t, lp);
            final int idx = i;
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { hidePopup(); menuIndex = idx; refreshMenuLabels(); onMenuOk(); }
            });
            menuItems.add(t);
        }
        menuBar.addView(menuRow, rowLp);
        root.addView(menuBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        menuScroll = new ScrollView(this);
        menuScroll.setBackground(roundBg(0xF0202024, 10));
        menuScroll.setVerticalScrollBarEnabled(true);
        menuScroll.setFocusable(false);
        menuScroll.setVisibility(View.GONE);
        menuPopup = new LinearLayout(this);
        menuPopup.setOrientation(LinearLayout.VERTICAL);
        menuPopup.setPadding(dp(6), dp(6), dp(6), dp(6));
        menuScroll.addView(menuPopup);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.LEFT);
        slp.topMargin = dp(24);
        root.addView(menuScroll, slp);
        refreshMenuLabels();
    }

    private boolean menuVisible() {
        return menuBar != null && menuBar.getVisibility() == View.VISIBLE;
    }

    private void showMenu() {
        if (!videoMode) return;
        if (!menuVisible()) menuIndex = 2;   // açılınca odak oynat/durdur düğmesinde
        menuBar.setVisibility(View.VISIBLE);
        menuBar.bringToFront();
        menuScroll.bringToFront();
        hint.bringToFront();
        refreshMenuLabels();
        handler.removeCallbacks(menuTicker);
        handler.post(menuTicker);
        bumpMenuTimer();
        updateHomeFab();
    }

    private void hideMenu() {
        if (menuBar == null) return;
        hidePopup();
        menuBar.setVisibility(View.GONE);
        handler.removeCallbacks(menuTicker);
        handler.removeCallbacks(menuAutoHide);
        updateHomeFab();
    }

    private final Runnable menuAutoHide = new Runnable() {
        @Override public void run() { hideMenu(); }
    };

    private void bumpMenuTimer() {
        handler.removeCallbacks(menuAutoHide);
        handler.postDelayed(menuAutoHide, menuScroll.getVisibility() == View.VISIBLE ? MENU_HIDE_MS * 2 : MENU_HIDE_MS);
    }

    private String menuLabel(String id) {
        switch (id) {
            case "prev": return getString(R.string.menu_prev);
            case "back": return getString(R.string.menu_back);
            case "play": return videoPaused ? "▶" : "⏸";
            case "fwd": return getString(R.string.menu_fwd);
            case "next": return getString(R.string.menu_next);
            case "speed": return getString(R.string.menu_speed, speedLabel(trimSpeed(speed())));
            case "subs": return getString(R.string.menu_subs);
            case "zoom": return getString(R.string.menu_zoom);
            default: return getString(isFullscreen() ? R.string.menu_full_exit : R.string.menu_full);
        }
    }

    private static String trimSpeed(float f) {
        String s = String.format(Locale.US, "%.2f", f);
        while (s.contains(".") && (s.endsWith("0") || s.endsWith("."))) s = s.substring(0, s.length() - 1);
        return s;
    }

    private void refreshMenuLabels() {
        for (int i = 0; i < menuItems.size(); i++) {
            TextView t = menuItems.get(i);
            t.setText(menuLabel(MENU_IDS[i]));
            boolean sel = i == menuIndex && menuScroll.getVisibility() != View.VISIBLE;
            boolean open = i == menuIndex && menuScroll.getVisibility() == View.VISIBLE;
            t.setBackground(sel ? roundBg(0xFFF26B3A, 8) : open ? roundBg(0x66F26B3A, 8) : null);
            t.setTypeface(null, sel ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        }
    }

    /** Menü açıkken her yarım saniyede süre, ilerleme ve oynat/durdur durumunu günceller. */
    private final Runnable menuTicker = new Runnable() {
        @Override public void run() {
            if (!menuVisible()) return;
            web.evaluateJavascript("(function(){if(!window.Kursor)return 'none';var s=JSON.parse(Kursor.state());"
                    + "if(!s.video)return 'none';return (s.paused?'p':'r')+'|'+s.t+'|'+s.d;})()",
                    new ValueCallback<String>() {
                        @Override public void onReceiveValue(String value) {
                            if (value == null || !menuVisible()) return;
                            String[] p = value.replace("\"", "").split("\\|");
                            if (p.length < 3) return;
                            try {
                                double cur = Double.parseDouble(p[1]), dur = Double.parseDouble(p[2]);
                                videoPaused = p[0].equals("p");
                                menuTime.setText(fmt(String.valueOf((long) cur)) + " / " + fmt(String.valueOf((long) dur)));
                                menuProgress.setProgress(dur > 0 ? (int) (cur * 1000 / dur) : 0);
                                menuItems.get(2).setText(menuLabel("play"));
                            } catch (NumberFormatException ignored) { }
                        }
                    });
            handler.postDelayed(this, 500);
        }
    };

    private void onMenuOk() {
        if (menuScroll.getVisibility() == View.VISIBLE) { choosePopup(); return; }
        if (!menuVisible()) { showMenu(); return; }
        bumpMenuTimer();
        String id = MENU_IDS[menuIndex];
        switch (id) {
            case "prev": hideMenu(); goLecture(-1); break;
            case "next": hideMenu(); goLecture(1); break;
            case "back": videoCommand(String.valueOf(-SEEK_SEC)); break;
            case "fwd": videoCommand(String.valueOf(SEEK_SEC)); break;
            case "play":
                videoCommand("toggle");
                videoPaused = !videoPaused;
                refreshMenuLabels();
                break;
            case "full": toggleFullscreen(); refreshMenuLabels(); break;
            default: openPopup(id); break;
        }
    }

    private void onMenuDpad(int code) {
        bumpMenuTimer();
        if (menuScroll.getVisibility() == View.VISIBLE) {
            if (code == KeyEvent.KEYCODE_DPAD_UP) { popupIndex = Math.max(0, popupIndex - 1); refreshPopup(); }
            else if (code == KeyEvent.KEYCODE_DPAD_DOWN) { popupIndex = Math.min(popupItems.size() - 1, popupIndex + 1); refreshPopup(); }
            else hidePopup();
            return;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) menuIndex = Math.max(0, menuIndex - 1);
        else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) menuIndex = Math.min(MENU_IDS.length - 1, menuIndex + 1);
        else if (code == KeyEvent.KEYCODE_DPAD_UP) { hideMenu(); return; }
        refreshMenuLabels();
    }

    private void addPopupItem(String label, String value, boolean checked) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setPadding(dp(16), dp(7), dp(28), dp(7));
        t.setText((checked ? "✓  " : "     ") + label);
        menuPopup.addView(t);
        final int idx = popupItems.size();
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { popupIndex = idx; choosePopup(); }
        });
        popupItems.add(t);
        popupValues.add(value);
        if (checked) popupIndex = popupItems.size() - 1;
    }

    private void addPopupHeader(String label) {
        TextView t = new TextView(this);
        t.setTextColor(0xFFAAAAAA);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setPadding(dp(16), dp(8), dp(16), dp(2));
        t.setText(label);
        menuPopup.addView(t);
    }

    private void openPopup(String kind) {
        menuPopup.removeAllViews();
        popupItems.clear();
        popupValues.clear();
        popupIndex = 0;
        popupKind = kind;
        if ("speed".equals(kind)) {
            float cur = speed();
            for (String v : SPEEDS) addPopupItem(speedLabel(v), v, Math.abs(Float.parseFloat(v) - cur) < 0.01f);
        } else if ("zoom".equals(kind)) {
            String[] names = getResources().getStringArray(R.array.zoom_names);
            int z = zoomIndex();
            for (int i = 0; i < names.length; i++) addPopupItem(names[i], String.valueOf(i), i == z);
        } else {
            String mode = subMode(), lang = subLang();
            addPopupHeader(getString(R.string.menu_subs_mode));
            addPopupItem(getString(R.string.subs_mode_auto), "mode:auto", "auto".equals(mode));
            addPopupItem(getString(R.string.subs_mode_off), "mode:off", "off".equals(mode));
            addPopupItem(getString(R.string.subs_mode_on), "mode:on", "on".equals(mode));
            int keep = popupIndex;
            addPopupHeader(getString(R.string.menu_subs_size));
            String[] sizeNames = getResources().getStringArray(R.array.sub_sizes);
            String size = subSize();
            for (int i = 0; i < SUB_SIZES.length; i++) addPopupItem(sizeNames[i], "size:" + SUB_SIZES[i], SUB_SIZES[i].equals(size));
            addPopupHeader(getString(R.string.menu_subs_lang));
            for (int i = 0; i < SUB_LANGS.length; i++) addPopupItem(SUB_LANG_NAMES[i], "lang:" + SUB_LANGS[i], SUB_LANGS[i].equals(lang));
            popupIndex = keep;   // odak mod seçiminde başlasın
        }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) menuScroll.getLayoutParams();
        TextView anchor = menuItems.get(menuIndex);
        lp.leftMargin = Math.max(dp(8), menuBar.getLeft() + menuRow.getLeft() + anchor.getLeft());
        lp.bottomMargin = menuBar.getHeight() - menuRow.getTop() + dp(6);
        menuScroll.setLayoutParams(lp);
        menuScroll.setVisibility(View.VISIBLE);
        menuScroll.scrollTo(0, 0);
        menuScroll.bringToFront();
        refreshPopup();
        refreshMenuLabels();
        bumpMenuTimer();
    }

    private void refreshPopup() {
        for (int i = 0; i < popupItems.size(); i++) {
            popupItems.get(i).setBackground(i == popupIndex ? roundBg(0xFFF26B3A, 6) : null);
        }
        if (popupIndex >= 0 && popupIndex < popupItems.size()) {
            final TextView sel = popupItems.get(popupIndex);
            menuScroll.post(new Runnable() {
                @Override public void run() {
                    int y = sel.getTop() - menuScroll.getHeight() / 2 + sel.getHeight() / 2;
                    menuScroll.smoothScrollTo(0, Math.max(0, y));
                }
            });
        }
    }

    private void hidePopup() {
        if (menuScroll == null || menuScroll.getVisibility() != View.VISIBLE) return;
        menuScroll.setVisibility(View.GONE);
        popupKind = null;
        refreshMenuLabels();
    }

    private void choosePopup() {
        if (popupIndex < 0 || popupIndex >= popupValues.size()) { hidePopup(); return; }
        String v = popupValues.get(popupIndex);
        if ("speed".equals(popupKind)) {
            prefs.edit().putString("speed", v).apply();
            web.evaluateJavascript("window.Kursor&&Kursor.cmd('rate'," + v + ")", null);
            showHint(getString(R.string.speed_set, speedLabel(v)), 1500);
        } else if ("zoom".equals(popupKind)) {
            prefs.edit().putInt("videoZoom", Integer.parseInt(v)).apply();
            if (!isFullscreen()) enterFullscreen(); else applyVideoZoom();
            showHint(getString(R.string.video_zoom, getResources().getStringArray(R.array.zoom_names)[Integer.parseInt(v)]), 1500);
        } else if (v.startsWith("mode:")) {
            setSubMode(v.substring(5));
        } else if (v.startsWith("size:")) {
            String k = v.substring(5);
            prefs.edit().putString("subSize", k).apply();
            injectKursor();
            web.evaluateJavascript("window.__kSub&&window.__kSub.setSize('" + k + "')", null);
            int idx = java.util.Arrays.asList(SUB_SIZES).indexOf(k);
            showHint(getString(R.string.subs_size_set, getResources().getStringArray(R.array.sub_sizes)[Math.max(0, idx)]), 2000);
        } else if (v.startsWith("lang:")) {
            prefs.edit().putString("subLang", v.substring(5)).apply();
            if ("off".equals(subMode())) prefs.edit().putString("subMode", "auto").apply();
            injectKursor();
            showHint(getString(R.string.subs_lang_set, popupItems.get(popupIndex).getText().toString().trim().replace("✓", "").trim()), 2000);
        }
        hidePopup();
        refreshMenuLabels();
        bumpMenuTimer();
    }

    /** Önceki / sonraki ders ya da video: site modülü, yoksa sayfadaki "Sonraki/Önceki" düğmesi. */
    private void goLecture(final int dir) {
        web.evaluateJavascript("window.Kursor?Kursor.go(" + dir + "):'none'", new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                boolean none = v == null || v.contains("none");
                showHint(getString(none ? R.string.no_next : (dir > 0 ? R.string.lecture_next : R.string.lecture_prev)), 2000);
            }
        });
    }

    // ------------------------------------------------------------------ first run notice

    private final Runnable firstRunNotice = new Runnable() {
        @Override public void run() {
            if (prefs.getBoolean("disclaimerShown", false) || isFinishing()) return;
            new AlertDialog.Builder(MainActivity.this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.disclaimer)
                    .setCancelable(false)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            prefs.edit().putBoolean("disclaimerShown", true).apply();
                        }
                    })
                    .show();
        }
    };

    // ------------------------------------------------------------------ auto update

    private static final String ACTION_INSTALL_STATUS = "app.kursor.INSTALL_STATUS";
    /** Uygulama açılışında her zaman; arka plandan dönüşte en fazla 10 dakikada bir kontrol. */
    private static final long UPDATE_INTERVAL_MS = 10L * 60 * 1000;
    /** "Sonra" denilen sürüm, uygulama kapanana kadar tekrar sorulmaz. */
    private int dismissedBuild = 0;
    private final ExecutorService updateExec = Executors.newSingleThreadExecutor();
    private boolean updateBusy = false;

    /** GitHub'daki son sürümü kontrol eder (en fazla 6 saatte bir). */
    private final Runnable updateCheck = new Runnable() {
        @Override public void run() {
            final String repo = getString(R.string.update_repo).trim();
            if (repo.isEmpty() || updateBusy || isFinishing()) return;   // yerel/deneme derlemelerinde kapalı
            long now = System.currentTimeMillis();
            if (now - prefs.getLong("lastUpdateCheck", 0) < UPDATE_INTERVAL_MS) return;
            prefs.edit().putLong("lastUpdateCheck", now).apply();
            updateExec.execute(new Runnable() {
                @Override public void run() {
                    try {
                        // GitHub'ın normal sürüm sayfası kullanılır (API'nin saatte 60 istek sınırı yok).
                        // Uses the plain releases page: no API rate limit, follows repo/user renames.
                        final String tag = latestReleaseTag(repo);
                        final int latest = parseBuildNumber(tag);
                        if (latest <= currentVersionCode() || latest == dismissedBuild) return;
                        final String url = "https://github.com/" + repo + "/releases/download/" + tag + "/EduCastTV.apk";
                        final String name = "EduCast TV 2.1." + latest;
                        String body = "";
                        try {   // sürüm notları isteğe bağlı / release notes are optional
                            JSONObject rel = new JSONObject(httpGet("https://api.github.com/repos/" + repo + "/releases/tags/" + tag));
                            body = rel.optString("body", "").trim();
                        } catch (Exception ignored) { }
                        if (body.length() > 600) body = body.substring(0, 600) + "…";
                        final String notes = body;
                        handler.post(new Runnable() {
                            @Override public void run() { showUpdateDialog(name, notes, url, latest); }
                        });
                    } catch (Exception ignored) {
                        // Ağ yoksa bir sonraki açılışta tekrar denenir.
                        prefs.edit().putLong("lastUpdateCheck", 0).apply();
                    }
                }
            });
        }
    };

    /** github.com/<repo>/releases/latest adresinin yönlendirmesinden son sürüm etiketini (ör. "v60") okur. */
    private static String latestReleaseTag(String repo) throws Exception {
        String url = "https://github.com/" + repo + "/releases/latest";
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", "Kursor-updater");
            try {
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) {
                    int i = loc.indexOf("/releases/tag/");
                    if (i >= 0) {
                        String tag = loc.substring(i + "/releases/tag/".length());
                        int q = tag.indexOf('?');
                        return q >= 0 ? tag.substring(0, q) : tag;
                    }
                    url = loc.startsWith("http") ? loc : "https://github.com" + loc;   // kullanıcı/depo adı değişmiş
                    continue;
                }
                throw new java.io.IOException("HTTP " + code);
            } finally {
                c.disconnect();
            }
        }
        throw new java.io.IOException("too many redirects");
    }

    private static int parseBuildNumber(String tag) {
        String d = tag == null ? "" : tag.replaceAll("[^0-9]", "");
        try { return d.isEmpty() ? 0 : Integer.parseInt(d); } catch (NumberFormatException e) { return 0; }
    }

    @SuppressWarnings("deprecation")
    private int currentVersionCode() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return Integer.MAX_VALUE;
        }
    }

    private void showUpdateDialog(String name, String notes, final String url, final int build) {
        if (isFinishing()) return;
        String msg = getString(R.string.update_message, name) + (notes.isEmpty() ? "" : "\n\n" + notes);
        new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(R.string.update_title)
                .setMessage(msg)
                .setPositiveButton(R.string.update_now, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { downloadAndInstall(url); }
                })
                .setNegativeButton(R.string.update_later, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) { dismissedBuild = build; }
                })
                .show();
    }

    private void downloadAndInstall(final String url) {
        if (updateBusy) return;
        updateBusy = true;
        showHint(getString(R.string.update_downloading, 0), 3000);
        updateExec.execute(new Runnable() {
            @Override public void run() {
                try {
                    File apk = new File(getCacheDir(), "update.apk");
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(15000);
                    c.setReadTimeout(30000);
                    c.setInstanceFollowRedirects(true);
                    try {
                        if (c.getResponseCode() != 200) throw new java.io.IOException("HTTP " + c.getResponseCode());
                        long total = c.getContentLength();
                        InputStream in = c.getInputStream();
                        OutputStream out = new FileOutputStream(apk);
                        byte[] buf = new byte[65536];
                        long done = 0;
                        int n, lastPct = -1;
                        while ((n = in.read(buf)) > 0) {
                            out.write(buf, 0, n);
                            done += n;
                            if (total > 0) {
                                final int pct = (int) (done * 100 / total);
                                if (pct / 10 != lastPct / 10) {
                                    lastPct = pct;
                                    handler.post(new Runnable() {
                                        @Override public void run() { showHint(getString(R.string.update_downloading, pct), 3000); }
                                    });
                                }
                            }
                        }
                        out.close();
                        in.close();
                    } finally {
                        c.disconnect();
                    }
                    installApk(apk);
                } catch (final Exception ex) {
                    updateBusy = false;
                    handler.post(new Runnable() {
                        @Override public void run() { showHint(getString(R.string.update_failed, String.valueOf(ex.getMessage())), 6000); }
                    });
                }
            }
        });
    }

    /** İndirilen APK'yı sistem yükleyicisine verir; Android onay penceresini gösterir. */
    private void installApk(File apk) throws Exception {
        PackageInstaller pi = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int sessionId = pi.createSession(params);
        PackageInstaller.Session session = pi.openSession(sessionId);
        try {
            OutputStream out = session.openWrite("update.apk", 0, apk.length());
            InputStream in = new FileInputStream(apk);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            session.fsync(out);
            out.close();
            Intent i = new Intent(this, MainActivity.class).setAction(ACTION_INSTALL_STATUS);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent p = PendingIntent.getActivity(this, sessionId, i, flags);
            session.commit(p.getIntentSender());
        } finally {
            session.close();
        }
        handler.post(new Runnable() {
            @Override public void run() { showHint(getString(R.string.update_installing), 4000); }
        });
    }

    private void onInstallStatus(Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                try {
                    startActivity(confirm);
                    return;
                } catch (Exception ignored) { }
            }
        }
        updateBusy = false;
        if (status != PackageInstaller.STATUS_SUCCESS) {
            String m = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            showHint(getString(R.string.update_failed, m == null ? String.valueOf(status) : m), 8000);
        }
    }

    // ------------------------------------------------------------------ quality

    private final Runnable highQuality = new Runnable() {
        @Override public void run() { if (videoMode) forceHighestQuality(); }
    };

    /** Oynatıcının ayarlar menüsünden en yüksek çözünürlüğü seçer (şimdilik yalnız Udemy modülünde). */
    private void forceHighestQuality() {
        String js = "(function(){if(!(window.Kursor&&Kursor.site()&&Kursor.site().id=='udemy'))return 'skip';"
                + "function items(){var a=document.querySelectorAll('[role=menuitemradio],[role=menuitem],[role=menuitemcheckbox],[role=option],li,button'),o=[];"
                + "for(var i=0;i<a.length;i++){var t=(a[i].textContent||'').trim();if(/^\\d{3,4}p\\b/i.test(t)&&t.length<12)o.push(a[i]);}return o;}"
                + "function pick(){var its=items();if(!its.length)return 0;var best=null,bv=0;"
                + "for(var i=0;i<its.length;i++){var v=parseInt(its[i].textContent,10);if(v>bv){bv=v;best=its[i];}}"
                + "var sel=best.getAttribute('aria-checked')==='true'||best.getAttribute('aria-selected')==='true';"
                + "if(!sel)best.click();window.__kQ=bv;return bv;}"
                + "function btn(){var b=document.querySelectorAll('button');for(var i=0;i<b.length;i++){"
                + "var s=(b[i].getAttribute('aria-label')||'')+' '+(b[i].getAttribute('data-purpose')||'')+' '+(b[i].getAttribute('title')||'');"
                + "if(/(settings|ayarlar|quality|kalite)/i.test(s))return b[i];}return null;}"
                + "window.__kQ=0;if(pick())return 'direct';"
                + "var s=btn();if(!s)return 'nobutton';s.click();"
                + "setTimeout(function(){if(!pick()){var a=document.querySelectorAll('[role=menuitem],button,li');"
                + "for(var i=0;i<a.length;i++){if(/(quality|kalite|çözünürlük)/i.test(a[i].textContent||'')&&(a[i].textContent||'').length<40){a[i].click();break;}}"
                + "setTimeout(function(){pick();s.click();},350);}else{setTimeout(function(){s.click();},250);}},450);"
                + "return 'menu';})()";
        web.evaluateJavascript(js, null);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                web.evaluateJavascript("String(window.__kQ||0)", new ValueCallback<String>() {
                    @Override public void onReceiveValue(String v) {
                        String q = v == null ? "0" : v.replace("\"", "");
                        if (!"0".equals(q)) showHint(getString(R.string.video_quality, q), 2000);
                    }
                });
            }
        }, 1600);
    }

    private boolean isFullscreen() {
        return customView != null || wantFull;
    }

    private int zoomIndex() {
        int z = prefs.getInt("videoZoom", 0);
        return (z < 0 || z >= ZOOM_FIT.length) ? 0 : z;
    }

    /** Tam ekranda ▲: Sığdır → Doldur → 1.2x → 1.4x */
    private void cycleVideoZoom() {
        int z = (zoomIndex() + 1) % ZOOM_FIT.length;
        prefs.edit().putInt("videoZoom", z).apply();
        applyVideoZoom();
        showHint(getString(R.string.video_zoom, getResources().getStringArray(R.array.zoom_names)[z]), 1800);
    }

    private void applyVideoZoom() {
        int z = zoomIndex();
        String js = String.format(Locale.US,
                "(function(){var d=document.documentElement.style;d.setProperty('--k-fit','%s');d.setProperty('--k-scale','%.2f');})()",
                ZOOM_FIT[z], ZOOM_SCALE[z]);
        web.evaluateJavascript(js, null);
    }

    private void toggleFullscreen() {
        if (isFullscreen()) exitFullscreen(); else enterFullscreen();
    }

    /** Videoyu (oynatıcı kutusuyla birlikte) tüm ekrana yayar ve bekçiyi başlatır. */
    private void enterFullscreen() {
        wantFull = true;
        if (touchUi) {
            toolbar.setVisibility(View.GONE);
            layoutWeb();
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        }
        fullBadCount = 0;
        applyCssFullscreen(true, false);
        handler.removeCallbacks(fullWatchdog);
        handler.postDelayed(fullWatchdog, 1500);
    }

    private void exitFullscreen() {
        wantFull = false;
        if (touchUi) {
            toolbar.setVisibility(View.VISIBLE);
            layoutWeb();
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        }
        handler.removeCallbacks(fullWatchdog);
        if (customView != null) {
            web.evaluateJavascript("document.exitFullscreen&&document.exitFullscreen()", null);
            handler.postDelayed(new Runnable() {
                @Override public void run() {
                    if (customView != null && customCallback != null) customCallback.onCustomViewHidden();
                }
            }, 300);
        }
        applyCssFullscreen(false, true);
    }

    /** Oynatıcı kutusunu (video + altyazı + kontroller) sabit konumla tüm ekrana yayar. */
    private void applyCssFullscreen(final boolean on, final boolean silent) {
        String js = "(function(on){var id='kursor-full',o=document.getElementById(id);if(o)o.remove();"
                + "var ms=document.querySelectorAll('[data-kursor-full],[data-kursor-anc],[data-kursor-vid],[data-kursor-chain]');"
                + "for(var k=0;k<ms.length;k++){ms[k].removeAttribute('data-kursor-full');ms[k].removeAttribute('data-kursor-anc');ms[k].removeAttribute('data-kursor-vid');ms[k].removeAttribute('data-kursor-chain');}"
                + "if(!on)return 'off';"
                + "var v=window.Kursor?(Kursor.video()||(Kursor.embed()||{}).frame||null):null;"
                + "if(!v)return 'none';"
                + "var vr=v.getBoundingClientRect(),c=v,p=v.parentElement;"
                + "while(p&&p!==document.body){var pr=p.getBoundingClientRect();"
                + "if(pr.width>vr.width*1.15+4||pr.height>vr.height*1.35+4)break;c=p;p=p.parentElement;}"
                + "c.setAttribute('data-kursor-full','1');v.setAttribute('data-kursor-vid','1');"
                + "var q=v.parentElement;while(q&&q!==c){q.setAttribute('data-kursor-chain','1');q=q.parentElement;}"
                + "var a=v.parentElement;while(a){a.setAttribute('data-kursor-anc','1');a=a.parentElement;}"
                + "var st=document.createElement('style');st.id=id;"
                + "st.textContent='html,body{overflow:hidden!important;background:#000!important}'"
                + "+'body *{visibility:hidden!important}'"
                + "+'[data-kursor-full],[data-kursor-full] *{visibility:visible!important}'"
                + "+'[data-kursor-anc]{transform:none!important;filter:none!important;contain:none!important;will-change:auto!important;overflow:visible!important}'"
                + "+'[data-kursor-full]{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;"
                + "width:auto!important;height:auto!important;max-width:none!important;max-height:none!important;"
                + "margin:0!important;z-index:2147483647!important;background:#000!important;transform:none!important}'"
                + "+'[data-kursor-chain]{width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;"
                + "min-height:0!important;padding-top:0!important;padding-bottom:0!important;aspect-ratio:auto!important;margin:0!important}'"
                + "+'[data-kursor-vid]{position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;"
                + "width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;min-width:0!important;min-height:0!important;"
                + "margin:0!important;padding:0!important;background:#000!important;object-fit:var(--k-fit,contain)!important;"
                + "transform:scale(var(--k-scale,1))!important;transform-origin:50% 50%!important}';"
                + "(document.head||document.documentElement).appendChild(st);return 'on';})(" + on + ")";
        web.evaluateJavascript(js, new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                String r = v == null ? "" : v.replace("\"", "");
                if (on) {
                    cssFull = "on".equals(r);
                    if (cssFull) applyVideoZoom();
                    // Video henüz yoksa bekçi kod yüklenince tam ekranı uygular.
                    if (cssFull && !silent) showHint(getString(R.string.hint_fullscreen), 2500);
                } else {
                    cssFull = false;
                }
            }
        });
    }

    /** a = "toggle" | "play" | "pause" | saniye (ör. "10", "-10") */
    private void videoCommand(String a) {
        String js = "(function(a){if(!window.Kursor)return 'none';"
                + "var r=(a=='toggle'||a=='play'||a=='pause')?Kursor.cmd(a):Kursor.cmd('seek',parseFloat(a));"
                + "if(r=='novideo')return 'none';var s=JSON.parse(Kursor.state());"
                + "var p=r=='embed'?(a=='toggle'?!s.paused:s.paused):s.paused;"
                + "return (p?'p':'r')+'|'+Math.floor(s.t)+'|'+Math.floor(s.d);"
                + "})('" + a + "')";
        web.evaluateJavascript(js, new ValueCallback<String>() {
            @Override public void onReceiveValue(String value) {
                if (value == null) return;
                String v = value.replace("\"", "");
                if (v.equals("none") || v.equals("null")) {
                    showHint(getString(R.string.no_video), 2000);
                    return;
                }
                String[] parts = v.split("\\|");
                if (parts.length < 3) return;
                String icon = parts[0].equals("p") ? "⏸" : "▶";
                showHint(icon + "  " + fmt(parts[1]) + " / " + fmt(parts[2]), 1800);
            }
        });
    }

    private static String fmt(String secStr) {
        int s;
        try { s = Integer.parseInt(secStr); } catch (NumberFormatException ex) { return "--:--"; }
        int h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    // ------------------------------------------------------------------ zoom / viewport

    private void cycleZoom() {
        widthIndex = (widthIndex + 1) % WIDTHS.length;
        prefs.edit().putInt("widthIndex", widthIndex).apply();
        viewportFixes = 0;
        applyViewport();
        showHint(getString(R.string.text_size, getResources().getStringArray(R.array.text_sizes)[widthIndex]), 1500);
    }

    /** Sayfayı TV için sabit bir masaüstü genişliğinde (vars. 1280 px) gösterir. */
    private void applyViewport() {
        if (touchUi) return;   // telefon/tablet: sitenin kendi ölçüsü
        if (web.getWidth() <= 0 || web.getHeight() <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        int viewW = root.getWidth() > 0 ? root.getWidth() : web.getWidth();
        int viewH = root.getHeight() > 0 ? root.getHeight() : web.getHeight();
        int cssW = WIDTHS[widthIndex];
        float scale = (viewW / density) / cssW;
        int cssH = Math.round((viewH / density) / scale);
        String content = String.format(Locale.US,
                "width=%d, initial-scale=%.4f, minimum-scale=%.4f, maximum-scale=%.4f, user-scalable=no",
                cssW, scale, scale, scale);
        // Ayar site tarafından değiştirildiyse ya da sayfa yüksekliği ekrana uymuyorsa düzelt.
        String js = String.format(Locale.US,
                "(function(c,w,h){var m=document.querySelector('meta[name=viewport]'),ch=false;"
                + "if(!m){m=document.createElement('meta');m.name='viewport';(document.head||document.documentElement).appendChild(m);}"
                + "if(m.getAttribute('content')!==c){m.setAttribute('content',c);ch=true;}"
                + "var d=document.documentElement;if(Math.abs(d.clientWidth-w)>8||Math.abs(d.clientHeight-h)>8)ch=true;"
                + "return ch?'fix':'ok';})('%s',%d,%d)", content, cssW, cssH);
        web.evaluateJavascript(js, new ValueCallback<String>() {
            @Override public void onReceiveValue(String v) {
                if (v != null && v.contains("fix")) {
                    if (viewportFixes < 3) { viewportFixes++; nudgeWebView(); }
                } else {
                    viewportFixes = 0;
                }
            }
        });
    }

    private int viewportFixes = 0;
    private boolean nudging = false;

    /** WebView'i 2 piksel küçültüp geri büyütür: tarayıcı sayfa yüksekliğini yeniden hesaplar. */
    private void nudgeWebView() {
        if (nudging || root.getHeight() <= 2) return;
        nudging = true;
        final ViewGroup.LayoutParams lp = web.getLayoutParams();
        lp.height = root.getHeight() - 2;
        web.setLayoutParams(lp);
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
                web.setLayoutParams(lp);
                nudging = false;
                if (wantFull && videoMode) {
                    handler.postDelayed(new Runnable() {
                        @Override public void run() { if (wantFull && videoMode) applyCssFullscreen(true, true); }
                    }, 300);
                }
            }
        }, 150);
    }

    /** Ekran ölçüsünü düzenli kontrol eder (tek sayfa siteler ayarı bozabiliyor). */
    private final Runnable viewportGuard = new Runnable() {
        @Override public void run() {
            if (!nudging) applyViewport();
            handler.postDelayed(this, 2000);
        }
    };

    // ------------------------------------------------------------------ phone / tablet

    /** Telefon/tablet: üst araç çubuğu (geri, ileri, yenile, adres, ana sayfa) ve video menü düğmesi. */
    private void buildToolbar() {
        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(0xFF1B1F4B);
        toolbar.setPadding(dp(4), dp(4), dp(4), dp(4));
        toolbar.addView(toolButton("←", new Runnable() { @Override public void run() { if (web.canGoBack()) web.goBack(); } }));
        toolbar.addView(toolButton("→", new Runnable() { @Override public void run() { if (web.canGoForward()) web.goForward(); } }));
        toolbar.addView(toolButton("⟳", new Runnable() { @Override public void run() { web.reload(); } }));
        addressBar = new EditText(this);
        addressBar.setSingleLine(true);
        addressBar.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        addressBar.setTextColor(Color.WHITE);
        addressBar.setHintTextColor(0x99FFFFFF);
        addressBar.setHint(R.string.address_hint);
        addressBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressBar.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressBar.setSelectAllOnFocus(true);
        addressBar.setBackground(roundBg(0x33FFFFFF, 18));
        addressBar.setPadding(dp(14), dp(6), dp(14), dp(6));
        addressBar.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                openAddress(v.getText().toString());
                return true;
            }
        });
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        alp.leftMargin = alp.rightMargin = dp(4);
        toolbar.addView(addressBar, alp);
        // Ana sayfa artık köşedeki yüzen düğmede; burada reklam engelleme kalkanı var.
        // Home is now the floating corner button; this slot holds the ad-blocking shield.
        toolbar.addView(toolButton("🛡", new Runnable() { @Override public void run() { showAdDialog(); } }));
        root.addView(toolbar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        // Video modunda alt menüyü açan düğme (kumandadaki OK'nin karşılığı)
        videoFab = new TextView(this);
        videoFab.setText("⚙");
        videoFab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        videoFab.setTextColor(Color.WHITE);
        videoFab.setGravity(Gravity.CENTER);
        videoFab.setBackground(roundBg(0xE6FF6A3D, 28));
        videoFab.setVisibility(View.GONE);
        videoFab.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (menuVisible()) hideMenu(); else showMenu(); }
        });
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.BOTTOM | Gravity.RIGHT);
        flp.rightMargin = dp(16);
        flp.bottomMargin = dp(16);
        root.addView(videoFab, flp);

        // Android 15+ kenardan kenara çizim: sistem çubuklarının altında kalmamak için boşluk bırak.
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                insetTop = in.getSystemWindowInsetTop();
                insetBottom = in.getSystemWindowInsetBottom();
                layoutWeb();
                placeHomeFab();
                return in;
            }
        });
        toolbar.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (b - t != ob - ot) layoutWeb();
            }
        });
    }

    private TextView toolButton(String label, final Runnable action) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(new View.OnClickListener() { @Override public void onClick(View v) { action.run(); } });
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return t;
    }

    /** Araç çubuğu ve sistem çubuklarına göre WebView'in yerini ayarlar. */
    private void layoutWeb() {
        if (!touchUi || toolbar == null) return;
        boolean full = wantFull;
        FrameLayout.LayoutParams tlp = (FrameLayout.LayoutParams) toolbar.getLayoutParams();
        tlp.topMargin = insetTop;
        toolbar.setLayoutParams(tlp);
        FrameLayout.LayoutParams wlp = (FrameLayout.LayoutParams) web.getLayoutParams();
        wlp.topMargin = full ? 0 : insetTop + toolbar.getHeight();
        wlp.bottomMargin = full ? 0 : insetBottom;
        web.setLayoutParams(wlp);
        FrameLayout.LayoutParams mlp = (FrameLayout.LayoutParams) menuBar.getLayoutParams();
        mlp.bottomMargin = full ? 0 : insetBottom;
        menuBar.setLayoutParams(mlp);
    }

    /** Adres çubuğuna yazılanı açar: adres ise doğrudan, değilse web araması. */
    private void openAddress(String q) {
        q = q == null ? "" : q.trim();
        if (q.isEmpty()) return;
        String url;
        if (q.matches("(?i)^https?://.*")) url = q;
        else if (q.matches("(?i)^[^\\s]+\\.[a-z]{2,}(/\\S*)?$")) url = "https://" + q;
        else {
            try { url = "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8"); }
            catch (Exception e) { return; }
        }
        if (addressBar != null) {
            addressBar.clearFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(addressBar.getWindowToken(), 0);
        }
        web.requestFocus();
        web.loadUrl(url);
    }

    /** Android 13 ve üstü: geri tuşu/hareketi yeni geri arayüzüyle yakalanır (Android 16 zorunlu kılıyor). */
    private void setupBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                new android.window.OnBackInvokedCallback() {
                    @Override public void onBackInvoked() { onBackKey(); }
                });
    }

    // ------------------------------------------------------------------ ad blocking

    /** Ana sayfanın adresini izler (istek süzme ve ana sayfa düğmesi için). */
    private void setPage(String url) {
        boolean app = url == null || isAppPage(url);
        onHomePage = app;
        String h = "";
        if (!app) {
            try { h = Uri.parse(url).getHost(); } catch (Exception ignored) { }
            h = h == null ? "" : h.toLowerCase(Locale.ROOT);
        }
        pageHost = h;
        updateHomeFab();
    }

    private boolean siteOff(String host) {
        return !host.isEmpty() && adOffSites.contains(AdBlocker.baseDomain(host));
    }

    /** WebView'in her isteği için (arka plan iş parçacığı). Reklamsa boş yanıt döner. */
    private WebResourceResponse filterRequest(WebResourceRequest req) {
        try {
            Uri u = req.getUrl();
            if (req.isForMainFrame()) {
                String h = u.getHost();
                if (h != null && ("http".equals(u.getScheme()) || "https".equals(u.getScheme()))) {
                    pageHost = h.toLowerCase(Locale.ROOT);
                }
                return null;
            }
            if (!adOn) return null;
            String scheme = u.getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) return null;
            String host = u.getHost();
            String ph = pageHost;
            if (host == null || ph.isEmpty() || siteOff(ph)) return null;
            int type = guessType(req, u.getPath());
            if (!Filters.engine().shouldBlock(u.toString(), host.toLowerCase(Locale.ROOT), ph, type)) return null;
            blockedCount.incrementAndGet();
            return new WebResourceResponse("text/plain", "utf-8", 403, "Blocked",
                    new HashMap<String, String>(), new ByteArrayInputStream(new byte[0]));
        } catch (Throwable t) {
            return null;
        }
    }

    /** WebView istek türünü söylemez; uzantı ve Accept başlığından tahmin edilir. */
    private static int guessType(WebResourceRequest req, String path) {
        String p = path == null ? "" : path.toLowerCase(Locale.ROOT);
        int dot = p.lastIndexOf('.');
        String ext = dot >= 0 && dot > p.lastIndexOf('/') ? p.substring(dot + 1) : "";
        switch (ext) {
            case "js": case "mjs": return AdBlocker.T_SCRIPT;
            case "css": return AdBlocker.T_CSS;
            case "png": case "jpg": case "jpeg": case "gif": case "webp": case "svg": case "ico": case "avif": case "bmp":
                return AdBlocker.T_IMAGE;
            case "woff": case "woff2": case "ttf": case "otf": case "eot": return AdBlocker.T_FONT;
            case "mp4": case "webm": case "m3u8": case "mpd": case "ts": case "m4s": case "mp3": case "aac": case "vtt":
                return AdBlocker.T_MEDIA;
            case "html": case "htm": return AdBlocker.T_SUBDOC;
        }
        String accept = null;
        Map<String, String> hs = req.getRequestHeaders();
        if (hs != null) for (Map.Entry<String, String> e : hs.entrySet()) {
            if ("accept".equalsIgnoreCase(e.getKey())) { accept = e.getValue(); break; }
        }
        if (accept != null) {
            accept = accept.toLowerCase(Locale.ROOT);
            if (accept.startsWith("text/css")) return AdBlocker.T_CSS;
            if (accept.startsWith("image/")) return AdBlocker.T_IMAGE;
            if (accept.startsWith("text/html")) return AdBlocker.T_SUBDOC;
        }
        return AdBlocker.T_UNKNOWN;
    }

    private String adScript() {
        if (adScript != null) return adScript;
        try { adScript = new String(readAll(getAssets().open("kursor/adblock.js")), "UTF-8"); }
        catch (Exception e) { adScript = ""; }
        return adScript;
    }

    /** Reklam kutularını gizleyen katmanı sayfaya yükler. */
    private void injectAdblock() {
        String url = web.getUrl();
        if (!adOn || url == null || isAppPage(url)) return;
        String h = pageHost;
        if (h.isEmpty() || siteOff(h)) return;
        web.evaluateJavascript(adScript(), null);
    }

    /** Sayfadaki kozmetik katman ve ana sayfa için köprü. Kullanıcı verisi dışarı gönderilmez. */
    private class AdBridge {
        @JavascriptInterface
        public String base(String host) {
            if (!adOn || host == null) return "";
            host = host.toLowerCase(Locale.ROOT);
            if (siteOff(host)) return "";
            return Filters.engine().baseCss(host);
        }

        @JavascriptInterface
        public String tokens(String host, String json) {
            if (!adOn || host == null) return "";
            host = host.toLowerCase(Locale.ROOT);
            if (siteOff(host)) return "";
            try {
                JSONArray a = new JSONArray(json);
                List<String> keys = new ArrayList<>(a.length());
                for (int i = 0; i < a.length() && i < 4000; i++) keys.add(a.getString(i));
                return Filters.engine().tokenCss(host, keys);
            } catch (Exception e) {
                return "";
            }
        }
    }

    /** Kalkan düğmesi / basılı Menü: bu sitede ya da her yerde aç-kapat. */
    private void showAdDialog() {
        final String ph = pageHost;
        final String site = ph.isEmpty() ? null : AdBlocker.baseDomain(ph);
        final boolean off = site != null && adOffSites.contains(site);
        String msg = !adOn ? getString(R.string.ad_all_off)
                : site == null ? getString(R.string.ad_home)
                : off ? getString(R.string.ad_site_off)
                : getString(R.string.ad_blocked_n, blockedCount.get());
        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(site != null ? site : getString(R.string.ad_title))
                .setMessage(msg)
                .setNegativeButton(android.R.string.ok, null);
        if (site != null && adOn) {
            b.setPositiveButton(off ? R.string.ad_site_enable : R.string.ad_site_disable,
                    new DialogInterface.OnClickListener() {
                        @Override public void onClick(DialogInterface d, int w) {
                            Set<String> next = new HashSet<>(adOffSites);
                            if (off) next.remove(site); else next.add(site);
                            adOffSites = next;
                            prefs.edit().putStringSet("adOffSites", next).apply();
                            web.reload();
                        }
                    });
        }
        b.setNeutralButton(adOn ? R.string.ad_all_disable : R.string.ad_all_enable,
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        adOn = !adOn;
                        prefs.edit().putBoolean("adblock", adOn).apply();
                        if (!onHomePage) web.reload();
                    }
                });
        b.show();
    }

    /** Uygulama açık kaldıkça saatte bir: zamanı geldiyse filtre listelerini sessizce güncelle. */
    private final Runnable filterTick = new Runnable() {
        @Override public void run() {
            Filters.tick(MainActivity.this);
            handler.postDelayed(this, 3600_000L);
        }
    };

    // ------------------------------------------------------------------ PC mode: mouse + keyboard on TV

    /** Fare kullanılıyor: ekrandaki sahte imleç gizli, tıklamalar farenin olduğu yere gider. */
    private boolean mouseMode = false;
    /** Harf tuşu gönderen cihazlar = gerçek klavye (kumandalar harf göndermez). */
    private final Set<Integer> keyboards = new HashSet<>();
    private boolean pcHintShown = false;

    private static boolean isDpadOrOk(int code) {
        return code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    private boolean isPcKeyboard(KeyEvent e) {
        InputDevice d = e.getDevice();
        if (d == null || d.isVirtual() || d.getKeyboardType() != InputDevice.KEYBOARD_TYPE_ALPHABETIC) return false;
        int code = e.getKeyCode();
        if (!keyboards.contains(d.getId())) {
            // Bazı kumandalar kendini klavye gibi tanıtır; ilk harf/rakam tuşuna kadar kumanda say.
            // Some remotes report themselves as keyboards; treat as a remote until a letter key arrives.
            boolean letter = code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_Z;
            if (!letter && !e.isCtrlPressed() && !e.isAltPressed() && code != KeyEvent.KEYCODE_TAB
                    && code != KeyEvent.KEYCODE_SPACE && code != KeyEvent.KEYCODE_ESCAPE
                    && !(code >= KeyEvent.KEYCODE_F1 && code <= KeyEvent.KEYCODE_F12)) return false;
            keyboards.add(d.getId());
            if (!pcHintShown) { pcHintShown = true; showHint(getString(R.string.pc_keyboard), 7000); }
        }
        return true;
    }

    /** Tarayıcı kısayolları / browser shortcuts */
    private boolean pcShortcut(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ctrl = e.isCtrlPressed(), alt = e.isAltPressed();
        if ((ctrl && code == KeyEvent.KEYCODE_L) || code == KeyEvent.KEYCODE_F6) { showAddressDialog(); return true; }
        if (code == KeyEvent.KEYCODE_F5 || (ctrl && code == KeyEvent.KEYCODE_R)) { web.reload(); return true; }
        if (alt && code == KeyEvent.KEYCODE_DPAD_LEFT) { if (web.canGoBack()) web.goBack(); return true; }
        if ((alt && code == KeyEvent.KEYCODE_DPAD_RIGHT) || code == KeyEvent.KEYCODE_FORWARD) { if (web.canGoForward()) web.goForward(); return true; }
        if ((alt && code == KeyEvent.KEYCODE_MOVE_HOME) || code == KeyEvent.KEYCODE_HOME) { goHome(); return true; }
        if (ctrl && (code == KeyEvent.KEYCODE_EQUALS || code == KeyEvent.KEYCODE_PLUS || code == KeyEvent.KEYCODE_NUMPAD_ADD)) { textZoom(10); return true; }
        if (ctrl && (code == KeyEvent.KEYCODE_MINUS || code == KeyEvent.KEYCODE_NUMPAD_SUBTRACT)) { textZoom(-10); return true; }
        if (ctrl && (code == KeyEvent.KEYCODE_0 || code == KeyEvent.KEYCODE_NUMPAD_0)) { textZoom(0); return true; }
        if (code == KeyEvent.KEYCODE_F1 || (ctrl && code == KeyEvent.KEYCODE_SLASH)) { showPcHelp(); return true; }
        if (code == KeyEvent.KEYCODE_MENU) { showAdDialog(); return true; }
        return false;
    }

    private void textZoom(int delta) {
        WebSettings s = web.getSettings();
        int z = delta == 0 ? 100 : Math.max(50, Math.min(250, s.getTextZoom() + delta));
        s.setTextZoom(z);
        showHint(getString(R.string.pc_zoom, z), 1500);
    }

    private void showAddressDialog() {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        in.setImeOptions(EditorInfo.IME_ACTION_GO);
        String u = web.getUrl();
        in.setText(u == null || isAppPage(u) ? "" : u);
        in.selectAll();
        final AlertDialog d = new AlertDialog.Builder(this)
                .setTitle(R.string.address_hint)
                .setView(in)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dd, int w) { openAddress(in.getText().toString()); }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        in.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int id, KeyEvent ev) {
                d.dismiss();
                openAddress(v.getText().toString());
                return true;
            }
        });
        d.show();
        in.requestFocus();
    }

    private void showPcHelp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.pc_help_title)
                .setMessage(R.string.pc_help)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Fare hareketi: sahte imleci gizle, konumu takip et / mouse movement: hide the fake cursor, track position */
    private void onMouse(MotionEvent e) {
        if (touchUi) return;
        cx = e.getX();
        cy = e.getY();
        if (!mouseMode) {
            mouseMode = true;
            cursor.setVisibility(View.INVISIBLE);
            if (videoMode) { userLeftVideo = true; setVideoMode(false, false); }
            if (!pcHintShown) { pcHintShown = true; showHint(getString(R.string.pc_mouse), 7000); }
        }
    }

    private static boolean isMouse(MotionEvent e) {
        return (e.getSource() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE
                || (e.getPointerCount() > 0 && e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (isMouse(e)) onMouse(e);
        return super.dispatchGenericMotionEvent(e);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        if (isMouse(e)) {
            onMouse(e);
        }
        return super.dispatchTouchEvent(e);
    }

    // ------------------------------------------------------------------ home button

    /**
     * Köşede, kenarlardan boşluklu yüzen ana sayfa düğmesi. Telefonda sürüklenip sağ/sol kenara
     * yerleştirilebilir (yeri hatırlanır); TV'de imleçle tıklanır.
     * Floating home button in a corner with margins from the edges. On phones it can be dragged to the
     * left/right edge (position is remembered); on TV it is clicked with the cursor.
     */
    private void buildHomeFab() {
        homeFab = new TextView(this);
        homeFab.setText("⌂");
        homeFab.setContentDescription(getString(R.string.home));
        homeFab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        homeFab.setTextColor(Color.WHITE);
        homeFab.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xD91B1F4B);
        bg.setStroke(dp(2), 0xCCFF8A3D);
        homeFab.setBackground(bg);
        homeFab.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= 21) homeFab.setElevation(dp(6));
        root.addView(homeFab, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP | Gravity.LEFT));
        homeFab.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY, startX, startY;
            boolean dragging;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX(); downY = e.getRawY();
                        startX = v.getX(); startY = v.getY();
                        dragging = false;
                        v.setAlpha(1f);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                        if (!dragging && Math.hypot(dx, dy) > dp(10)) dragging = true;
                        if (dragging) { v.setX(startX + dx); v.setY(startY + dy); }
                        return true;
                    case MotionEvent.ACTION_UP:
                        v.setAlpha(0.9f);
                        if (!dragging) { goHome(); return true; }
                        // En yakın kenara yapış, yeri kaydet / snap to the nearest edge and remember
                        int side = v.getX() + v.getWidth() / 2f < root.getWidth() / 2f ? 0 : 1;
                        float top = fabTop(), bottom = fabBottom();
                        float frac = bottom > top ? (v.getY() - top) / (bottom - top) : 1f;
                        prefs.edit().putInt("homeSide", side)
                                .putFloat("homeY", Math.max(0f, Math.min(1f, frac))).apply();
                        placeHomeFab();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        v.setAlpha(0.9f);
                        placeHomeFab();
                        return true;
                }
                return false;
            }
        });
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) placeHomeFab();
            }
        });
    }

    private float fabTop() {
        int tb = touchUi && toolbar != null && toolbar.getVisibility() == View.VISIBLE ? toolbar.getHeight() : 0;
        return insetTop + tb + dp(12);
    }

    private float fabBottom() {
        return root.getHeight() - insetBottom - dp(52) - dp(16);
    }

    private void placeHomeFab() {
        if (homeFab == null || root.getWidth() == 0) return;
        int side = prefs.getInt("homeSide", 0);
        float frac = prefs.getFloat("homeY", -1f);
        float top = fabTop(), bottom = fabBottom();
        // Varsayılan: sol alt, sitelerin alt menü çubuklarının biraz üstünde
        // Default: bottom-left, a little above sites' bottom navigation bars
        float y = frac < 0 ? bottom - dp(64) : top + frac * (bottom - top);
        y = Math.max(top, Math.min(bottom, y));
        homeFab.setX(side == 0 ? dp(16) : root.getWidth() - dp(52) - dp(16));
        homeFab.setY(y);
        homeFab.setAlpha(0.9f);
    }

    /** Ana sayfada, tam ekranda ve TV'de video izlerken gizli; diğer her yerde görünür. */
    private void updateHomeFab() {
        if (homeFab == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post(new Runnable() { @Override public void run() { updateHomeFab(); } });
            return;
        }
        boolean show = !onHomePage && customView == null && !wantFull && !(videoMode && !touchUi)
                && !(menuBar != null && menuVisible());
        int vis = show ? View.VISIBLE : View.GONE;
        if (homeFab.getVisibility() != vis) {
            homeFab.setVisibility(vis);
            if (show) {
                placeHomeFab();
                homeFab.bringToFront();
                if (!touchUi) cursor.bringToFront();   // TV imleci düğmenin üstünde kalsın / keep the TV cursor on top
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String desktopUserAgent(String defaultUa) {
        String chrome = "130.0.0.0";
        Matcher m = Pattern.compile("Chrome/([\\d.]+)").matcher(defaultUa == null ? "" : defaultUa);
        if (m.find()) chrome = m.group(1);
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/"
                + chrome + " Safari/537.36";
    }

    private final Runnable relayout = new Runnable() {
        @Override public void run() {
            viewportFixes = 0;
            applyViewport();
            if (wantFull && videoMode) applyCssFullscreen(true, true);
        }
    };

    private final Runnable hideHint = new Runnable() {
        @Override public void run() { hint.setVisibility(View.GONE); }
    };

    private void showHint(String text, long ms) {
        hint.setText(text);
        hint.setVisibility(View.VISIBLE);
        hint.bringToFront();
        handler.removeCallbacks(hideHint);
        handler.postDelayed(hideHint, ms);
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }

    // ------------------------------------------------------------------ cursor view

    private static class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private float x, y;
        private float pulse = 0f;
        private final float size;

        CursorView(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            size = 26 * d;
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(Color.BLACK);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(2 * d);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            ring.setColor(0xFFA435F0);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3 * d);
            setClickable(false);
            setFocusable(false);
        }

        void moveTo(float nx, float ny) { x = nx; y = ny; invalidate(); }

        void pulse() {
            pulse = 1f;
            invalidate();
            postDelayed(new Runnable() {
                @Override public void run() { pulse = 0f; invalidate(); }
            }, 180);
        }

        @Override
        protected void onDraw(Canvas c) {
            if (pulse > 0) c.drawCircle(x, y, size * 0.8f, ring);
            float s = size;
            arrow.reset();
            arrow.moveTo(x, y);
            arrow.lineTo(x, y + s);
            arrow.lineTo(x + s * 0.28f, y + s * 0.74f);
            arrow.lineTo(x + s * 0.48f, y + s * 1.12f);
            arrow.lineTo(x + s * 0.62f, y + s * 1.05f);
            arrow.lineTo(x + s * 0.43f, y + s * 0.68f);
            arrow.lineTo(x + s * 0.78f, y + s * 0.68f);
            arrow.close();
            c.drawPath(arrow, fill);
            c.drawPath(arrow, stroke);
        }
    }
}
