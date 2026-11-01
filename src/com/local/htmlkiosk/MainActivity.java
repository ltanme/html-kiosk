package com.local.htmlkiosk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.os.Message;
import android.speech.tts.TextToSpeech;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int PORT = 9090;
    private static final int REQ_FILE = 101;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private ScrollView homeScroll;
    private LinearLayout listContainer;
    private TextView countView;
    private TextView addrView;
    private TextView statusView;

    private WebView webView;
    /** 伪装成桌面 Chrome 的 UA；初始化时存一份，后台线程要用（WebView 方法不能跨线程调） */
    private String desktopUa;
    private TextToSpeech tts;
    private boolean ttsReady;
    private String ttsPendingText;
    private String ttsPendingLang;
    private float ttsPendingRate = 1f;
    private TextView floatBack;
    private TextView loadingView;
    private TextView biliBtn;
    /** 未登录时先弹登录页，登录成功后自动回到这个地址。 */
    private String pendingReturnUrl;
    private long lastLoginGate;
    /** 当前页面地址：onPageStarted/Finished 在主线程更新，其它线程只读，
     *  避免在 WebView 后台线程直接调 webView.getUrl()（会抛线程校验异常） */
    private volatile String currentUrl;
    private boolean readerOpen = false;
    private long lastBackPress = 0;

    private File libraryDir;
    private HttpServer server;

    private ValueCallback<Uri[]> filePathCallback;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    /** 返回胶囊空闲时的透明度：只淡不隐藏，避免点击穿透到网页内容上。 */
    private static final float PILL_IDLE_ALPHA = 0.38f;

    private final Runnable hidePill = new Runnable() {
        @Override
        public void run() {
            if (floatBack == null) return;
            // 不能置 GONE：一旦这个位置没有 View 接收触摸，
            // 点击就会穿透到网页里（课本页左侧栏正好压在同一角落）。
            floatBack.animate().alpha(PILL_IDLE_ALPHA).setDuration(220).start();
        }
    };

    // ------------------------------------------------------------------ life

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 强制横屏：manifest 里虽然写了 sensorLandscape，但这个 ROM 的桌面模式
        // 偶尔会把窗口拉回竖屏，导致下半屏黑掉，这里再钉一次
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        File base = getExternalFilesDir(null);
        if (base == null) base = getFilesDir();
        libraryDir = new File(base, "library");
        if (!libraryDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            libraryDir.mkdirs();
        }

        root = new FrameLayout(this);
        root.setBackgroundColor(0xFFF4F8FD);
        setContentView(root);

        buildHome();
        buildFloatBack();
        buildLoadingView();
        startServer();
        refreshList();
        goImmersive();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (addrView != null) {
            addrView.setText("http://" + getLocalIp() + ":" + PORT);
        }
        if (!readerOpen) refreshList();
        updateBiliState();
        goImmersive();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // manifest 里声明了 configChanges，旋转时不会重建 Activity：
        // 这里手动重排一次，否则窗口尺寸变了但内容还是旧尺寸，下半屏会留黑
        goImmersive();
        if (root != null) root.requestLayout();
        if (webView != null) webView.requestLayout();
    }

    @Override
    protected void onDestroy() {
        if (tts != null) {
            try {
                tts.stop();
                tts.shutdown();
            } catch (Throwable ignored) {
            }
            tts = null;
            ttsReady = false;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        if (webView != null) {
            try {
                webView.destroy();
            } catch (Exception ignored) {
            }
            webView = null;
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) goImmersive();
    }

    @Override
    public void onBackPressed() {
        handleBack();
    }

    // ------------------------------------------------------------------ home

    private void buildHome() {
        homeScroll = new ScrollView(this);
        homeScroll.setFillViewport(true);
        homeScroll.setBackgroundColor(0xFFF4F8FD);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(30), dp(24), dp(30), dp(30));
        homeScroll.addView(col, new ScrollView.LayoutParams(MATCH, WRAP));

        // 标题 + 刷新
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.addView(text("AI+自学", 26, 0xFF0F2038, Typeface.BOLD));
        titleBox.addView(text("上传任意 HTML，点开即全屏浏览", 13, 0xFF7B8CA3, Typeface.NORMAL));
        header.addView(titleBox, new LinearLayout.LayoutParams(0, WRAP, 1f));

        TextView pin = text("添加到桌面", 13, 0xFF284262, Typeface.BOLD);
        pin.setGravity(Gravity.CENTER);
        pin.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable pbg = rounded(0xFFFFFFFF, 11);
        pbg.setStroke(dp(1), 0xFFDBE6F3);
        pin.setBackground(pbg);
        pin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addLauncherShortcut();
            }
        });
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(WRAP, WRAP);
        plp.rightMargin = dp(10);
        header.addView(pin, plp);

        TextView refresh = text("刷新", 13, 0xFF284262, Typeface.BOLD);
        refresh.setGravity(Gravity.CENTER);
        refresh.setPadding(dp(20), dp(10), dp(20), dp(10));
        GradientDrawable rbg = rounded(0xFFFFFFFF, 11);
        rbg.setStroke(dp(1), 0xFFDBE6F3);
        refresh.setBackground(rbg);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshList();
                toast("已刷新书库");
            }
        });
        header.addView(refresh);
        col.addView(header);

        // 地址卡片
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(18), dp(24), dp(18));
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF102F59, 0xFF128076});
        g.setCornerRadius(dp(18));
        card.setBackground(g);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(MATCH, WRAP);
        clp.topMargin = dp(18);
        col.addView(card, clp);

        card.addView(text("在电脑 / 手机浏览器打开这个地址上传", 12, 0xFFB9D9F2, Typeface.BOLD));
        addrView = text("http://" + getLocalIp() + ":" + PORT, 30, 0xFFFFFFFF, Typeface.BOLD);
        addrView.setTextIsSelectable(true);
        addrView.setPadding(0, dp(2), 0, dp(2));
        card.addView(addrView);
        statusView = text("端口 " + PORT + " · 上传后书库自动刷新", 12, 0xFFCBE6F8, Typeface.NORMAL);
        card.addView(statusView);

        // B 站登录：在 App 内打开登录页，扫码/短信登录一次后 Cookie 会一直保留
        biliBtn = text("B 站登录", 13, 0xFF102F59, Typeface.BOLD);
        biliBtn.setPadding(dp(18), dp(9), dp(18), dp(9));
        biliBtn.setBackground(rounded(0xFFFFFFFF, 99));
        biliBtn.setElevation(dp(2));
        biliBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUrl("https://passport.bilibili.com/login");
            }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(WRAP, WRAP);
        blp.topMargin = dp(12);
        card.addView(biliBtn, blp);

        // 书库标题
        LinearLayout sec = new LinearLayout(this);
        sec.setOrientation(LinearLayout.HORIZONTAL);
        sec.setGravity(Gravity.BOTTOM);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(MATCH, WRAP);
        slp.topMargin = dp(26);
        slp.bottomMargin = dp(12);
        col.addView(sec, slp);
        countView = text("书库 · 0 个页面", 16, 0xFF0F2038, Typeface.BOLD);
        sec.addView(countView, new LinearLayout.LayoutParams(0, WRAP, 1f));
        sec.addView(text("点开即全屏 · 长按可删除", 12, 0xFF93A4B9, Typeface.NORMAL));

        // 列表
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        col.addView(listContainer, new LinearLayout.LayoutParams(MATCH, WRAP));

        // 页脚
        TextView foot = text("也可以用 adb push --sync 到\n" + libraryDir.getAbsolutePath(), 11,
                0xFF93A4B9, Typeface.NORMAL);
        foot.setPadding(0, dp(20), 0, 0);
        foot.setLineSpacing(dp(2), 1f);
        col.addView(foot);

        root.addView(homeScroll, new FrameLayout.LayoutParams(MATCH, MATCH));
    }

    private void refreshList() {
        if (listContainer == null || libraryDir == null) return;
        List<File> files = new ArrayList<File>();
        collect(libraryDir, files, 0);
        Collections.sort(files, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });

        countView.setText("书库 · " + files.size() + " 个页面");
        listContainer.removeAllViews();

        if (files.isEmpty()) {
            listContainer.addView(emptyView());
            return;
        }
        for (File f : files) {
            listContainer.addView(fileRow(f));
        }
    }

    private View emptyView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(24), dp(34), dp(24), dp(34));
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFFFFFFFF);
        d.setCornerRadius(dp(18));
        d.setStroke(dp(2), 0xFFC6D9EE, dp(8), dp(6));
        box.setBackground(d);

        TextView t1 = text("书库还是空的", 17, 0xFF0F2038, Typeface.BOLD);
        t1.setGravity(Gravity.CENTER);
        box.addView(t1);

        TextView t2 = text("在电脑浏览器打开上面的地址，上传任意 .html", 13, 0xFF6B7F99, Typeface.NORMAL);
        t2.setGravity(Gravity.CENTER);
        t2.setPadding(0, dp(6), 0, 0);
        box.addView(t2);

        TextView t3 = text("上传完成后这里会自动出现，点开就是全屏，没有地址栏", 12, 0xFF93A4B9, Typeface.NORMAL);
        t3.setGravity(Gravity.CENTER);
        t3.setPadding(0, dp(3), 0, 0);
        box.addView(t3);
        return box;
    }

    private View fileRow(final File f) {
        final String rel = relPath(f);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(13), dp(18), dp(13));
        GradientDrawable bg = rounded(0xFFFFFFFF, 14);
        bg.setStroke(dp(1), 0xFFE2ECF7);
        row.setBackground(bg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        rlp.bottomMargin = dp(10);
        row.setLayoutParams(rlp);

        String first = "?";
        if (!f.getName().isEmpty()) {
            first = new String(Character.toChars(f.getName().codePointAt(0))).toUpperCase(Locale.CHINA);
        }
        TextView badge = text(first, 17, 0xFF1D4ED8, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(rounded(0xFFE8F1FD, 12));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(44), dp(44));
        blp.rightMargin = dp(14);
        row.addView(badge, blp);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(f.getName(), 16, 0xFF0F2038, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        mid.addView(name);

        String dir = rel.contains("/") ? rel.substring(0, rel.lastIndexOf('/')) : "书库根目录";
        TextView meta = text(dir + " · " + fmtSize(f.length()) + " · " + fmtTime(f.lastModified()),
                12, 0xFF93A4B9, Typeface.NORMAL);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        mid.addView(meta);
        row.addView(mid, new LinearLayout.LayoutParams(0, WRAP, 1f));

        row.addView(text("›", 24, 0xFFB8C6D8, Typeface.NORMAL));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPage(rel);
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                confirmDelete(f, rel);
                return true;
            }
        });
        return row;
    }

    private void confirmDelete(final File f, String rel) {
        new AlertDialog.Builder(this)
                .setTitle("删除课件")
                .setMessage(rel)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int which) {
                        if (f.delete()) {
                            refreshList();
                            toast("已删除");
                        } else {
                            toast("删除失败");
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ------------------------------------------------------------------ reader

    private void buildFloatBack() {
        floatBack = text("‹ 书库", 14, 0xFF173352, Typeface.BOLD);
        floatBack.setPadding(dp(16), dp(9), dp(18), dp(9));
        GradientDrawable d = rounded(0xE6FFFFFF, 99);
        d.setStroke(dp(1), 0x220F2038);
        floatBack.setBackground(d);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(18);
        lp.bottomMargin = dp(18);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        floatBack.setLayoutParams(lp);
        floatBack.setElevation(dp(6));
        floatBack.setVisibility(View.GONE);
        floatBack.setAlpha(0f);
        floatBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleBack();
            }
        });
        root.addView(floatBack);
    }

    private void buildLoadingView() {
        loadingView = text("正在打开…", 15, 0xFF41607F, Typeface.NORMAL);
        loadingView.setPadding(dp(20), dp(11), dp(20), dp(11));
        GradientDrawable d = rounded(0xEEFFFFFF, 99);
        d.setStroke(dp(1), 0x220F2038);
        loadingView.setBackground(d);
        loadingView.setElevation(dp(4));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(WRAP, WRAP);
        lp.gravity = Gravity.CENTER;
        loadingView.setLayoutParams(lp);
        loadingView.setVisibility(View.GONE);
        root.addView(loadingView);
    }

    private void showLoading(boolean on) {
        if (loadingView == null) return;
        loadingView.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on && floatBack != null) root.bringChildToFront(loadingView);
    }

    /** 用系统的「固定快捷方式」把图标放到桌面，会弹一个系统确认框。 */
    private void addLauncherShortcut() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                toast("请从应用列表手动拖到桌面");
                return;
            }
            ShortcutManager sm = (ShortcutManager) getSystemService(SHORTCUT_SERVICE);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                toast("当前桌面不支持自动添加，请从应用列表拖到桌面");
                return;
            }
            int iconRes = getApplicationInfo().icon;
            if (iconRes == 0) iconRes = android.R.drawable.sym_def_app_icon;
            Intent open = new Intent(this, MainActivity.class);
            open.setAction(Intent.ACTION_MAIN);
            open.addCategory(Intent.CATEGORY_LAUNCHER);
            ShortcutInfo info = new ShortcutInfo.Builder(this, "kiosk")
                    .setShortLabel("AI+自学")
                    .setLongLabel("AI+自学 · 课件书库")
                    .setIcon(Icon.createWithResource(this, iconRes))
                    .setIntent(open)
                    .build();
            sm.requestPinShortcut(info, null);
            toast("请在系统弹窗里点「添加」");
        } catch (Throwable t) {
            toast("添加失败：" + t.getMessage());
        }
    }

    /** 更新首页「B 站登录」按钮上的状态。 */
    private void updateBiliState() {
        if (biliBtn == null) return;
        String cookies = CookieManager.getInstance().getCookie("https://www.bilibili.com");
        boolean logged = cookies != null && cookies.contains("SESSDATA=");
        biliBtn.setText(logged ? "B 站已登录 · 重新登录" : "B 站登录");
    }

    private void openPage(String rel) {
        openUrl("http://127.0.0.1:" + PORT + "/view/" + encodePath(rel));
    }

    /** 在同一个 WebView 里打开任意地址（课件页或外站），不跳系统浏览器。 */
    private void openUrl(String url) {
        ensureWebView();
        homeScroll.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        readerOpen = true;
        showLoading(true);
        webView.loadUrl(url);
        pokeBackPill();
    }

    private void closeReader() {
        ui.removeCallbacks(hidePill);
        if (floatBack != null) {
            floatBack.animate().cancel();
            floatBack.setVisibility(View.GONE);
            floatBack.setAlpha(0f);
        }
        readerOpen = false;
        showLoading(false);
        // 直接销毁 WebView，而不是 loadUrl("about:blank")：
        // 后者会把 about:blank 写进历史记录，下次打开页面按返回就会退到它，
        // 结果是白屏、书库也不显示。销毁同时能把渲染进程占的内存还回去。
        destroyWebView();
        homeScroll.setVisibility(View.VISIBLE);
        refreshList();
    }

    private void destroyWebView() {
        if (tts != null && ttsReady) {
            try {
                tts.stop(); // 回书库就别再念了
            } catch (Throwable ignored) {
            }
        }
        if (webView == null) return;
        final WebView v = webView;
        webView = null;
        try {
            android.view.ViewParent parent = v.getParent();
            if (parent instanceof android.view.ViewGroup) ((android.view.ViewGroup) parent).removeView(v);
            v.stopLoading();
            v.clearHistory();
            v.removeAllViews();
            v.destroy();
        } catch (Throwable ignored) {
        }
    }

    // ---------- 语音朗读：WebView 没有 Web Speech API，改走系统 TTS ----------

    /** 暴露给网页：window.AndroidTTS.speak(text, lang, rate) */
    private class TtsBridge {
        @JavascriptInterface
        public void speak(final String text, final String lang, final float rate) {
            if (TextUtils.isEmpty(text)) return;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    speakNow(text, lang, rate);
                }
            });
        }

        @JavascriptInterface
        public void stop() {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (tts != null && ttsReady) tts.stop();
                }
            });
        }
    }

    private void speakNow(String text, String lang, float rate) {
        Log.i("Kiosk", "speakNow text=" + text + " lang=" + lang + " rate=" + rate
                + " tts=" + (tts == null ? "null" : "set") + " ready=" + ttsReady);
        ttsPendingText = text;
        ttsPendingLang = lang;
        ttsPendingRate = rate;
        if (tts == null) {
            // 首次调用才初始化，没装语音引擎也不拖慢启动
            tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    ttsReady = status == TextToSpeech.SUCCESS;
                    Log.i("Kiosk", "TTS onInit status=" + status);
                    if (!ttsReady) {
                        toast("系统没有可用的语音引擎（TTS）");
                        ttsPendingText = null;
                        // 允许下次重试，否则一次失败会永久哑掉
                        try {
                            tts.shutdown();
                        } catch (Throwable ignored) {
                        }
                        tts = null;
                        return;
                    }
                    speakPending();
                }
            });
            return;
        }
        speakPending();
    }

    private void speakPending() {
        if (!ttsReady || tts == null || ttsPendingText == null) return;
        final String text = ttsPendingText;
        String lang = ttsPendingLang != null ? ttsPendingLang : "en-US";
        float rate = ttsPendingRate;
        ttsPendingText = null;

        Locale loc;
        String low = lang.toLowerCase(Locale.ROOT);
        if (low.startsWith("zh")) loc = Locale.CHINA;
        else if (low.startsWith("en")) loc = Locale.US;
        else loc = Locale.forLanguageTag(lang.replace('_', '-'));

        int r = tts.setLanguage(loc);
        if ((r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED)
                && loc != Locale.US) {
            r = tts.setLanguage(Locale.US); // 没有对应语言包时退回英语
        }
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            toast("系统缺少该语言的语音包");
            return;
        }
        float sp = (rate <= 0.05f) ? 1.0f : Math.max(0.3f, Math.min(2.0f, rate));
        tts.setSpeechRate(sp);
        tts.setPitch(1.0f);
        int r2 = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "kiosk-tts");
        Log.i("Kiosk", "setLanguage(" + loc + ")=" + r + " speak=" + r2
                + " engine=" + tts.getDefaultEngine());
    }

    private void injectTtsShim(WebView view) {
        if (view == null) return;
        try {
            view.evaluateJavascript(TTS_SHIM, null);
        } catch (Throwable ignored) {
        }
    }

    /**
     * 给页面补一个 speechSynthesis 壳：有真实现（getVoices 非空）就不动它，
     * 否则把 speak/cancel/getVoices 接到 AndroidTTS。
     * 注意 WebView 里 speechSynthesis 多半是 undefined，而真 Chrome 里它是只读 getter，
     * 所以用 defineProperty 覆盖而不是直接赋值。
     */
    private static final String TTS_SHIM =
            "(function(){" +
            " if (window.__kioskTts) return; window.__kioskTts = 1;" +
            " try { var real = window.speechSynthesis;" +
            "   if (real && real.speak && real.getVoices && real.getVoices().length > 0) return; } catch(e){}" +
            " function U(t){ this.text = t==null ? '' : String(t); this.lang='en-US'; this.rate=1;" +
            "   this.pitch=1; this.volume=1; this.voice=null; this.onend=null; this.onerror=null; }" +
            " try { window.SpeechSynthesisUtterance = window.SpeechSynthesisUtterance || U; } catch(e){}" +
            " var api = {" +
            "  speaking:false, pending:false, paused:false, onvoiceschanged:null," +
            "  speak:function(u){ if(!u) return;" +
            "    var t = (u.text!=null) ? String(u.text) : String(u);" +
            "    var l = u.lang || 'en-US'; var r = (typeof u.rate==='number' && u.rate>0) ? u.rate : 1;" +
            "    try { AndroidTTS.speak(t, l, r); } catch(e){}" +
            "    try { if(u.onend) setTimeout(function(){ try{u.onend({})}catch(e){} }," +
            "          Math.max(400, t.length*90)); } catch(e){} }," +
            "  cancel:function(){ try { AndroidTTS.stop(); } catch(e){} }," +
            "  pause:function(){ try { AndroidTTS.stop(); } catch(e){} }," +
            "  resume:function(){}," +
            "  getVoices:function(){ return []; }" +
            " };" +
            " try { Object.defineProperty(window,'speechSynthesis',{value:api,configurable:true,writable:true}); }" +
            " catch(e){ try { window.speechSynthesis = api; } catch(e2){} }" +
            "})()";

    private void handleBack() {
        if (!readerOpen) {
            long now = System.currentTimeMillis();
            if (now - lastBackPress < 2000) {
                finish();
            } else {
                lastBackPress = now;
                toast("再按一次返回退出");
            }
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            pokeBackPill();
            return;
        }
        closeReader();
    }

    private void pokeBackPill() {
        if (!readerOpen || floatBack == null) return;
        if (floatBack.getVisibility() != View.VISIBLE) {
            floatBack.setVisibility(View.VISIBLE);
            floatBack.setAlpha(PILL_IDLE_ALPHA);
        }
        floatBack.animate().alpha(1f).setDuration(140).start();
        ui.removeCallbacks(hidePill);
        ui.postDelayed(hidePill, 4500);
    }

    /** 把 WebView 的安卓 UA 换成 macOS 桌面 Chrome，版本号沿用本机 Chromium。 */
    // --------------------------------------------------- B 站播放器「倍速」修复
    //
    // player.bilibili.com 给「站外未知嵌入」下发的是受限播放器：它的 HTML 里
    //     const getChannelKind = function () { return nano.ChannelKind.Embedded_Other; };
    // 是写死的，于是 channelKind=Embedded_Other → embeddedEnv=3，整个倍速控件
    // 退化成一个「进入哔哩哔哩」按钮（点一下只会暂停 + 尝试跳转），选不了倍速。
    //
    // 纯前端改不了（HTML 是服务端生成的，跟 Referer/UA 无关），所以在 App 里
    // 自己取一次播放器 HTML，把这一个枚举换成正常的 Embedded，再交给 WebView。
    // 取不到、或者 B 站改版后找不到这句，就返回 null 原样放行，绝不挡播放。
    private static final String BILI_EMBED_HACK_FROM = "ChannelKind.Embedded_Other";
    private static final String BILI_EMBED_HACK_TO = "ChannelKind.Embedded";

    /**
     * 触摸适配：B 站桌面播放器是「鼠标移到倍速按钮上就展开菜单」，
     * 手指点第一次能触发一次合成的 mouseenter，第二次点击就没有了——
     * 于是「2.0x 想改回 1.5x」时菜单根本不弹。
     * 这段脚本让点按钮就等于展开菜单（最长保持 20 秒），
     * 点档位或点别处就交还给 B 站原逻辑。
     */
    private static final String BILI_TOUCH_SCRIPT =
            "<script>(function(){"
            + "var ST='bpx-state-show',SEL='.bpx-player-ctrl-playbackrate',"
            + "ITEM='.bpx-player-ctrl-playbackrate-menu-item';"
            + "var box=null,obs=null,until=0;"
            + "function stop(){if(obs){obs.disconnect();obs=null;}box=null;until=0;}"
            + "function show(){if(!box)return;if(Date.now()>until){stop();return;}"
            + "if(!box.classList.contains(ST))box.classList.add(ST);}"
            + "document.addEventListener('click',function(e){"
            + "var t=e.target;if(!t||!t.closest)return;"
            + "if(t.closest(ITEM)){stop();return;}"
            + "var b=t.closest(SEL);if(!b){stop();return;}"
            + "box=b;until=Date.now()+20000;show();"
            + "if(obs)obs.disconnect();obs=new MutationObserver(show);"
            + "obs.observe(box,{attributes:true,attributeFilter:['class']});"
            + "},true);})();</script>";

    private final LinkedHashMap<String, byte[]> biliHtmlCache =
            new LinkedHashMap<String, byte[]>(8, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > 24;
                }
            };

    /** 拉一次播放器 HTML 并改写嵌入模式；失败返回 null，让 WebView 照常联网加载。 */
    private WebResourceResponse patchedBiliPlayer(String url) {
        byte[] body;
        synchronized (biliHtmlCache) {
            body = biliHtmlCache.get(url);
        }
        if (body == null) {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);
                String ua = desktopUa;
                conn.setRequestProperty("User-Agent", ua != null ? ua : desktopUserAgent(null));
                conn.setRequestProperty("Referer", "https://www.bilibili.com/");
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie != null && cookie.length() > 0) {
                    conn.setRequestProperty("Cookie", cookie);
                }
                if (conn.getResponseCode() != 200) return null;
                String html = new String(readBytes(conn.getInputStream()), "UTF-8");
                if (!html.contains(BILI_EMBED_HACK_FROM)) {
                    Log.w("Kiosk", "bili player html changed (" + html.length() + " chars), 只注入触摸脚本");
                }
                String out = html.replace(BILI_EMBED_HACK_FROM, BILI_EMBED_HACK_TO);
                if (out.contains("</body>")) {
                    out = out.replace("</body>", BILI_TOUCH_SCRIPT + "</body>");
                } else {
                    out = out + BILI_TOUCH_SCRIPT;
                }
                body = out.getBytes("UTF-8");
                Log.i("Kiosk", "bili player patched: " + body.length + " bytes");
                synchronized (biliHtmlCache) {
                    biliHtmlCache.put(url, body);
                }
            } catch (Throwable t) {
                Log.w("Kiosk", "bili player patch failed: " + t);
                return null;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return new WebResourceResponse("text/html", "utf-8", new ByteArrayInputStream(body));
    }

    private static byte[] readBytes(InputStream is) throws java.io.IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int r;
        while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
        is.close();
        return bo.toByteArray();
    }

    private static String desktopUserAgent(String ua) {
        String ver = "131.0.0.0";
        if (ua != null) {
            int i = ua.indexOf("Chrome/");
            if (i >= 0) {
                int s = i + 7, e = ua.indexOf(' ', s);
                ver = e > s ? ua.substring(s, e) : ua.substring(s);
            }
        }
        return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36"
                + " (KHTML, like Gecko) Chrome/" + ver + " Safari/537.36";
    }

    private void ensureWebView() {
        if (webView != null) return;
        WebView.setWebContentsDebuggingEnabled(true);

        webView = new KioskWebView(this);
        webView.setBackgroundColor(Color.WHITE);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setDefaultTextEncodingName("utf-8");

        // 用桌面浏览器 UA：B 站 CDN 只给「PC 浏览器 UA」放行视频分片，
// 安卓 UA（哪怕去掉了 wv 标记）一律 403，播放器还会引导去下载 B 站 App。
        s.setUserAgentString(desktopUa = desktopUserAgent(s.getUserAgentString()));

        // Cookie 需要长期保存（B 站登录态就靠它），并允许 iframe 里的第三方 Cookie
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        // WebView 不实现 Web Speech API，课件里的「点击发音」会直接报错。
        // 这里暴露一个极小的 TTS 桥，再用 JS 补一个 speechSynthesis 壳。
        webView.addJavascriptInterface(new TtsBridge(), "AndroidTTS");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || !request.isForMainFrame()) return false;
                return handleUrl(request.getUrl().toString());
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    showLoading(false);
                    toast("页面加载失败：" + (error != null ? error.getDescription() : ""));
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                currentUrl = url;
                showLoading(true);
                injectTtsShim(view);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                currentUrl = url;
                showLoading(false);
                injectTtsShim(view);
                CookieManager.getInstance().flush();
                boolean local = url != null && url.startsWith("http://127.0.0.1:" + PORT);
                if (floatBack != null) floatBack.setText(local ? "‹ 书库" : "‹ 返回");
                updateBiliState();
                // 刚在登录页登录成功：自动回到原来要看的页面
                if (pendingReturnUrl != null) {
                    if (isLoggedIn()) {
                        if (!url.equals(pendingReturnUrl)) {
                            final String back = pendingReturnUrl;
                            pendingReturnUrl = null;
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    openUrl(back);
                                }
                            });
                        } else {
                            pendingReturnUrl = null;
                        }
                    } else if (!isPassportUrl(url)) {
                        // 没登录就离开登录页（放弃登录），不再保留返回目标
                        pendingReturnUrl = null;
                    }
                }
            }

            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                final String u = request != null ? request.getUrl().toString() : "";
                // 课件里点「网页内试播」会去拉 player.bilibili.com，
                // 没登录时顺势弹登录页，登录完再回到课件页。
                try {
                    if (u.contains("player.bilibili.com/player.html") && !isLoggedIn()) {
                        gatePlayerLogin();
                    }
                } catch (Throwable ignored) {
                }
                // 播放器受限嵌入是 B 站按 HTML 写死的，只能拿到响应后改写一次
                try {
                    if (u.contains("player.bilibili.com/player.html")) {
                        return patchedBiliPlayer(u);
                    }
                } catch (Throwable ignored) {
                }
                return null;
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                // 大页面 + 内存吃紧时渲染进程会被系统回收：清掉它回到书库，避免白屏卡死。
                boolean crashed = detail != null && detail.didCrash();
                if (view.getParent() instanceof android.view.ViewGroup) {
                    ((android.view.ViewGroup) view.getParent()).removeView(view);
                }
                try {
                    view.destroy();
                } catch (Throwable ignored) {
                }
                if (webView == view) webView = null;
                readerOpen = false;
                showLoading(false);
                if (floatBack != null) {
                    floatBack.setVisibility(View.GONE);
                    floatBack.setAlpha(0f);
                }
                homeScroll.setVisibility(View.VISIBLE);
                refreshList();
                toast(crashed ? "页面渲染出错，已返回书库" : "内存不足，页面已关闭");
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                // target="_blank" / window.open：不弹新窗口，直接在本 WebView 里打开
                if (resultMsg != null && resultMsg.obj instanceof WebView.WebViewTransport) {
                    ((WebView.WebViewTransport) resultMsg.obj).setWebView(view);
                    resultMsg.sendToTarget();
                    return true;
                }
                return false;
            }
            @Override
            public boolean onJsAlert(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                result.confirm();
                            }
                        })
                        .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(android.content.DialogInterface d) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, final JsResult result) {
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                result.confirm();
                            }
                        })
                        .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                result.cancel();
                            }
                        })
                        .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(android.content.DialogInterface d) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message, String defaultValue,
                                      final JsPromptResult result) {
                final EditText input = new EditText(MainActivity.this);
                input.setInputType(InputType.TYPE_CLASS_TEXT);
                input.setImeOptions(EditorInfo.IME_ACTION_DONE);
                input.setText(defaultValue == null ? "" : defaultValue);
                input.setSelection(input.getText().length());
                new AlertDialog.Builder(MainActivity.this)
                        .setMessage(message)
                        .setView(input)
                        .setPositiveButton("确定", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                result.confirm(input.getText().toString());
                            }
                        })
                        .setNegativeButton("取消", new android.content.DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(android.content.DialogInterface d, int w) {
                                result.cancel();
                            }
                        })
                        .setOnCancelListener(new android.content.DialogInterface.OnCancelListener() {
                            @Override
                            public void onCancel(android.content.DialogInterface d) {
                                result.cancel();
                            }
                        })
                        .show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) {
                    callback.onCustomViewHidden();
                    return;
                }
                customView = view;
                customViewCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(MATCH, MATCH));
                homeScroll.setVisibility(View.GONE);
                webView.setVisibility(View.GONE);
                if (floatBack != null) floatBack.setVisibility(View.GONE);
                goImmersive();
            }

            @Override
            public void onHideCustomView() {
                if (customView == null) return;
                root.removeView(customView);
                customView = null;
                if (customViewCallback != null) {
                    customViewCallback.onCustomViewHidden();
                    customViewCallback = null;
                }
                webView.setVisibility(View.VISIBLE);
                goImmersive();
                pokeBackPill();
            }
        });

        webView.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                toast("该页面触发了下载，当前版本未启用");
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(MATCH, MATCH));
        webView.setVisibility(View.GONE);
        // 保证加载提示与返回按钮都盖在网页之上
        if (loadingView != null) root.bringChildToFront(loadingView);
        root.bringChildToFront(floatBack);
    }

    private boolean handleUrl(String url) {
        if (url == null) return true;
        if (url.startsWith("http://127.0.0.1:" + PORT) || url.startsWith("http://localhost:" + PORT)) {
            return false;
        }
        // 这个壳不是通用浏览器：只放行 B 站（课件里的视频/登录），其余外链一律拦掉。
        if (url.startsWith("http://") || url.startsWith("https://")) {
            if (!isAllowedHost(url)) {
                Log.w("Kiosk", "blocked: " + url);
                // B 站登录过程中常会弹第三方页面（安全验证、下载 App 等）：
                // 在 B 站页面里静默拦掉就行，弹提示反而把扫码流程打断。
                if (!isBiliPage(currentUrl)) toast("已阻止打开外部地址");
                return true;
            }
            // 没登录就先弹登录页，登录后自动回到这里
            if (!isPassportUrl(url) && !isLoggedIn()) {
                pendingReturnUrl = url;
                webView.loadUrl("https://passport.bilibili.com/login?gourl=" + Uri.encode(url));
                return true;
            }
            return false;
        }
        if (url.startsWith("intent:") || url.startsWith("mailto:") || url.startsWith("tel:")) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            } catch (Exception e) {
                toast("无法打开该链接");
            }
            return true;
        }
        if (url.startsWith("file:") || url.startsWith("content:")) {
            toast("本地文件请放上书库后打开");
            return true;
        }
        return true;
    }

    /** 允许在 App 内打开的外站域名：只有 B 站相关的（含登录/验证码用到的生态域名）。 */
    private static final String[] ALLOWED_HOSTS = {
            "bilibili.com", "bilibili.cn", "bilibili.tv", "b23.tv",
            "bilivideo.com", "bilivideo.cn", "biliapi.net",
            "hdslb.com", "hdslb.net", "biligame.com", "biligame.cn",
            "biliimg.com", "bibibili.com", "bilibiliyoo.com",
            "geetest.com", "geetest.cn"
    };

    private boolean isAllowedHost(String url) {
        try {
            String host = Uri.parse(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            for (String s : ALLOWED_HOSTS) {
                if (host.equals(s) || host.endsWith("." + s)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 当前是不是停在 B 站自己的页面上（登录中/播放器页）。 */
    private boolean isBiliPage(String url) {
        return url != null && isAllowedHost(url);
    }

    /** B 站登录态就藏在 CookieManager 的 SESSDATA 里。 */
    private boolean isLoggedIn() {
        try {
            String c = CookieManager.getInstance().getCookie("https://www.bilibili.com");
            return c != null && c.contains("SESSDATA=");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isPassportUrl(String url) {
        try {
            String h = Uri.parse(url).getHost();
            return h != null && h.toLowerCase(Locale.ROOT).startsWith("passport.");
        } catch (Throwable t) {
            return false;
        }
    }

    private void gatePlayerLogin() {
        long now = System.currentTimeMillis();
        if (now - lastLoginGate < 15000) return;
        lastLoginGate = now;
        // 注意：这里可能运行在 WebView 的后台线程，不能碰 WebView 的方法，
        // 也不能调用会解析线程状态的东西；全部回主线程再做。
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (!readerOpen || webView == null) return;
                if (isLoggedIn()) return;
                String back = currentUrl;
                if (back == null || !back.startsWith("http://127.0.0.1:" + PORT + "/view/")) {
                    // 不在课件页就不拦，避免从 B 站页面再次触发时来回跳
                    return;
                }
                Log.i("Kiosk", "login gate -> " + back);
                pendingReturnUrl = back;
                openUrl("https://passport.bilibili.com/login");
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE) {
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        result[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(result);
                filePathCallback = null;
            }
        }
    }

    private class KioskWebView extends WebView {
        KioskWebView(android.content.Context context) {
            super(context);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() == MotionEvent.ACTION_DOWN) pokeBackPill();
            return super.onTouchEvent(event);
        }
    }

    // ------------------------------------------------------------------ server

    private void startServer() {
        String html = readAsset("upload.html");
        try {
            server = new HttpServer(libraryDir, PORT, html);
            server.setListener(new HttpServer.Listener() {
                @Override
                public void onFilesChanged() {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!readerOpen) refreshList();
                        }
                    });
                }
            });
            server.start();
            if (statusView != null) {
                statusView.setText("端口 " + PORT + " · 上传后书库自动刷新");
            }
        } catch (Exception e) {
            server = null;
            if (statusView != null) {
                statusView.setText("端口 " + PORT + " 启动失败：" + e.getMessage());
            }
        }
    }

    private String readAsset(String name) {
        try {
            InputStream is = getAssets().open(name);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) > 0) bo.write(buf, 0, r);
            is.close();
            return new String(bo.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return "<html><body style='font-family:sans-serif'>upload page missing</body></html>";
        }
    }

    // ------------------------------------------------------------------ utils

    private void goImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void collect(File dir, List<File> out, int depth) {
        if (depth > 8 || out.size() > 5000) return;
        File[] list = dir.listFiles();
        if (list == null) return;
        for (File f : list) {
            String n = f.getName();
            if (n.startsWith(".")) continue;
            if (f.isDirectory()) {
                collect(f, out, depth + 1);
            } else if (n.toLowerCase(Locale.ROOT).endsWith(".html")
                    || n.toLowerCase(Locale.ROOT).endsWith(".htm")) {
                out.add(f);
            }
        }
    }

    private String relPath(File f) {
        String base = libraryDir.getAbsolutePath();
        String abs = f.getAbsolutePath();
        if (abs.startsWith(base + File.separator)) {
            return abs.substring(base.length() + 1).replace(File.separatorChar, '/');
        }
        return f.getName();
    }

    private static String encodePath(String rel) {
        StringBuilder sb = new StringBuilder();
        String[] parts = rel.split("/");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            try {
                sb.append(java.net.URLEncoder.encode(parts[i], "UTF-8").replace("+", "%20"));
            } catch (Exception e) {
                sb.append(parts[i]);
            }
        }
        return sb.toString();
    }

    private String getLocalIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            String fallback = null;
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                List<InterfaceAddress> addrs = ni.getInterfaceAddresses();
                for (InterfaceAddress ia : addrs) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address) {
                        String ip = a.getHostAddress();
                        String name = ni.getName();
                        if (name != null && (name.startsWith("wlan") || name.startsWith("eth")
                                || name.startsWith("ap"))) {
                            return ip;
                        }
                        if (fallback == null) fallback = ip;
                    }
                }
            }
            return fallback != null ? fallback : "127.0.0.1";
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private String fmtSize(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KB", n / 1024.0);
        return String.format(Locale.CHINA, "%.1f MB", n / 1024.0 / 1024.0);
    }

    private String fmtTime(long t) {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date(t));
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String content, float spValue, int color, int style) {
        TextView v = new TextView(this);
        v.setText(content);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, spValue);
        v.setTextColor(color);
        v.setTypeface(Typeface.DEFAULT, style);
        v.setIncludeFontPadding(false);
        return v;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }
}