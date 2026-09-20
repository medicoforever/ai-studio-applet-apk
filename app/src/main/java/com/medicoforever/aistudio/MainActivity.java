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
import androidx.core.app.NotificationCompat;
import org.json.JSONTokener;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import android.media.MediaScannerConnection;
import android.util.Base64;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    public static final String TARGET_URL = "https://ai.studio/apps/3f0807e3-2494-4289-a3a6-c12032da731c?fullscreenApplet=true";
    public static final int CURRENT_VERSION_CODE = 15;
    public static final String GITHUB_RELEASE_API = "https://api.github.com/repos/medicoforever/ai-studio-applet-apk/releases/tags/v1.0.8";
    public static final String APK_DOWNLOAD_URL = "https://github.com/medicoforever/ai-studio-applet-apk/releases/download/v1.0.8/RADDOC-Dictation-Release.apk";
    private static final int PERMISSION_REQ_CODE = 2001;
    private static final int FILE_CHOOSER_REQ_CODE = 3001;

    private WebView webView;
    private ProgressBar progressBar;
    private ValueCallback<Uri[]> filePathCallback;
    private String chromeUserAgent;
    private PermissionRequest pendingPermissionRequest;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        progressBar = findViewById(R.id.progressBar);

        startKeepAliveService();
        checkAndRequestPermissions();
        setupWebView();

        // Delay battery optimization prompt by 3s so it never suppresses runtime permission dialogs
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                requestBatteryOptimizationExemption();
            }
        }, 3000);

        // Check for app updates in background after 2.5s
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!isFinishing() && !isDestroyed()) {
                checkForAppUpdates();
            }
        }, 2500);

        if (savedInstanceState == null) {
            webView.loadUrl(TARGET_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkAndRequestPermissions();
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
                if (url != null && isAppletUrl(url)) {
                    injectUiCleaner(view);
                }
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                progressBar.setVisibility(View.GONE);
                CookieManager.getInstance().flush();
                if (url != null && isAppletUrl(url)) {
                    injectUiCleaner(view);
                }
            }
        });

        // WebChromeClient
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progressBar.setProgress(newProgress);
                if (newProgress == 100) {
                    progressBar.setVisibility(View.GONE);
                    String currentUrl = view.getUrl();
                    if (currentUrl != null && isAppletUrl(currentUrl)) {
                        injectUiCleaner(view);
                    }
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean needsAudio = false;
                    boolean needsVideo = false;
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                            needsAudio = true;
                        } else if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
                            needsVideo = true;
                        }
                    }

                    List<String> missingPermissions = new ArrayList<>();
                    if (needsAudio && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        missingPermissions.add(Manifest.permission.RECORD_AUDIO);
                    }
                    if (needsVideo && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                        missingPermissions.add(Manifest.permission.CAMERA);
                    }

                    if (!missingPermissions.isEmpty()) {
                        pendingPermissionRequest = request;
                        ActivityCompat.requestPermissions(MainActivity.this, missingPermissions.toArray(new String[0]), PERMISSION_REQ_CODE);
                    } else {
                        request.grant(request.getResources());
                    }
                });
            }

            @Override
            public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingPermissionRequest == request) {
                    pendingPermissionRequest = null;
                }
            }
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
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
    }

    private boolean isAppletUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        if (lower.contains("accounts.google") || lower.contains("signin") || 
            lower.contains("oauth") || lower.contains("lifecycle") || 
            lower.contains("authuser") || lower.contains("servicelogin")) {
            return false;
        }
        return (lower.contains("ai.studio") || lower.contains("aistudio.google.com")) && 
               lower.contains("3f0807e3-2494-4289-a3a6-c12032da731c");
    }

    // Injects UI cleaner into host AI Studio page to remove bottom disclaimer banner,
    // enforce Preview tab visibility, and ensure applet iframe stays 100% fullscreen on error
    private void injectUiCleaner(WebView view) {
        if (view == null) return;
        String currentUrl = view.getUrl();
        if (!isAppletUrl(currentUrl)) {
            return;
        }

        String js = "(function() {" +
            "function isAppletPage() {" +
                "var h = (window.location.hostname || '').toLowerCase();" +
                "var p = (window.location.pathname || '').toLowerCase();" +
                "var q = (window.location.search || '').toLowerCase();" +
                "if (h.indexOf('ai.studio') === -1 && h.indexOf('aistudio.google.com') === -1) return false;" +
                "if (p.indexOf('signin') !== -1 || p.indexOf('oauth') !== -1 || p.indexOf('lifecycle') !== -1 || q.indexOf('authuser') !== -1) return false;" +
                "return p.indexOf('3f0807e3-2494-4289-a3a6-c12032da731c') !== -1;" +
            "}" +
            "if (!isAppletPage()) return;" +

            "var micAllowPolicy = 'microphone *; camera *; autoplay *; display-capture *; clipboard-read *; clipboard-write *;';" +

            // 1. Hook Document.prototype.createElement so all iframes get allow attribute immediately upon creation
            "if (!window.__raddocElementHooked) {" +
                "window.__raddocElementHooked = true;" +
                "try {" +
                    "var origCreate = Document.prototype.createElement;" +
                    "Document.prototype.createElement = function(tag, opts) {" +
                        "var el = origCreate.call(this, tag, opts);" +
                        "if (tag && typeof tag === 'string' && tag.toLowerCase() === 'iframe') {" +
                            "try {" +
                                "el.setAttribute('allow', micAllowPolicy);" +
                                "el.allow = micAllowPolicy;" +
                                "var curSb = el.getAttribute('sandbox') || '';" +
                                "if (curSb && curSb.indexOf('allow-downloads') === -1) {" +
                                    "el.setAttribute('sandbox', curSb + ' allow-downloads');" +
                                "}" +
                            "} catch(e) {}" +
                        "}" +
                        "return el;" +
                    "};" +
                "} catch(e) {}" +
            "}" +

            // Hook HTMLAnchorElement.prototype.click to intercept all in-memory Blob and Data downloads
            "if (!window.__raddocAnchorHooked) {" +
                "window.__raddocAnchorHooked = true;" +
                "try {" +
                    "var origAnchorClick = HTMLAnchorElement.prototype.click;" +
                    "HTMLAnchorElement.prototype.click = function() {" +
                        "try {" +
                            "var href = this.href || '';" +
                            "var dl = this.getAttribute('download') || this.download;" +
                            "if (href.indexOf('blob:') === 0 || (dl && href.indexOf('data:') === 0)) {" +
                                "var fname = dl || 'dictation_file';" +
                                "var xhr = new XMLHttpRequest();" +
                                "xhr.open('GET', href, true);" +
                                "xhr.responseType = 'blob';" +
                                "xhr.onload = function() {" +
                                    "var reader = new FileReader();" +
                                    "reader.onloadend = function() {" +
                                        "if (window.AndroidBridge && window.AndroidBridge.saveBase64File) {" +
                                            "window.AndroidBridge.saveBase64File(reader.result, fname, xhr.response.type || 'application/octet-stream');" +
                                        "}" +
                                    "};" +
                                    "reader.readAsDataURL(xhr.response);" +
                                "};" +
                                "xhr.send();" +
                            "}" +
                        "} catch(e) {}" +
                        "return origAnchorClick.apply(this, arguments);" +
                    "};" +
                "} catch(e) {}" +
            "}" +

            "if (window.__raddocCleanInjected) {" +
                "if (typeof window.__raddocEnforce === 'function') window.__raddocEnforce();" +
                "return;" +
            "}" +
            "window.__raddocCleanInjected = true;" +

            // 2. Intercept postMessage switchToChat error messages originating from the applet iframe
            "window.addEventListener('message', function(e) {" +
                "try {" +
                    "var origin = (e.origin || '').toLowerCase();" +
                    "if (origin.indexOf('accounts.google') !== -1 || origin.indexOf('apis.google') !== -1) return;" +
                    "var d = e.data;" +
                    "if (!d) return;" +
                    "var isErr = false;" +
                    "if (typeof d === 'string') {" +
                        "var s = d.toLowerCase();" +
                        "if (s.indexOf('switchtochat') !== -1 || s.indexOf('view_chat') !== -1 || s.indexOf('switch_to_chat') !== -1) {" +
                            "isErr = true;" +
                        "}" +
                    "} else if (typeof d === 'object') {" +
                        "var action = String(d.action || d.type || d.event || '').toLowerCase();" +
                        "if (action === 'switchtochat' || action === 'view_chat' || action === 'switch_tab_chat') {" +
                            "isErr = true;" +
                        "}" +
                    "}" +
                    "if (isErr) {" +
                        "e.stopImmediatePropagation();" +
                        "e.stopPropagation();" +
                    "}" +
                "} catch(ex) {}" +
            "}, true);" +

            // 3. Main enforcement function
            "window.__raddocEnforce = function() {" +
                "try {" +
                    "if (!isAppletPage()) return;" +

                    // A. Pin the dictation applet iframe to fullscreen at high z-index and enable microphone
                    "var iframes = document.querySelectorAll('iframe');" +
                    "var appletIframe = null;" +
                    "for (var i = 0; i < iframes.length; i++) {" +
                        "var ifr = iframes[i];" +
                        "try {" +
                            "var curSb = ifr.getAttribute('sandbox') || '';" +
                            "if (curSb && curSb.indexOf('allow-downloads') === -1) {" +
                                "ifr.setAttribute('sandbox', curSb + ' allow-downloads');" +
                            "}" +
                            "var curAllow = ifr.getAttribute('allow') || '';" +
                            "if (curAllow.indexOf('microphone') === -1) {" +
                                "ifr.setAttribute('allow', micAllowPolicy);" +
                                "ifr.allow = micAllowPolicy;" +
                                "if (!ifr.hasAttribute('data-raddoc-mic-set')) {" +
                                    "ifr.setAttribute('data-raddoc-mic-set', 'true');" +
                                    "if (ifr.src && ifr.src.indexOf('about:blank') === -1) {" +
                                        "ifr.src = ifr.src;" +
                                    "}" +
                                "}" +
                            "}" +
                        "} catch(e) {}" +
                        "var src = (ifr.src || '').toLowerCase();" +
                        "if (src.indexOf('accounts.google') !== -1) continue;" +
                        "if (src.indexOf('usercontent') !== -1 || ifr.hasAttribute('sandbox') || (ifr.offsetWidth > 100 && ifr.offsetHeight > 100)) {" +
                            "appletIframe = ifr;" +
                            "break;" +
                        "}" +
                    "}" +

                    "if (appletIframe) {" +
                        "try {" +
                            "appletIframe.setAttribute('allow', micAllowPolicy);" +
                            "appletIframe.allow = micAllowPolicy;" +
                        "} catch(e) {}" +
                        "appletIframe.style.setProperty('position', 'fixed', 'important');" +
                        "appletIframe.style.setProperty('top', '0px', 'important');" +
                        "appletIframe.style.setProperty('left', '0px', 'important');" +
                        "appletIframe.style.setProperty('width', '100vw', 'important');" +
                        "appletIframe.style.setProperty('height', '100vh', 'important');" +
                        "appletIframe.style.setProperty('max-height', '100vh', 'important');" +
                        "appletIframe.style.setProperty('z-index', '99999', 'important');" +
                        "appletIframe.style.setProperty('border', 'none', 'important');" +
                    "}" +

                    // B. Detect if Chat view or Remix prompt is active
                    "var bodyText = document.body ? (document.body.innerText || '') : '';" +
                    "var isChatShowing = (bodyText.indexOf('Remix to make this app your own') !== -1 || " +
                                         "bodyText.indexOf('Here are some ideas to try') !== -1 || " +
                                         "bodyText.indexOf('Generate video from text') !== -1);" +

                    // C. Locate Preview tab button
                    "var buttons = document.querySelectorAll('button, [role=\"tab\"], [role=\"button\"], a');" +
                    "var previewBtn = null;" +
                    "for (var b = 0; b < buttons.length; b++) {" +
                        "var btn = buttons[b];" +
                        "var txt = (btn.textContent || '').trim();" +
                        "var aria = btn.getAttribute('aria-label') || '';" +
                        "if (txt === 'Preview' || aria === 'Preview') {" +
                            "previewBtn = btn;" +
                            "var isSelected = btn.getAttribute('aria-selected') === 'true' || " +
                                             "btn.classList.contains('active') || " +
                                             "btn.classList.contains('selected') || " +
                                             "btn.classList.contains('mdc-tab--active');" +
                            "if (!isSelected) {" +
                                "isChatShowing = true;" +
                            "}" +
                            "break;" +
                        "}" +
                    "}" +

                    // If Chat view is displayed or Preview is deselected, click Preview immediately!
                    "if (previewBtn && isChatShowing) {" +
                        "previewBtn.click();" +
                    "}" +

                    // D. Safely hide disclaimer banner text if present
                    "var allElements = document.querySelectorAll('p, span, footer, aside, [role=\"status\"], [role=\"alert\"]');" +
                    "for (var j = 0; j < allElements.length; j++) {" +
                        "var el = allElements[j];" +
                        "var t = el.textContent || '';" +
                        "if (t.indexOf('This app was developed by another user') !== -1 && el.children.length === 0) {" +
                            "var parent = el.parentElement;" +
                            "if (parent && parent !== document.body && !parent.querySelector('iframe')) {" +
                                "parent.style.setProperty('display', 'none', 'important');" +
                            "}" +
                        "}" +
                    "}" +

                    // E. Background Media Keep-Alive: ensures Chromium classifies page as active media player, exempting it from timer throttling & background audio capture suspension
                    "if (!window.__raddocSilentAudio) {" +
                        "try {" +
                            "var audioCtx = new (window.AudioContext || window.webkitAudioContext)();" +
                            "var buffer = audioCtx.createBuffer(1, audioCtx.sampleRate * 2, audioCtx.sampleRate);" +
                            "var source = audioCtx.createBufferSource();" +
                            "source.buffer = buffer;" +
                            "source.loop = true;" +
                            "var gainNode = audioCtx.createGain();" +
                            "gainNode.gain.value = 0.0001;" +
                            "source.connect(gainNode);" +
                            "gainNode.connect(audioCtx.destination);" +
                            "source.start(0);" +
                            "window.__raddocSilentAudio = { ctx: audioCtx, src: source };" +
                        "} catch(eAudio) {" +
                            "try {" +
                                "var a = document.createElement('audio');" +
                                "a.src = 'data:audio/wav;base64,UklGRigAAABXQVZFZm10IBIAAAABAAEARKwAAIhYAQACABAAAABkYXRhAgAAAAEA';" +
                                "a.loop = true;" +
                                "a.volume = 0.01;" +
                                "a.play().catch(function(){});" +
                                "window.__raddocSilentAudio = a;" +
                            "} catch(eAudio2) {}" +
                        "}" +
                    "}" +
                    "if (window.__raddocSilentAudio && window.__raddocSilentAudio.ctx && window.__raddocSilentAudio.ctx.state === 'suspended') {" +
                        "window.__raddocSilentAudio.ctx.resume().catch(function(){});" +
                    "}" +
                "} catch(e) {}" +
            "};" +

            "window.__raddocEnforce();" +
            "if (!window.__raddocInterval) {" +
                "window.__raddocInterval = setInterval(window.__raddocEnforce, 300);" +
            "}" +
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
        public void saveBase64File(String base64Data, String filename, String mimeType) {
            runOnUiThread(() -> saveBase64ToDownloads(base64Data, filename, mimeType));
        }

        @JavascriptInterface
        public boolean hasRecordAudioPermission() {
            return ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        }

        @JavascriptInterface
        public void requestRecordAudioPermission() {
            runOnUiThread(() -> {
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQ_CODE);
                }
            });
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
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

        // 1. Custom schemes (intent:, tg:, mailto:, etc.)
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            handleCustomScheme(url);
            return true;
        }

        // 2. External Telegram link
        if (url.contains("t.me") || url.contains("telegram.me")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true;
            } catch (Exception ignored) {}
        }

        // 3. External Drive folder link (e.g. drive.google.com/drive/folders/...)
        if (url.contains("drive.google.com") && !url.contains("picker")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                startActivity(intent);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        // 4. AUTH, LOGIN, GOOGLE SERVICES & INTERNAL URLS: NEVER intercept! Let WebView load naturally!
        if (isInternalUrl(url)) {
            return false;
        }

        // 5. If user navigates away from AI Studio to another website, open externally
        Uri uri = Uri.parse(url);
        String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
        if (!host.contains("ai.studio") && !host.contains("google.com") && !host.contains("googleusercontent.com")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                startActivity(intent);
                return true;
            } catch (Exception ignored) {}
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
               lower.contains("signin") ||
               lower.contains("signup") ||
               lower.contains("servicelogin") ||
               lower.contains("lifecycle") ||
               lower.contains("authuser") ||
               lower.contains("continue=") ||
               lower.contains("state=") ||
               lower.contains("code=");
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
        if (url == null || url.isEmpty()) return;

        if (url.startsWith("blob:")) {
            handleBlobDownload(url, contentDisposition, mimetype);
            return;
        }

        if (url.startsWith("data:")) {
            saveBase64ToDownloads(url, extractFilename(contentDisposition, mimetype), mimetype);
            return;
        }

        handleHttpDownload(url, userAgent, contentDisposition, mimetype);
    }

    private void handleBlobDownload(String blobUrl, String contentDisposition, String mimeType) {
        String filename = extractFilename(contentDisposition, mimeType);
        String js = "(function() {" +
                "var url = '" + blobUrl + "';" +
                "var fname = '" + filename + "';" +
                "var mime = '" + (mimeType != null ? mimeType : "application/octet-stream") + "';" +
                "var xhr = new XMLHttpRequest();" +
                "xhr.open('GET', url, true);" +
                "xhr.responseType = 'blob';" +
                "xhr.onload = function() {" +
                "    var reader = new FileReader();" +
                "    reader.onloadend = function() {" +
                "        if (window.AndroidBridge && window.AndroidBridge.saveBase64File) {" +
                "            window.AndroidBridge.saveBase64File(reader.result, fname, mime);" +
                "        }" +
                "    };" +
                "    reader.readAsDataURL(xhr.response);" +
                "};" +
                "xhr.onerror = function() {" +
                "    console.error('Failed to extract blob download');" +
                "};" +
                "xhr.send();" +
                "})();";
        webView.evaluateJavascript(js, null);
    }

    private String extractFilename(String contentDisposition, String mimeType) {
        String filename = null;
        if (contentDisposition != null) {
            try {
                filename = URLUtil.guessFileName("", contentDisposition, mimeType);
            } catch (Exception ignored) {}
        }
        if (filename == null || filename.isEmpty() || filename.equalsIgnoreCase("downloadfile") || filename.equalsIgnoreCase("downloadfile.bin")) {
            String ext = ".bin";
            if (mimeType != null) {
                String m = mimeType.toLowerCase();
                if (m.contains("audio/wav") || m.contains("wav")) ext = ".wav";
                else if (m.contains("audio/webm") || m.contains("webm")) ext = ".webm";
                else if (m.contains("audio/mpeg") || m.contains("mp3")) ext = ".mp3";
                else if (m.contains("word") || m.contains("msword") || m.contains(".document")) ext = ".doc";
                else if (m.contains("rtf")) ext = ".rtf";
                else if (m.contains("html")) ext = ".html";
                else if (m.contains("text/plain") || m.contains("txt")) ext = ".txt";
            }
            filename = "Dictation_File_" + System.currentTimeMillis() + ext;
        }
        return filename;
    }

    private void handleHttpDownload(String url, String userAgent, String contentDisposition, String mimetype) {
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimetype);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("cookie", cookies);
            request.addRequestHeader("User-Agent", userAgent);
            request.setDescription("Downloading file...");
            String filename = extractFilename(contentDisposition, mimetype);
            request.setTitle(filename);
            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(getApplicationContext(), "Downloading " + filename + "...", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception ex) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(getApplicationContext(), "Could not start download: " + ex.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    public void saveBase64ToDownloads(String base64Data, String filename, String mimeType) {
        if (base64Data == null || base64Data.isEmpty()) {
            Toast.makeText(this, "Download failed: empty content", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            int commaIndex = base64Data.indexOf(",");
            String pureBase64 = (commaIndex >= 0) ? base64Data.substring(commaIndex + 1) : base64Data;
            byte[] fileBytes = Base64.decode(pureBase64, Base64.DEFAULT);

            String safeName = (filename == null || filename.trim().isEmpty()) ? "Dictation_Report" : filename.trim();
            safeName = safeName.replaceAll("[\\\\/:*?\"<>|]", "_");

            File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!downloadsDir.exists()) {
                downloadsDir.mkdirs();
            }

            File targetFile = new File(downloadsDir, safeName);
            int counter = 1;
            String base = safeName;
            String ext = "";
            int dot = safeName.lastIndexOf('.');
            if (dot > 0) {
                base = safeName.substring(0, dot);
                ext = safeName.substring(dot);
            }
            while (targetFile.exists()) {
                targetFile = new File(downloadsDir, base + " (" + counter + ")" + ext);
                counter++;
            }

            FileOutputStream fos = new FileOutputStream(targetFile);
            fos.write(fileBytes);
            fos.flush();
            fos.close();

            MediaScannerConnection.scanFile(this, new String[]{targetFile.getAbsolutePath()}, null, null);
            Toast.makeText(this, "Downloaded to Downloads/" + targetFile.getName(), Toast.LENGTH_LONG).show();

            showDownloadNotification(targetFile, mimeType);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Save error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showDownloadNotification(File file, String mimeType) {
        try {
            String channelId = "dictation_downloads";
            android.app.NotificationManager nm = (android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.NotificationChannel channel = new android.app.NotificationChannel(
                        channelId, "File Downloads", android.app.NotificationManager.IMPORTANCE_DEFAULT);
                channel.setDescription("Notifications for downloaded dictation files and reports");
                if (nm != null) nm.createNotificationChannel(channel);
            }

            Uri fileUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent viewIntent = new Intent(Intent.ACTION_VIEW);
            viewIntent.setDataAndType(fileUri, (mimeType != null && !mimeType.isEmpty()) ? mimeType : "*/*");
            viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            android.app.PendingIntent pendingIntent = android.app.PendingIntent.getActivity(
                    this, (int) System.currentTimeMillis(), viewIntent,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? android.app.PendingIntent.FLAG_IMMUTABLE : 0);

            NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("Download Complete")
                    .setContentText(file.getName())
                    .setAutoCancel(true)
                    .setContentIntent(pendingIntent);

            if (nm != null) {
                nm.notify((int) System.currentTimeMillis(), builder.build());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void checkForAppUpdates() {
        new Thread(() -> {
            try {
                URL url = new URL(GITHUB_RELEASE_API);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("User-Agent", "RADDOC-Dictation-App");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);

                if (conn.getResponseCode() == 200) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                    reader.close();

                    JSONObject json = new JSONObject(sb.toString());
                    String body = json.optString("body", "");
                    String tagName = json.optString("tag_name", "");

                    int remoteVersionCode = CURRENT_VERSION_CODE;
                    Pattern p = Pattern.compile("VERSION_CODE:\\s*(\\d+)");
                    Matcher m = p.matcher(body);
                    if (m.find()) {
                        remoteVersionCode = Integer.parseInt(m.group(1));
                    }

                    boolean updateAvailable = remoteVersionCode > CURRENT_VERSION_CODE;

                    if (updateAvailable) {
                        final String downloadUrl = APK_DOWNLOAD_URL;
                        runOnUiThread(() -> promptUserToUpdate(downloadUrl, tagName));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void promptUserToUpdate(String downloadUrl, String tagName) {
        if (isFinishing() || isDestroyed()) return;

        new AlertDialog.Builder(this)
                .setTitle("Update Available")
                .setMessage("A new update of RADDOC Dictation is available with bug fixes and improvements. Would you like to install it now?")
                .setPositiveButton("Update Now", (dialog, which) -> downloadAndInstallApk(downloadUrl))
                .setNegativeButton("Later", null)
                .show();
    }

    private void downloadAndInstallApk(String downloadUrl) {
        ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("Downloading Update");
        progress.setMessage("Downloading RADDOC Dictation APK...");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            try {
                URL u = new URL(downloadUrl);
                HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.connect();

                int code = conn.getResponseCode();
                int redirects = 0;
                while ((code == HttpURLConnection.HTTP_MOVED_TEMP || code == HttpURLConnection.HTTP_MOVED_PERM || code == HttpURLConnection.HTTP_SEE_OTHER || code == 307) && redirects < 5) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect();
                    u = new URL(loc);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.connect();
                    code = conn.getResponseCode();
                    redirects++;
                }

                int fileLength = conn.getContentLength();
                File updatesDir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates");
                if (!updatesDir.exists()) updatesDir.mkdirs();
                File apkFile = new File(updatesDir, "RADDOC-Dictation-Update.apk");

                InputStream input = conn.getInputStream();
                FileOutputStream output = new FileOutputStream(apkFile);

                byte[] data = new byte[8192];
                long total = 0;
                int count;
                while ((count = input.read(data)) != -1) {
                    total += count;
                    if (fileLength > 0) {
                        int pct = (int) (total * 100 / fileLength);
                        runOnUiThread(() -> progress.setProgress(pct));
                    }
                    output.write(data, 0, count);
                }
                output.flush();
                output.close();
                input.close();

                runOnUiThread(() -> {
                    try {
                        progress.dismiss();
                    } catch (Exception ignored) {}
                    installApk(apkFile);
                });
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() -> {
                    try {
                        progress.dismiss();
                    } catch (Exception ignored) {}
                    Toast.makeText(MainActivity.this, "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    private void installApk(File apkFile) {
        if (!apkFile.exists()) {
            Toast.makeText(this, "APK file not found", Toast.LENGTH_SHORT).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!getPackageManager().canRequestPackageInstalls()) {
                new AlertDialog.Builder(this)
                        .setTitle("Permission Needed")
                        .setMessage("Please allow installing updates from this app in settings.")
                        .setPositiveButton("Settings", (d, w) -> {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                return;
            }
        }

        try {
            Uri apkUri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Installation error: " + e.getMessage(), Toast.LENGTH_LONG).show();
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
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean audioGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
            if (pendingPermissionRequest != null) {
                if (audioGranted) {
                    pendingPermissionRequest.grant(pendingPermissionRequest.getResources());
                } else {
                    pendingPermissionRequest.deny();
                    Toast.makeText(this, "Microphone permission is required for dictation recording", Toast.LENGTH_LONG).show();
                }
                pendingPermissionRequest = null;
            }
            if (audioGranted) {
                startKeepAliveService();
            } else {
                if (!ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                    showPermissionDeniedDialog();
                }
            }
        }
    }

    private void showPermissionDeniedDialog() {
        new androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Microphone Permission Required")
            .setMessage("Microphone permission is required to record audio dictation. Please enable it in App Settings.")
            .setPositiveButton("Open Settings", (dialog, which) -> {
                try {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
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
