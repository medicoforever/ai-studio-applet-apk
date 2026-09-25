import UIKit
import WebKit
import AVFoundation

class ViewController: UIViewController, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {

    public static let targetURLString = "https://ai.studio/apps/3f0807e3-2494-4289-a3a6-c12032da731c?fullscreenApplet=true"
    public static let safariUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
    public static let githubReleasesURL = "https://api.github.com/repos/medicoforever/ai-studio-applet-apk/releases/latest"

    private var webView: WKWebView!
    private var progressView: UIProgressView!
    private var silentAudioPlayer: AVAudioPlayer?
    private var progressObservation: NSKeyValueObservation?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black

        setupWebView()
        setupProgressView()
        setupRefreshControl()
        startSilentAudioKeepAlive()
        checkForUpdates()
        loadTargetURL()
    }

    override var preferredStatusBarStyle: UIStatusBarStyle {
        return .lightContent
    }

    // MARK: - WKWebView Setup

    private func setupWebView() {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.defaultWebpagePreferences.allowsContentJavaScript = true
        configuration.websiteDataStore = WKWebsiteDataStore.default()

        // Setup UserContentController and JavaScript Bridge
        let userContentController = WKUserContentController()
        userContentController.add(self, name: "iosBridge")

        // Injected UI Cleaner script
        if let scriptPath = Bundle.main.path(forResource: "UiCleanerScript", ofType: "js"),
           let scriptContent = try? String(contentsOfFile: scriptPath, encoding: .utf8) {
            let userScript = WKUserScript(source: scriptContent, injectionTime: .atDocumentEnd, forMainFrameOnly: false)
            userContentController.addUserScript(userScript)
        }

        configuration.userContentController = userContentController

        webView = WKWebView(frame: .zero, configuration: configuration)
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.backgroundColor = .black
        webView.isOpaque = true
        webView.scrollView.bounces = true
        webView.scrollView.contentInsetAdjustmentBehavior = .never

        // Standard Mobile Safari User Agent avoids Google 403 disallowed_useragent
        webView.customUserAgent = ViewController.safariUserAgent

        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.topAnchor.constraint(equalTo: view.topAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor)
        ])
    }

    private func setupProgressView() {
        progressView = UIProgressView(progressViewStyle: .bar)
        progressView.translatesAutoresizingMaskIntoConstraints = false
        progressView.tintColor = UIColor(red: 0.12, green: 0.53, blue: 0.90, alpha: 1.0)
        progressView.trackTintColor = .clear
        view.addSubview(progressView)

        NSLayoutConstraint.activate([
            progressView.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor),
            progressView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            progressView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            progressView.heightAnchor.constraint(equalToConstant: 2.5)
        ])

        progressObservation = webView.observe(\.estimatedProgress, options: [.new]) { [weak self] _, change in
            guard let self = self, let progress = change.newValue else { return }
            self.progressView.alpha = 1.0
            self.progressView.setProgress(Float(progress), animated: true)
            if progress >= 1.0 {
                UIView.animate(withDuration: 0.35, delay: 0.15, options: .curveEaseOut, animations: {
                    self.progressView.alpha = 0.0
                }, completion: { _ in
                    self.progressView.setProgress(0.0, animated: false)
                })
            }
        }
    }

    private func setupRefreshControl() {
        let refreshControl = UIRefreshControl()
        refreshControl.tintColor = .white
        refreshControl.addTarget(self, action: #selector(handleRefresh(_:)), for: .valueChanged)
        webView.scrollView.refreshControl = refreshControl
    }

    @objc private func handleRefresh(_ sender: UIRefreshControl) {
        webView.reload()
        sender.endRefreshing()
    }

    private func loadTargetURL() {
        guard let url = URL(string: ViewController.targetURLString) else { return }
        var request = URLRequest(url: url)
        request.cachePolicy = .useProtocolCachePolicy
        request.timeoutInterval = 30.0
        webView.load(request)
    }

    // MARK: - WKUIDelegate

    // iOS 15+ Automatic WebRTC & Microphone Permission Granting
    @available(iOS 15.0, *)
    func webView(
        _ webView: WKWebView,
        requestMediaCapturePermissionFor origin: WKSecurityOrigin,
        initiatedByFrame frame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: @escaping (WKPermissionDecision) -> Void
    ) {
        // Automatically grant microphone permissions to AI Studio and embedded Applet
        decisionHandler(.grant)
    }

    // Handle Google Sign-In and OAuth popup windows
    func webView(
        _ webView: WKWebView,
        createWebViewWith configuration: WKWebViewConfiguration,
        for navigationAction: WKNavigationAction,
        windowFeatures: WKWindowFeatures
    ) -> WKWebView? {
        if navigationAction.targetFrame == nil {
            webView.load(navigationAction.request)
        }
        return nil
    }

    // MARK: - WKNavigationDelegate

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        injectUiCleaner()
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction, decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard let url = navigationAction.request.url else {
            decisionHandler(.allow)
            return
        }

        let urlString = url.absoluteString.lowercased()

        // Handle mailto or tel links outside webview
        if urlString.hasPrefix("mailto:") || urlString.hasPrefix("tel:") {
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
            decisionHandler(.cancel)
            return
        }

        decisionHandler(.allow)
    }

    private func injectUiCleaner() {
        if let scriptPath = Bundle.main.path(forResource: "UiCleanerScript", ofType: "js"),
           let scriptContent = try? String(contentsOfFile: scriptPath, encoding: .utf8) {
            webView.evaluateJavaScript(scriptContent, completionHandler: nil)
        }
    }

    // MARK: - WKScriptMessageHandler (Native iOS Bridge)

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "iosBridge",
              let body = message.body as? [String: Any],
              let action = body["action"] as? String else {
            return
        }

        switch action {
        case "saveBase64File":
            if let base64Data = body["base64Data"] as? String,
               let filename = body["filename"] as? String {
                let mimeType = body["mimeType"] as? String ?? "application/octet-stream"
                saveAndShareFile(base64Data: base64Data, filename: filename, mimeType: mimeType)
            }

        case "requestMicrophone":
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                print("[RADDOC] Microphone permission granted: \(granted)")
            }

        default:
            break
        }
    }

    // MARK: - File Export & Native Share Sheet

    private func saveAndShareFile(base64Data: String, filename: String, mimeType: String) {
        var cleanBase64 = base64Data
        if let commaIndex = cleanBase64.firstIndex(of: ",") {
            cleanBase64 = String(cleanBase64[cleanBase64.index(after: commaIndex)...])
        }

        guard let data = Data(base64Encoded: cleanBase64, options: .ignoreUnknownCharacters) else {
            print("[RADDOC] Error: Invalid Base64 payload")
            return
        }

        let tempDirectory = FileManager.default.temporaryDirectory
        let safeFilename = sanitizeFilename(filename.isEmpty ? "report.docx" : filename)
        let fileURL = tempDirectory.appendingPathComponent(safeFilename)

        do {
            try data.write(to: fileURL)
            DispatchQueue.main.async { [weak self] in
                guard let self = self else { return }
                let activityVC = UIActivityViewController(activityItems: [fileURL], applicationActivities: nil)
                if let popover = activityVC.popoverPresentationController {
                    popover.sourceView = self.view
                    popover.sourceRect = CGRect(x: self.view.bounds.midX, y: self.view.bounds.midY, width: 0, height: 0)
                    popover.permittedArrowDirections = []
                }
                self.present(activityVC, animated: true)
            }
        } catch {
            print("[RADDOC] Failed to write temporary download file: \(error)")
        }
    }

    private func sanitizeFilename(_ name: String) -> String {
        let invalidChars = CharacterSet(charactersIn: "\\/:*?\"<>|")
        return name.components(separatedBy: invalidChars).joined(separator: "_")
    }

    // MARK: - Background Audio Keep-Alive

    private func startSilentAudioKeepAlive() {
        // Continuous silent WAV keeps audio engine and WebRTC capture alive when minimized
        let silentWavBase64 = "UklGRiQAAABXQVZFZm10IBAAAAABAAEARKwAAIhYAQACABAAZGF0YQAAAAA="
        guard let data = Data(base64Encoded: silentWavBase64) else { return }
        do {
            silentAudioPlayer = try AVAudioPlayer(data: data)
            silentAudioPlayer?.numberOfLoops = -1
            silentAudioPlayer?.volume = 0.001
            silentAudioPlayer?.prepareToPlay()
            silentAudioPlayer?.play()
            print("[RADDOC] Silent background audio keep-alive active.")
        } catch {
            print("[RADDOC] Could not start silent audio keep-alive: \(error)")
        }
    }

    // MARK: - Update Checker

    private func checkForUpdates() {
        guard let url = URL(string: ViewController.githubReleasesURL) else { return }
        var request = URLRequest(url: url)
        request.setValue("RADDOC-iOS-App", forHTTPHeaderField: "User-Agent")

        URLSession.shared.dataTask(with: request) { [weak self] data, response, error in
            guard let self = self, let data = data, error == nil else { return }
            do {
                if let json = try JSONSerialization.jsonObject(with: data, options: []) as? [String: Any],
                   let htmlUrl = json["html_url"] as? String,
                   let tagName = json["tag_name"] as? String {
                    if tagName != "v1.0.0" {
                        DispatchQueue.main.async {
                            self.showUpdateAlert(releaseURL: htmlUrl, tagName: tagName)
                        }
                    }
                }
            } catch {}
        }.resume()
    }

    private func showUpdateAlert(releaseURL: String, tagName: String) {
        let alert = UIAlertController(
            title: "Update Available",
            message: "A newer release (\(tagName)) is available on GitHub.",
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: "View Release", style: .default, handler: { _ in
            if let url = URL(string: releaseURL) {
                UIApplication.shared.open(url)
            }
        }))
        alert.addAction(UIAlertAction(title: "Later", style: .cancel, handler: nil))
        present(alert, animated: true)
    }

    deinit {
        progressObservation?.invalidate()
    }
}
