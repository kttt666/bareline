package com.littlesauce.pocket;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 主界面。
 *
 * 首页只有一个输入框 + 扫码按钮；进入网页后界面完全让给内容，
 * 左下角留一个小半透明按钮，点开才浮出「后退 / 地址 / 刷新 / 最近打开」，
 * 不占用任何常驻布局高度。
 */
public class MainActivity extends Activity {

    private static final int REQ_SCAN = 1001;

    private WebView web;
    private View homeLayer;
    private EditText urlInput;
    private EditText barInput;
    private View panel;
    private View scrim;
    private View barRow;
    private LinearLayout linkList;
    private View fabKey;
    private TextView vaultCount;

    private Store store;
    private Vault vault;
    private VaultSheet vaultSheet;
    private boolean panelOpen = false;
    private boolean browsing = false;
    /** 当前页面是否出现过密码框，决定钥匙按钮是否显示。 */
    private boolean pageHasPassword = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 窗口属性必须在 setContentView 之前，否则首帧会先按「留出状态栏」排一遍版
        setupWindow();

        setContentView(R.layout.activity_main);

        // 收栏必须在 setContentView 之后，DecorView 这时才存在
        hideBars();

        store = new Store(this);
        vault = new Vault(this);

        web = findViewById(R.id.web);
        homeLayer = findViewById(R.id.homeLayer);
        urlInput = findViewById(R.id.urlInput);
        barInput = findViewById(R.id.barInput);
        panel = findViewById(R.id.panel);
        scrim = findViewById(R.id.scrim);
        barRow = findViewById(R.id.barRow);
        linkList = findViewById(R.id.linkList);
        fabKey = findViewById(R.id.fabKey);
        vaultCount = findViewById(R.id.vaultCount);

        vaultSheet = new VaultSheet(
                this, findViewById(R.id.vaultHost), vault, this::fillPassword);
        vaultSheet.setOnChanged(this::refreshVaultCount);

        setupWeb();
        setupInputs();
        setupButtons();

        refreshList();
        refreshVaultCount();
        handleIntent(getIntent());
    }

    // ---------------------------------------------------------------- 沉浸式

    /**
     * 窗口属性，必须在 setContentView 之前设。
     *
     * 两件事：
     * 1. 栏透明，滑出来时是浮在内容上的，不会顶下去一块
     * 2. setDecorFitsSystemWindows(false) —— 不加这行，窗口仍然给状态栏/导航栏
     *    留出那块高度，栏藏起来了位置还空着，就是那条「和状态栏一样高」的黑边
     *
     * 注意这里不能碰 getInsetsController()，DecorView 还没建，会 NPE。
     */
    private void setupWindow() {
        Window w = getWindow();
        w.setStatusBarColor(0x00000000);
        w.setNavigationBarColor(0x00000000);

        // 刘海/挖孔屏：不声明这个，系统会为了避开刘海把整块可用区域往下压
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= 30
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }

        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
        }
    }

    /**
     * 把栏收起来。必须在 setContentView 之后调 —— DecorView 这时才存在。
     *
     * 用 BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE 而不是普通 IMMERSIVE：
     * 后者是「滑出来会一直留着」，前者才是滑出来看完自动收起。
     */
    private void hideBars() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.getDecorView(); // 确保 DecorView 已创建
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            // 老系统上 LAYOUT_FULLSCREEN / LAYOUT_HIDE_NAVIGATION 就是干这事的
            w.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    /**
     * 从别的界面回来时系统会把栏恢复出来，重新收一遍。
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) return;
        hideBars();
    }

    // ---------------------------------------------------------------- WebView

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);

        web.setBackgroundColor(0xFFF6F7F9);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            cm.setAcceptThirdPartyCookies(web, true);
        }

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return handleUrl(req.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleUrl(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!barInput.hasFocus()) {
                    barInput.setText(url);
                    barInput.setSelection(barInput.getText().length());
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && !url.startsWith("about:")) {
                    store.add(url, view.getTitle());
                    refreshList();
                }
                if (!barInput.hasFocus()) {
                    barInput.setText(url);
                    barInput.setSelection(barInput.getText().length());
                }
                // 页面加载完探一次有没有密码框，决定要不要显示钥匙按钮
                detectPasswordField();
            }
        });

        web.setWebChromeClient(new WebChromeClient());
    }

    /** 返回 true 表示已交给外部 App 处理。 */
    private boolean handleUrl(Uri uri) {
        if (uri == null) return false;
        String scheme = uri.getScheme();
        if (scheme == null) return false;
        if (scheme.equals("http") || scheme.equals("https")) return false;

        try {
            Intent i = new Intent(Intent.ACTION_VIEW, uri);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
            Toast.makeText(this, "没有能打开 " + scheme + " 的应用", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    // ---------------------------------------------------------------- 输入

    private void setupInputs() {
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                openFromInput(urlInput);
                return true;
            }
            return false;
        });

        barInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                openFromInput(barInput);
                return true;
            }
            return false;
        });
    }

    private void openFromInput(EditText input) {
        String url = UrlFix.normalize(input.getText().toString());
        if (url == null) {
            Toast.makeText(this, "看起来不是网址", Toast.LENGTH_SHORT).show();
            return;
        }
        hideKeyboard(input);
        input.clearFocus();
        open(url);
    }

    private void open(String url) {
        browsing = true;
        homeLayer.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        barRow.setVisibility(View.VISIBLE);
        closePanel();
        setPasswordFieldVisible(false);
        web.loadUrl(url);
    }

    private void hideKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
    }

    // ---------------------------------------------------------------- 浮出面板

    private void togglePanel() {
        if (panelOpen) closePanel();
        else openPanel();
    }

    private void openPanel() {
        if (panelOpen) return;
        panelOpen = true;
        refreshList();
        panel.setVisibility(View.VISIBLE);
        scrim.setVisibility(View.VISIBLE);

        panel.setAlpha(0f);
        panel.setTranslationY(dp(12));
        panel.animate().alpha(1f).translationY(0f).setDuration(150).start();
    }

    private void closePanel() {
        if (!panelOpen) return;
        panelOpen = false;
        panel.animate()
                .alpha(0f)
                .translationY(dp(12))
                .setDuration(120)
                .withEndAction(() -> {
                    panel.setVisibility(View.GONE);
                    scrim.setVisibility(View.GONE);
                })
                .start();
    }

    // ---------------------------------------------------------------- 按钮

    private void setupButtons() {
        findViewById(R.id.scanBtn).setOnClickListener(v -> startScan());
        findViewById(R.id.btnScan2).setOnClickListener(v -> startScan());
        findViewById(R.id.fab).setOnClickListener(v -> togglePanel());
        scrim.setOnClickListener(v -> closePanel());

        findViewById(R.id.btnBack).setOnClickListener(v -> {
            if (web.canGoBack()) web.goBack();
            else goHome();
        });

        findViewById(R.id.btnReload).setOnClickListener(v -> web.reload());

        findViewById(R.id.btnClear).setOnClickListener(v -> {
            store.clear();
            refreshList();
            Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
        });

        // 密码库管理
        findViewById(R.id.btnVault).setOnClickListener(v -> {
            closePanel();
            vaultSheet.show(true);
        });

        // 钥匙按钮：页面上有密码框时才出现，点一下弹出可填的密码列表
        fabKey.setOnClickListener(v -> {
            if (vault.isEmpty()) {
                Toast.makeText(this, "密码库是空的，先到 ☰ 里添加", Toast.LENGTH_SHORT).show();
                closePanel();
                vaultSheet.show(true);
                return;
            }
            vaultSheet.show(false);
        });
    }

    private void startScan() {
        startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_SCAN || res != RESULT_OK || data == null) return;
        String text = data.getStringExtra(ScanActivity.EXTRA_RESULT);
        if (text == null) return;
        String url = UrlFix.normalize(text);
        if (url == null) {
            Toast.makeText(this, "二维码内容不是网址", Toast.LENGTH_LONG).show();
            return;
        }
        open(url);
    }

    private void goHome() {
        browsing = false;
        web.loadUrl("about:blank");
        web.setVisibility(View.GONE);
        homeLayer.setVisibility(View.VISIBLE);
        barRow.setVisibility(View.GONE);
        urlInput.setText("");
        setPasswordFieldVisible(false);
        closePanel();
    }

    // ---------------------------------------------------------------- 密码库

    /** 探一下当前页面有没有密码框；有才显示钥匙按钮。 */
    private void detectPasswordField() {
        if (!browsing) {
            setPasswordFieldVisible(false);
            return;
        }
        try {
            web.evaluateJavascript(VaultSheet.checkFieldJs(), value -> {
                boolean has = value != null && value.contains("1");
                setPasswordFieldVisible(has);
            });
        } catch (Exception ignored) {
            setPasswordFieldVisible(false);
        }
    }

    private void setPasswordFieldVisible(boolean visible) {
        pageHasPassword = visible;
        boolean show = visible && browsing && !vault.isEmpty();
        fabKey.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** 把某条密码填进当前页面。 */
    private void fillPassword(Vault.Item item) {
        if (item == null) return;
        String js = VaultSheet.buildFillJs(item.secret, item.user);
        web.evaluateJavascript(js, value -> {
            String r = value == null ? "" : value.replace("\"", "");
            if (r.equals("nofield")) {
                Toast.makeText(this, "这个页面没找到密码框", Toast.LENGTH_SHORT).show();
            } else if (r.equals("submitted")) {
                Toast.makeText(this, "已填入并提交", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "已填入", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void refreshVaultCount() {
        int n = vault.list().size();
        if (vaultCount != null) {
            vaultCount.setText(n == 0 ? "空" : n + " 条");
        }
        if (pageHasPassword) setPasswordFieldVisible(true);
    }

    // ---------------------------------------------------------------- 最近列表

    private void refreshList() {
        if (linkList == null) return;
        List<Store.Link> links = store.list();
        linkList.removeAllViews();

        if (links.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有记录");
            empty.setTextColor(0xFF8C9099);
            empty.setTextSize(12);
            empty.setPadding(dp(10), dp(12), dp(10), dp(14));
            linkList.addView(empty);
            return;
        }

        int n = Math.min(links.size(), 30);
        for (int i = 0; i < n; i++) {
            linkList.addView(buildRow(links.get(i)));
        }
    }

    private View buildRow(final Store.Link link) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(10), dp(2), dp(10));
        row.setBackgroundResource(selectableBg());

        TextView tv = new TextView(this);
        tv.setText(UrlFix.shortLabel(link.url));
        tv.setTextColor(0xFF14161A);
        tv.setTextSize(13);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tv);

        TextView del = new TextView(this);
        del.setText("×");
        del.setTextColor(0xFFB0B4BC);
        del.setTextSize(17);
        del.setPadding(dp(10), dp(2), dp(10), dp(2));
        row.addView(del);

        row.setOnClickListener(v -> open(link.url));
        del.setOnClickListener(v -> {
            store.remove(link.url);
            refreshList();
        });
        return row;
    }

    private int selectableBg() {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void onBackPressed() {
        if (vaultSheet.isShowing()) {
            vaultSheet.hide();
            return;
        }
        if (panelOpen) {
            closePanel();
            return;
        }
        if (browsing) {
            if (web.canGoBack()) web.goBack();
            else goHome();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri data = intent.getData();
            if (data != null && data.toString().startsWith("http")) {
                open(data.toString());
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
        }
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }
}
