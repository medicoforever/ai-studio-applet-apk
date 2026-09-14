package com.medicoforever.aistudio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.content.ActivityNotFoundException;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import org.json.JSONTokener;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    public static final String TARGET_URL = "https://aistudio.google.com/apps/3f0807e3-2494-4289-a3a6-c12032da731c?fullscreenApplet=true";
    private static final int PERMISSION_REQ_CODE = 2001;
    private static final int FILE_CHOOSER_REQ_CODE = 3001;

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;
    private String chromeUserAgent;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        startKeepAliveService();
        checkAndRequestPermissions();
        requestBatteryOptimizationExemption();
        setupWebView();

        if (savedInstanceState == null) {
            webView.loadUrl(TARGET_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    private void startKeepAliveService() {
        Intent serviceIntent = new Intent(this, BackgroundKeepAliveService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void checkAndRequestPermissions() {
        List<String> permissions = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECORD_AUDIO);
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }
        if (!permissions.isEmpty()) {
            ActivityCompat.requestPermissions(this, permissions.toArray(new String[0]), PERMISSION_REQ_CODE);
        }
    }

    private void requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    @SuppressLint("BatteryLife")
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);

        WebSettings settings = webView.getSettings();
        configureCommonSettings(settings);

        // Bypass Google disallowed_useragent
        String originalUA = settings.getUserAgentString();
        chromeUserAgent = originalUA.replace("; wv", "").replaceAll("Version/[0-9.]+\\s*", "");
        if (!chromeUserAgent.contains("Chrome/")) {
            chromeUserAgent = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36";
        }
        settings.setUserAgentString(chromeUserAgent);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        // Download handling
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> handleDownload(url, userAgent, contentDisposition, mimetype));

        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        // WebViewClient
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request.getUrl() != null) {
                    String urlStr = request.getUrl().toString();
                    if (urlStr.contains("googleapis.com/drive/v3/files/") && urlStr.contains("alt=media")) {
                        WebResourceResponse response = handleDriveMediaDownload(request);
                        if (response != null) {
                            return response;
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Do not intercept subframes (iframes, Firebase auth iframes, scripts, APIs)
                if (!request.isForMainFrame()) {
                    return false;
                }
                return handleMainUrlLoading(view, request.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleMainUrlLoading(view, url);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request.isForMainFrame()) {
                    String url = request.getUrl().toString();
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        handleCustomScheme(url);
                    }
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                progressBar.setVisibility(View.VISIBLE);
                if (url != null && (url.contains("ai.studio") || url.contains("aistudio.google.com")) && !url.contains("accounts.google")) {
                    injectEarlyProtections(view);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                if (url != null && (url.contains("ai.studio") || url.contains("aistudio.google.com")) && !url.contains("accounts.google")) {
                    injectEarlyProtections(view);
                    injectUiCleaner(view);
                }
            }
        });

        // WebChromeClient
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                String currentUrl = view.getUrl();
                if (newProgress >= 40 && currentUrl != null && (currentUrl.contains("ai.studio") || currentUrl.contains("aistudio.google.com")) && !currentUrl.contains("accounts.google")) {
                    injectEarlyProtections(view);
                    injectUiCleaner(view);
                }
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                if (MainActivity.this.filePathCallback != null) {
                    MainActivity.this.filePathCallback.onReceiveValue(null);
                }
                MainActivity.this.filePathCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQ_CODE);
                } catch (Exception e) {
                    MainActivity.this.filePathCallback = null;
                    return false;
                }
                return true;
            }

            // CRITICAL: Handle Firebase Auth & Google Drive Popups inside the in-app dialog
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                return createPopupWindow(resultMsg);
            }
        });
    }

    private void configureCommonSettings(WebSettings settings) {
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
    }

    // Injects early error suppression and history protection to prevent AI Studio
    // from detecting errors and switching the view away from the Preview applet
    private void injectEarlyProtections(WebView view) {
        if (view == null) return;
        String currentUrl = view.getUrl();
        if (currentUrl == null || (!currentUrl.contains("ai.studio") && !currentUrl.contains("aistudio.google.com"))) {
            return;
        }
        if (currentUrl.contains("accounts.google") || currentUrl.contains("signin") || currentUrl.contains("oauth")) {
            return;
        }

        String js = "(function() {" +
            "try {" +
                "function isAiStudioHost() {" +
                    "var h = (window.location.hostname || '').toLowerCase();" +
                    "return h.indexOf('aistudio') !== -1 || h.indexOf('ai.studio') !== -1;" +
                "}" +
                "if (!isAiStudioHost()) return;" +
                "if (window.__raddocEarlyInjected) return;" +
                "window.__raddocEarlyInjected = true;" +

                // 1. Intercept and stop propagation of error & unhandledrejection in capturing phase
                "window.addEventListener('error', function(e) {" +
                    "e.stopImmediatePropagation();" +
                "}, true);" +
                "window.addEventListener('unhandledrejection', function(e) {" +
                    "e.stopImmediatePropagation();" +
                "}, true);" +

                // 2. Override window.onerror and onunhandledrejection
                "try {" +
                    "window.onerror = function() { return true; };" +
                    "window.onunhandledrejection = function() { return true; };" +
                "} catch(e) {}" +

                // 3. Intercept postMessage error dispatches that tell host AI Studio to switch to Chat
                "window.addEventListener('message', function(e) {" +
                    "try {" +
                        "if (!e || !e.data) return;" +
                        "var d = e.data;" +
                        "var isErr = false;" +
                        "if (typeof d === 'string') {" +
                            "var s = d.toLowerCase();" +
                            "if (s.indexOf('error') !== -1 || s.indexOf('switchtochat') !== -1 || s.indexOf('view_chat') !== -1) isErr = true;" +
                        "} else if (typeof d === 'object') {" +
                            "var t = String(d.type || d.action || d.event || d.kind || '').toLowerCase();" +
                            "if (t.indexOf('error') !== -1 || t.indexOf('reject') !== -1 || t.indexOf('fail') !== -1 || t.indexOf('crash') !== -1 || t.indexOf('chat') !== -1) isErr = true;" +
                            "if (d.isError === true || d.hasError === true) isErr = true;" +
                        "}" +
                        "if (isErr) {" +
                            "e.stopImmediatePropagation();" +
                            "e.stopPropagation();" +
                        "}" +
                    "} catch(ex) {}" +
                "}, true);" +

                // 4. Hook history.pushState and history.replaceState to never drop fullscreenApplet=true
                "try {" +
                    "var _ps = history.pushState;" +
                    "history.pushState = function(state, title, url) {" +
                        "if (url && typeof url === 'string' && url.indexOf('fullscreenApplet') === -1 && isAiStudioHost()) {" +
                            "var sep = url.indexOf('?') !== -1 ? '&' : '?';" +
                            "url = url + sep + 'fullscreenApplet=true';" +
                        "}" +
                        "return _ps.call(this, state, title, url);" +
                    "};" +
                    "var _rs = history.replaceState;" +
                    "history.replaceState = function(state, title, url) {" +
                        "if (url && typeof url === 'string' && url.indexOf('fullscreenApplet') === -1 && isAiStudioHost()) {" +
                            "var sep = url.indexOf('?') !== -1 ? '&' : '?';" +
                            "url = url + sep + 'fullscreenApplet=true';" +
                        "}" +
                        "return _rs.call(this, state, title, url);" +
                    "};" +
                "} catch(ex) {}" +
            "} catch(err) {}" +
        "})();";
        view.evaluateJavascript(js, null);
    }

    // Injects UI cleaner into host AI Studio page to remove bottom disclaimer banner,
    // enforce Preview tab visibility, and hide Chat UI & options menu
    private void injectUiCleaner(WebView view) {
        if (view == null) return;
        String currentUrl = view.getUrl();
        if (currentUrl == null || (!currentUrl.contains("ai.studio") && !currentUrl.contains("aistudio.google.com"))) {
            return;
        }
        if (currentUrl.contains("accounts.google") || currentUrl.contains("signin") || currentUrl.contains("oauth")) {
            return;
        }

        String js = "(function() {" +
            "function isAiStudioHost() {" +
                "var h = (window.location.hostname || '').toLowerCase();" +
                "return h.indexOf('aistudio') !== -1 || h.indexOf('ai.studio') !== -1;" +
            "}" +
            "if (!isAiStudioHost()) return;" +

            "if (window.__raddocCleanInjected) {" +
                "if (typeof window.__raddocClean === 'function') window.__raddocClean();" +
                "return;" +
            "}" +
            "window.__raddocCleanInjected = true;" +

            "window.__raddocClean = function() {" +
                "try {" +
                    "if (!isAiStudioHost()) return;" +

                    // 1. Hide 'This app was developed by another user' disclaimer banner
                    "var allDivs = document.querySelectorAll('div, footer, p, span, aside, section');" +
                    "for (var j = 0; j < allDivs.length; j++) {" +
                        "var el = allDivs[j];" +
                        "var txt = el.textContent || '';" +
                        "if (txt.indexOf('This app was developed by another user') !== -1) {" +
                            "var p = el;" +
                            "while (p.parentElement && p.parentElement !== document.body && p.parentElement.offsetHeight < 160) { p = p.parentElement; }" +
                            "p.style.setProperty('display', 'none', 'important');" +
                            "p.style.setProperty('visibility', 'hidden', 'important');" +
                            "p.style.setProperty('height', '0px', 'important');" +
                        "}" +
                    "}" +

                    // 2. DETECT CHAT VIEW & ENFORCE PREVIEW
                    "var bodyText = document.body ? (document.body.innerText || '') : '';" +
                    "var isChatShowing = (bodyText.indexOf('Remix to make this app your own') !== -1 || " +
                                         "bodyText.indexOf('Here are some ideas to try') !== -1 || " +
                                         "bodyText.indexOf('Generate video from text') !== -1);" +

                    // Find Preview and Chat buttons across all elements
                    "var allElements = document.querySelectorAll('button, [role=\"tab\"], [role=\"button\"], a, div, span');" +
                    "var previewBtn = null;" +
                    "var chatBtn = null;" +
                    "for (var t = 0; t < allElements.length; t++) {" +
                        "var elem = allElements[t];" +
                        "var etxt = (elem.textContent || '').trim();" +
                        "var earia = elem.getAttribute('aria-label') || '';" +
                        "if (etxt === 'Preview' || earia === 'Preview') {" +
                            "previewBtn = elem.closest('button, [role=\"tab\"], [role=\"button\"], a') || elem;" +
                        "} else if (etxt === 'Chat' || earia === 'Chat') {" +
                            "chatBtn = elem.closest('button, [role=\"tab\"], [role=\"button\"], a') || elem;" +
                        "}" +
                    "}" +

                    "var isChatSelected = isChatShowing;" +
                    "if (chatBtn) {" +
                        "if (chatBtn.getAttribute('aria-selected') === 'true' || " +
                            "chatBtn.classList.contains('active') || " +
                            "chatBtn.classList.contains('selected') || " +
                            "chatBtn.classList.contains('mdc-tab--active')) {" +
                            "isChatSelected = true;" +
                        "}" +
                    "}" +
                    "var isPreviewSelected = false;" +
                    "if (previewBtn) {" +
                        "if (previewBtn.getAttribute('aria-selected') === 'true' || " +
                            "previewBtn.classList.contains('active') || " +
                            "previewBtn.classList.contains('selected') || " +
                            "previewBtn.classList.contains('mdc-tab--active')) {" +
                            "isPreviewSelected = true;" +
                        "}" +
                    "}" +

                    // If Chat is selected or showing, or Preview is not selected: CLICK PREVIEW!
                    "if (previewBtn && (isChatSelected || !isPreviewSelected)) {" +
                        "previewBtn.click();" +
                        "try {" +
                            "previewBtn.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));" +
                        "} catch(e) {}" +
                    "}" +

                    // If Chat view STILL persists after clicking Preview, trigger native fallback reload!
                    "if (isChatShowing) {" +
                        "if (!window.__raddocChatSince) { window.__raddocChatSince = Date.now(); }" +
                        "else if (Date.now() - window.__raddocChatSince > 800) {" +
                            "window.__raddocChatSince = Date.now();" +
                            "if (window.AndroidBridge && typeof window.AndroidBridge.forceRestorePreview === 'function') {" +
                                "window.AndroidBridge.forceRestorePreview();" +
                            "} else {" +
                                "window.location.replace('https://aistudio.google.com/apps/3f0807e3-2494-4289-a3a6-c12032da731c?fullscreenApplet=true');" +
                            "}" +
                        "}" +
                    "} else {" +
                        "window.__raddocChatSince = 0;" +
                    "}" +

                    // 3. Move the navigation bar offscreen so the user doesn't see Chat/Preview bottom bar,
                    // but programmatic .click() stays 100% active
                    "var navs = document.querySelectorAll('nav, div, footer, [role=\"tablist\"], [role=\"navigation\"]');" +
                    "for (var k = 0; k < navs.length; k++) {" +
                        "var n = navs[k];" +
                        "var ntxt = n.textContent || '';" +
                        "if (ntxt.indexOf('Chat') !== -1 && ntxt.indexOf('Preview') !== -1) {" +
                            "n.style.setProperty('position', 'fixed', 'important');" +
                            "n.style.setProperty('top', '-9999px', 'important');" +
                            "n.style.setProperty('left', '-9999px', 'important');" +
                            "n.style.setProperty('opacity', '0', 'important');" +
                            "n.style.setProperty('pointer-events', 'none', 'important');" +
                            "n.style.setProperty('width', '1px', 'important');" +
                            "n.style.setProperty('height', '1px', 'important');" +
                            "n.style.setProperty('overflow', 'hidden', 'important');" +
                        "}" +
                    "}" +

                    // 4. Hide '...' more options button bar
                    "var btns = document.querySelectorAll('button, [role=\"button\"]');" +
                    "for (var b = 0; b < btns.length; b++) {" +
                        "var btn = btns[b];" +
                        "var btxt = (btn.textContent || '').trim();" +
                        "var aria = btn.getAttribute('aria-label') || '';" +
                        "if (btxt === '...' || aria === 'More' || aria === 'More options') {" +
                            "var bar = btn.closest('nav, [role=\"tablist\"], div');" +
                            "if (bar && bar !== document.body && bar.offsetHeight < 80) {" +
                                "bar.style.setProperty('position', 'fixed', 'important');" +
                                "bar.style.setProperty('top', '-9999px', 'important');" +
                                "bar.style.setProperty('left', '-9999px', 'important');" +
                                "bar.style.setProperty('opacity', '0', 'important');" +
                                "bar.style.setProperty('pointer-events', 'none', 'important');" +
                            "}" +
                        "}" +
                    "}" +

                    // 5. Hide Chat editor panels if they ever render
                    "var chatPanels = document.querySelectorAll('[class*=\"chat-container\"], [class*=\"chat_container\"], [class*=\"prompt-editor\"], [class*=\"conversation-view\"]');" +
                    "for (var c = 0; c < chatPanels.length; c++) {" +
                        "chatPanels[c].style.setProperty('display', 'none', 'important');" +
                    "}" +

                    // 6. Keep URL parameter fullscreenApplet=true
                    "if (window.location.pathname.indexOf('3f0807e3-2494-4289-a3a6-c12032da731c') !== -1 && window.location.search.indexOf('fullscreenApplet=true') === -1) {" +
                        "try {" +
                            "var u = new URL(window.location.href);" +
                            "u.searchParams.set('fullscreenApplet', 'true');" +
                            "window.history.replaceState(null, '', u.toString());" +
                        "} catch(e) {}" +
                    "}" +
                "} catch(e) {}" +
            "};" +

            "window.__raddocClean();" +
            "if (!window.__raddocInterval) {" +
                "window.__raddocInterval = setInterval(window.__raddocClean, 100);" +
            "}" +
            "if (!window.__raddocObserver && document.body) {" +
                "window.__raddocObserver = new MutationObserver(window.__raddocClean);" +
                "window.__raddocObserver.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'aria-selected'] });" +
            "}" +
            "document.addEventListener('DOMContentLoaded', window.__raddocClean);" +
        "})();";
        view.evaluateJavascript(js, null);
    }

    // Native JavaScript Interface accessible from web context as window.AndroidBridge
    public class WebAppInterface {
        @JavascriptInterface
        public void openHtmlInChrome(String htmlContent, String filename) {
            runOnUiThread(() -> exportAndOpenInChrome(htmlContent, filename));
        }

        @JavascriptInterface
        public void forceRestorePreview() {
            runOnUiThread(() -> {
                if (webView != null) {
                    webView.loadUrl(TARGET_URL);
                }
            });
        }
    }

    public void exportAndOpenInChrome(String htmlContent, String filename) {
        if (htmlContent == null || htmlContent.trim().isEmpty()) {
            Toast.makeText(this, "Empty report content", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String safeName = (filename == null || filename.trim().isEmpty()) ? "Dictation_Report.html" : filename.trim();
            if (!safeName.toLowerCase().endsWith(".html")) {
                safeName += ".html";
            }
            File reportsDir = new File(getCacheDir(), "reports");
            if (!reportsDir.exists()) {
                reportsDir.mkdirs();
            }
            File file = new File(reportsDir, safeName);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(htmlContent.getBytes(StandardCharsets.UTF_8));
            fos.flush();
            fos.close();

            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "text/html");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // Open in Google Chrome if available
            intent.setPackage("com.android.chrome");
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException e) {
                intent.setPackage(null);
                startActivity(Intent.createChooser(intent, "Open report with..."));
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error opening report: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String parseJsonString(String json) {
        if (json == null || json.equals("null")) return "";
        try {
            Object obj = new JSONTokener(json).nextValue();
            return obj != null ? obj.toString() : "";
        } catch (Exception e) {
            if (json.startsWith("\"") && json.endsWith("\"") && json.length() >= 2) {
                return json.substring(1, json.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\r", "\r")
                        .replace("\\t", "\t")
                        .replace("\\\\", "\\");
            }
            return json;
        }
    }

    // Intercept Google Drive media download requests to bypass CORS redirects natively
    private WebResourceResponse handleDriveMediaDownload(WebResourceRequest request) {
        String method = request.getMethod();
        if ("OPTIONS".equalsIgnoreCase(method)) {
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
            headers.put("Access-Control-Allow-Headers", "Authorization, Content-Type, Accept, Origin, X-Requested-With");
            headers.put("Access-Control-Max-Age", "86400");
            return new WebResourceResponse("text/plain", "UTF-8", 200, "OK", headers, new ByteArrayInputStream(new byte[0]));
        }

        try {
            String targetUrl = request.getUrl().toString();
            URL u = new URL(targetUrl);
            HttpURLConnection conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(30000);
            conn.setInstanceFollowRedirects(true);

            Map<String, String> reqHeaders = request.getRequestHeaders();
            if (reqHeaders != null) {
                for (Map.Entry<String, String> entry : reqHeaders.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            int code = conn.getResponseCode();
            int redirectCount = 0;
            while ((code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307) && redirectCount < 5) {
                String location = conn.getHeaderField("Location");
                if (location == null) break;
                conn.disconnect();
                u = new URL(location);
                conn = (HttpURLConnection) u.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(true);
                code = conn.getResponseCode();
                redirectCount++;
            }

            if (code >= 200 && code < 400) {
                InputStream in = conn.getInputStream();
                Map<String, String> resHeaders = new HashMap<>();
                resHeaders.put("Access-Control-Allow-Origin", "*");
                resHeaders.put("Access-Control-Allow-Methods", "GET, HEAD, OPTIONS");
                resHeaders.put("Access-Control-Allow-Headers", "*");
                resHeaders.put("Content-Type", "text/html; charset=UTF-8");
                return new WebResourceResponse("text/html", "UTF-8", 200, "OK", resHeaders, in);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    // In-app Popup Dialog for Firebase Auth, Google Drive OAuth & Report Preview
    @SuppressLint("SetJavaScriptEnabled")
    private boolean createPopupWindow(Message resultMsg) {
        final Dialog dialog = new Dialog(MainActivity.this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);

        LinearLayout layout = new LinearLayout(MainActivity.this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackgroundColor(Color.parseColor("#121212"));

        // Header bar
        LinearLayout header = new LinearLayout(MainActivity.this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setBackgroundColor(Color.parseColor("#1E1F20"));
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics());
        header.setPadding(pad, pad, pad, pad);
        header.setGravity(Gravity.CENTER_VERTICAL);

        final TextView title = new TextView(MainActivity.this);
        title.setText("Authorization / Report");
        title.setTextColor(Color.WHITE);
        title.setTextSize(15);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        header.addView(title, titleParams);

        final Button openInChromeBtn = new Button(MainActivity.this);
        openInChromeBtn.setText("🌐 Open in Chrome");
        openInChromeBtn.setTextColor(Color.parseColor("#38BDF8"));
        openInChromeBtn.setBackgroundColor(Color.TRANSPARENT);
        openInChromeBtn.setVisibility(View.GONE);
        header.addView(openInChromeBtn);

        Button closeBtn = new Button(MainActivity.this);
        closeBtn.setText("✕ Close");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setBackgroundColor(Color.TRANSPARENT);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        header.addView(closeBtn);

        layout.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final WebView popupWebView = new WebView(MainActivity.this);
        WebSettings pSettings = popupWebView.getSettings();
        configureCommonSettings(pSettings);
        pSettings.setUserAgentString(chromeUserAgent);
        pSettings.setSupportZoom(true);
        pSettings.setBuiltInZoomControls(true);
        pSettings.setDisplayZoomControls(false);

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(popupWebView, true);

        popupWebView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        openInChromeBtn.setOnClickListener(v -> {
            popupWebView.evaluateJavascript("(function(){ return document.documentElement.outerHTML; })();", value -> {
                String html = parseJsonString(value);
                if (html != null && !html.isEmpty()) {
                    exportAndOpenInChrome(html, "Dictation_Report.html");
                } else {
                    Toast.makeText(MainActivity.this, "Could not extract report HTML", Toast.LENGTH_SHORT).show();
                }
            });
        });

        popupWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                try {
                    dialog.dismiss();
                    window.destroy();
                } catch (Exception ignored) {}
            }
        });

        popupWebView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request.getUrl() != null) {
                    String urlStr = request.getUrl().toString();
                    if (urlStr.contains("googleapis.com/drive/v3/files/") && urlStr.contains("alt=media")) {
                        WebResourceResponse response = handleDriveMediaDownload(request);
                        if (response != null) {
                            return response;
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                if (!req.isForMainFrame()) {
                    return false;
                }
                return handlePopupUrl(dialog, req.getUrl().toString(), title, openInChromeBtn);
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                return handlePopupUrl(dialog, url, title, openInChromeBtn);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                CookieManager.getInstance().flush();
                if (url != null && (url.startsWith("blob:") || url.contains(".html") || url.contains("dictation"))) {
                    title.setText("Dictation Report");
                    openInChromeBtn.setVisibility(View.VISIBLE);
                } else if (url != null && (url.contains("accounts.google") || url.contains("signin") || url.contains("oauth") || url.contains("firebaseapp"))) {
                    title.setText("Google Sign-In");
                    openInChromeBtn.setVisibility(View.GONE);
                }
                // When OAuth reaches approval, close_window, or success callback, auto-dismiss
                if (url != null && (url.contains("oauth2/approval") || url.contains("close_window") || url.contains("success"))) {
                    v.postDelayed(() -> {
                        try {
                            dialog.dismiss();
                            v.destroy();
                        } catch (Exception ignored) {}
                    }, 800);
                }
            }
        });

        layout.addView(popupWebView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        dialog.setContentView(layout);
        dialog.setOnDismissListener(d -> {
            try {
                popupWebView.destroy();
            } catch (Exception ignored) {}
        });

        dialog.show();

        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(popupWebView);
        resultMsg.sendToTarget();
        return true;
    }

    private boolean handlePopupUrl(Dialog dialog, String url, TextView titleView, Button chromeBtn) {
        if (url == null || url.isEmpty() || url.equals("about:blank")) {
            return false;
        }

        // 1. Report / Blob URLs: render directly in popupWebView!
        if (url.startsWith("blob:") || url.startsWith("data:text/html")) {
            if (titleView != null) titleView.setText("Dictation Report");
            if (chromeBtn != null) chromeBtn.setVisibility(View.VISIBLE);
            return false;
        }

        // 2. Custom schemes (intent:, tg:, mailto:, etc.)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            handleCustomScheme(url);
            dialog.dismiss();
            return true;
        }

        // 3. External Telegram link
        if (url.contains("t.me") || url.contains("telegram.me")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                dialog.dismiss();
                return true;
            } catch (Exception ignored) {}
        }

        // 4. Update title based on URL
        if (titleView != null) {
            if (url.contains("accounts.google") || url.contains("signin") || url.contains("oauth") || url.contains("firebaseapp")) {
                titleView.setText("Google Sign-In");
                if (chromeBtn != null) chromeBtn.setVisibility(View.GONE);
            } else if (url.contains(".html") || url.contains("dictation")) {
                titleView.setText("Dictation Report");
                if (chromeBtn != null) chromeBtn.setVisibility(View.VISIBLE);
            }
        }

        // ALL auth, Firebase handler, and Google domains MUST stay inside the popup!
        return false;
    }

    private boolean handleMainUrlLoading(WebView view, String url) {
        if (url == null || url.isEmpty() || url.equals("about:blank")) {
            return false;
        }

        Uri uri = Uri.parse(url);
        String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
        String path = uri.getPath() != null ? uri.getPath() : "";

        // 1. Strict editor protection (lock to fullscreen applet on ai.studio)
        if (host.equals("ai.studio") || host.equals("aistudio.google.com")) {
            if (!path.contains("3f0807e3-2494-4289-a3a6-c12032da731c")) {
                view.loadUrl(TARGET_URL);
                return true;
            }
            String query = uri.getQuery();
            if (query == null || !query.contains("fullscreenApplet=true")) {
                view.loadUrl(TARGET_URL);
                return true;
            }
        }

        // 2. Custom schemes (intent:, tg:, mailto:, etc.)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            handleCustomScheme(url);
            return true;
        }

        // 3. Keep internal URLs, Firebase Auth, Google Auth, and Drive Picker inside WebView
        if (isInternalUrl(url)) {
            return false;
        }

        // 4. External Telegram link
        if (url.contains("t.me") || url.contains("telegram.me")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true;
            } catch (Exception ignored) {}
        }

        // 5. External Drive folder link (e.g. drive.google.com/drive/folders/...)
        if (url.contains("drive.google.com") && !url.contains("picker")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        // Default: stay inside WebView
        return false;
    }

    private boolean isInternalUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("ai.studio") ||
               lower.contains("aistudio.google.com") ||
               lower.contains("firebaseapp.com") ||
               lower.contains("web.app") ||
               lower.contains("firebase") ||
               lower.contains("identitytoolkit") ||
               lower.contains("accounts.google.com") ||
               lower.contains("accounts.youtube.com") ||
               lower.contains("myaccount.google.com") ||
               lower.contains("apis.google.com") ||
               lower.contains("gstatic.com") ||
               lower.contains("googleusercontent.com") ||
               lower.contains("usercontent.goog") ||
               lower.contains("picker") ||
               lower.contains("oauth") ||
               lower.contains("/signin") ||
               lower.contains("servicelogin");
    }

    private void handleCustomScheme(String url) {
        try {
            if (url.startsWith("intent:")) {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                if (intent != null) {
                    PackageManager pm = getPackageManager();
                    if (intent.resolveActivity(pm) != null) {
                        startActivity(intent);
                        return;
                    }
                    String fallbackUrl = intent.getStringExtra("browser_fallback_url");
                    if (fallbackUrl != null && !fallbackUrl.isEmpty()) {
                        Intent fbIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl));
                        startActivity(fbIntent);
                        return;
                    }
                }
            } else {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleDownload(String url, String userAgent, String contentDisposition, String mimetype) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
                request.setMimeType(mimetype);
                String cookies = CookieManager.getInstance().getCookie(url);
                request.addRequestHeader("cookie", cookies);
                request.addRequestHeader("User-Agent", userAgent);
                request.setDescription("Downloading file...");
                String filename = URLUtil.guessFileName(url, contentDisposition, mimetype);
                request.setTitle(filename);
                request.allowScanningByMediaScanner();
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
                DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (dm != null) {
                    dm.enqueue(request);
                    Toast.makeText(getApplicationContext(), "Downloading " + filename, Toast.LENGTH_SHORT).show();
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == FILE_CHOOSER_REQ_CODE) {
            if (filePathCallback != null) {
                Uri[] results = null;
                if (resultCode == RESULT_OK && data != null) {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    } else if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    public void onBackPressed() {
        String currentUrl = webView.getUrl();
        if (currentUrl != null && (currentUrl.contains("ai.studio") || currentUrl.contains("aistudio.google.com"))) {
            if (!currentUrl.contains("fullscreenApplet=true") || !currentUrl.contains("3f0807e3-2494-4289-a3a6-c12032da731c")) {
                webView.loadUrl(TARGET_URL);
                return;
            }
        }
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }
}
