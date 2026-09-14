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

    public static final String TARGET_URL = "https://ai.studio/apps/3f0807e3-2494-4289-a3a6-c12032da731c?fullscreenApplet=true";
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
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                injectUiCleaner(view);
            }
        });

        // WebChromeClient
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress >= 50) {
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

    // Injects UI cleaner into host AI Studio page to remove bottom disclaimer banner,
    // remove Chat / Preview tabs & options menu, and make the applet iframe occupy 100% full screen
    private void injectUiCleaner(WebView view) {
        if (view == null) return;
        String js = "(function() {" +
            "if (window.__raddocCleanInjected) {" +
                "if (typeof window.__raddocClean === 'function') window.__raddocClean();" +
                "return;" +
            "}" +
            "window.__raddocCleanInjected = true;" +
            "window.__raddocClean = function() {" +
                "try {" +
                    "var iframes = document.querySelectorAll('iframe');" +
                    "for (var i = 0; i < iframes.length; i++) {" +
                        "var ifr = iframes[i];" +
                        "if (ifr.offsetWidth > 60 || ifr.offsetHeight > 60 || ifr.getAttribute('sandbox') || (ifr.src && ifr.src.indexOf('usercontent') !== -1)) {" +
                            "ifr.style.setProperty('position', 'fixed', 'important');" +
                            "ifr.style.setProperty('top', '0px', 'important');" +
                            "ifr.style.setProperty('left', '0px', 'important');" +
                            "ifr.style.setProperty('width', '100vw', 'important');" +
                            "ifr.style.setProperty('height', '100vh', 'important');" +
                            "ifr.style.setProperty('max-height', '100vh', 'important');" +
                            "ifr.style.setProperty('z-index', '99999', 'important');" +
                            "ifr.style.setProperty('border', 'none', 'important');" +
                        "}" +
                    "}" +
                    "var all = document.querySelectorAll('div, footer, p, span, aside, section');" +
                    "for (var j = 0; j < all.length; j++) {" +
                        "var el = all[j];" +
                        "var txt = el.textContent || '';" +
                        "if (txt.indexOf('This app was developed by another user') !== -1) {" +
                            "var p = el;" +
                            "while (p.parentElement && p.parentElement !== document.body && p.parentElement.offsetHeight < 160) { p = p.parentElement; }" +
                            "p.style.setProperty('display', 'none', 'important');" +
                            "p.style.setProperty('visibility', 'hidden', 'important');" +
                            "p.style.setProperty('height', '0px', 'important');" +
                        "}" +
                    "}" +
                    "var navs = document.querySelectorAll('nav, div, footer, [role=\"tablist\"], [role=\"navigation\"]');" +
                    "for (var k = 0; k < navs.length; k++) {" +
                        "var n = navs[k];" +
                        "var ntxt = n.textContent || '';" +
                        "if (ntxt.indexOf('Chat') !== -1 && ntxt.indexOf('Preview') !== -1) {" +
                            "n.style.setProperty('display', 'none', 'important');" +
                            "n.style.setProperty('visibility', 'hidden', 'important');" +
                            "n.style.setProperty('height', '0px', 'important');" +
                        "}" +
                    "}" +
                "} catch(e) {}" +
            "};" +
            "window.__raddocClean();" +
            "setInterval(window.__raddocClean, 500);" +
            "var obs = new MutationObserver(window.__raddocClean);" +
            "if (document.body) { obs.observe(document.body, { childList: true, subtree: true }); }" +
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

        // 1. Strict editor protection (only on ai.studio/apps/<id> without fullscreen)
        if ((host.equals("ai.studio") || host.equals("aistudio.google.com")) && path.contains("3f0807e3-2494-4289-a3a6-c12032da731c")) {
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
