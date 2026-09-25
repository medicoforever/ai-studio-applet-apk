# AI Studio Applet App (Android APK & Apple iPhone iOS)

Dedicated mobile application wrappers built specifically for Google AI Studio:
`https://ai.studio/apps/3f0807e3-2494-4289-a3a6-c12032da731c?fullscreenApplet=true`

---

## 📱 Downloads (Releases)

| Platform | Format | Direct Download Link |
| :--- | :--- | :--- |
| **Apple iPhone (iOS)** | **IPA Package** | [Download RADDOC-Dictation-iOS.ipa](https://github.com/medicoforever/ai-studio-applet-apk/releases/download/v1.0.8/RADDOC-Dictation-iOS.ipa) |
| **Apple iPhone (iOS)** | **App Zip** | [Download RADDOC-Dictation-iOS.app.zip](https://github.com/medicoforever/ai-studio-applet-apk/releases/download/v1.0.8/RADDOC-Dictation-iOS.app.zip) |
| **Android** | **Release APK** | [Download RADDOC-Dictation-Release.apk](https://github.com/medicoforever/ai-studio-applet-apk/releases/download/v1.0.8/RADDOC-Dictation-Release.apk) |
| **Android** | **Debug APK** | [Download RADDOC-Dictation-Debug.apk](https://github.com/medicoforever/ai-studio-applet-apk/releases/download/v1.0.8/RADDOC-Dictation-Debug.apk) |

Visit the [Releases](https://github.com/medicoforever/ai-studio-applet-apk/releases) tab to view all builds and release notes.

---

## 🍎 Apple iPhone (iOS) Application

The native iOS app is located in the [`ios/`](ios/) folder and built for iOS 15.0+.

### Why this iOS app?
Standard Mobile Safari freezes background tabs, terminates WebSockets, and suspends audio recording within seconds of minimizing or locking the screen.

This iOS app solves that with:
1. **Continuous Background Audio Recording**: Uses `AVAudioSession` (`.playAndRecord` category) combined with `UIBackgroundModes` (`audio`, `processing`, `fetch`) and a silent audio keep-alive player so the microphone and dictation timers run continuously without muting or dropping out.
2. **Auto-Granted Microphone Permissions**: Automatically grants WebRTC / `getUserMedia` audio capture via iOS 15+ `WKUIDelegate` without prompt loops.
3. **Google Sign-In Compatibility**: Mobile Safari user agent bypassing `403 disallowed_useragent` with persistent cookies (`WKWebsiteDataStore.default()`).
4. **Native File & Report Downloads (Share Sheet)**: Intercepts Word `.docx`, Audio `.wav`/`.webm`, `.rtf`, and `.html` file downloads from the applet and presents the native iOS Share Sheet so doctors can **Save to Files** (iCloud / Local), **AirDrop**, or open in Microsoft Word.
5. **Fullscreen Pinning & Error Shielding**: Pins the applet iframe to 100vw x 100vh and intercepts `switchtochat` postMessages to keep dictation locked on the Preview screen.

### How to Install on iPhone:
- **Using Sideloadly (Windows / Mac)**: Open [Sideloadly](https://sideloadly.io/), drag and drop `RADDOC-Dictation-iOS.ipa`, enter your Apple ID, and click Start. Go to **Settings > General > VPN & Device Management** on iPhone to trust the profile.
- **Using AltStore**: Open AltStore on iPhone, tap **+**, and select the downloaded `RADDOC-Dictation-iOS.ipa`.
- **Using TrollStore / Scarlet**: Directly tap to install on-device.

---

## 🤖 Android APK (Always-Active)

The Android APK is located in [`app/`](app/).

### Why this Android app?
Standard mobile browsers (like Chrome) freeze background tabs, terminate WebSockets, and stop JavaScript timers within seconds of minimizing the app or locking the screen.

This APK solves that with:
1. **Android Foreground Service**: Keeps the process alive with high priority.
2. **Partial WakeLock**: Keeps the device CPU awake when the screen is locked.
3. **No WebView Sleep**: Omits `webView.onPause()` and `webView.pauseTimers()` so timers, audio, and background tasks run continuously.
4. **Google Sign-In Bypass**: Bypasses `403 disallowed_useragent` by providing a standard Mobile Chrome user-agent string.
5. **Auto-Granted Permissions**: Automatically grants microphone and WebRTC permissions required by Google AI Studio applets.
