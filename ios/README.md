# RADDOC's Dictation App for Apple iPhone (iOS)

Native iOS application wrapper built specifically for:
`https://ai.studio/apps/3f0807e3-2494-4289-a3a6-c12032da731c?fullscreenApplet=true`

---

## Key Features

1. **Continuous Background Audio Recording**:
   - Uses `AVAudioSession` set to category `.playAndRecord` with options `[.allowBluetooth, .defaultToSpeaker, .mixWithOthers]`.
   - Enabled `UIBackgroundModes` for `audio`, `processing`, and `fetch`.
   - Silent background audio keep-alive player ensures iOS does not suspend the app or the WebRTC microphone stream when the screen is locked or the app is minimized.
   - Disables device idle timer (`isIdleTimerDisabled = true`) while in active dictation.

2. **Microphone Permissions & WebRTC**:
   - `NSMicrophoneUsageDescription` configured in `Info.plist`.
   - Implements iOS 15+ `WKUIDelegate.webView(_:requestMediaCapturePermissionFor:initiatedByFrame:type:decisionHandler:)` to automatically grant microphone permissions to the AI Studio applet and cross-origin iframe.
   - Injected script ensures `allow="microphone; camera; display-capture; clipboard-read; clipboard-write; autoplay"` and `sandbox="... allow-downloads"`.

3. **Google Sign-In Compatibility**:
   - Standard Mobile Safari user agent string bypasses `403 disallowed_useragent`.
   - Persistent `WKWebsiteDataStore.default()` ensures login credentials and cookies remain saved across app sessions.
   - Handles popup windows and OAuth redirects natively within `WKUIDelegate`.

4. **Native File & Report Downloads (iOS Share Sheet)**:
   - Intercepts Blob and Data URL exports (Word `.docx`, Audio `.wav`/`.webm`, `.rtf`, `.html`) from the applet.
   - Transfers file data over the native WebKit message handler bridge (`iosBridge`).
   - Automatically presents the native iOS `UIActivityViewController` (Share Sheet), allowing doctors to:
     - **Save to Files** (iCloud Drive or On My iPhone)
     - **AirDrop** to Mac or iPad
     - **Open Directly in Microsoft Word**, Pages, Mail, WhatsApp, or Telegram.

5. **Fullscreen Pinning & Error Shielding**:
   - Pins the dictation applet iframe to fullscreen at high z-index.
   - Intercepts error `switchtochat` postMessages to keep the app on the dictation Preview tab.
   - Hides AI Studio disclaimer bars and unnecessary UI clutter.

---

## Download Pre-Compiled iOS App (.IPA)

- **Direct Download (IPA)**: [RADDOC-Dictation-iOS.ipa](https://github.com/medicoforever/ai-studio-applet-apk/releases/download/v1.0.8/RADDOC-Dictation-iOS.ipa)
- **Direct Download (App Zip)**: [RADDOC-Dictation-iOS.app.zip](https://github.com/medicoforever/ai-studio-applet-apk/releases/download/v1.0.8/RADDOC-Dictation-iOS.app.zip)

---

## How to Install on iPhone

Since iOS requires apps distributed outside the App Store to be sideloaded, you can install the `.ipa` onto any iPhone in minutes using any of these standard methods:

### Method 1: Sideloadly (Recommended, Windows or Mac)
1. Download [Sideloadly](https://sideloadly.io/) on your PC or Mac.
2. Connect your iPhone via USB cable or ensure Wi-Fi sync is enabled.
3. Drag and drop `RADDOC-Dictation-iOS.ipa` into Sideloadly.
4. Enter your Apple ID (used by Apple to sign the app for your personal device).
5. Click **Start**. The app will appear on your iPhone home screen!
6. On iPhone, go to **Settings > General > VPN & Device Management** and tap **Trust [Your Apple ID]**.

### Method 2: AltStore / AltServer
1. Install [AltStore](https://altstore.io/) on your iPhone.
2. In AltStore on your iPhone, go to **My Apps** and tap the **+** button at the top left.
3. Select `RADDOC-Dictation-iOS.ipa` from your Files app.
4. The app installs and auto-refreshes over Wi-Fi.

### Method 3: TrollStore / Scarlet
- If using TrollStore or Scarlet, open the IPA directly on your iPhone and tap **Install**.

### Method 4: Build from Xcode (Mac Developers)
1. Open `ios/RADDOCDictation.xcodeproj` in Xcode.
2. Select your development team in Signing & Capabilities.
3. Connect your iPhone and click **Run** (`Cmd + R`).
