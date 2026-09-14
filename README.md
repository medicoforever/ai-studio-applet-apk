# AI Studio Applet Android APK (Always-Active)

Android APK wrapper built specifically for:
`https://ai.studio/apps/3f0807e3-2494-4289-a3a6-c12032da731c?fullscreenApplet=true`

## Why this app?
Standard mobile browsers (like Chrome) freeze background tabs, terminate WebSockets, and stop JavaScript timers within seconds of minimizing the app or locking the screen.

This APK solves that with:
1. **Android Foreground Service**: Keeps the process alive with high priority.
2. **Partial WakeLock**: Keeps the device CPU awake when the screen is locked.
3. **No WebView Sleep**: Omits `webView.onPause()` and `webView.pauseTimers()` so timers, audio, and background tasks run continuously.
4. **Google Sign-In Bypass**: Bypasses `403 disallowed_useragent` by providing a standard Mobile Chrome user-agent string.
5. **Auto-Granted Permissions**: Automatically grants microphone and WebRTC permissions required by Google AI Studio applets.

## Download APK
Visit the [Releases](https://github.com/medicoforever/ai-studio-applet-apk/releases) tab to download the pre-compiled APK.
