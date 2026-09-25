import UIKit
import AVFoundation

@main
class AppDelegate: UIResponder, UIApplicationDelegate {

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
        // Prevent screen dimming / lock while dictating
        application.isIdleTimerDisabled = true
        
        // Configure audio session for continuous recording & background audio
        configureAudioSession()
        
        return true
    }

    func configureAudioSession() {
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(
                .playAndRecord,
                mode: .default,
                options: [.allowBluetooth, .defaultToSpeaker, .mixWithOthers]
            )
            try session.setActive(true, options: .notifyOthersOnDeactivation)
            print("[RADDOC] AVAudioSession configured successfully for background recording.")
        } catch {
            print("[RADDOC] Failed to configure AVAudioSession: \(error.localizedDescription)")
        }
    }

    func applicationWillResignActive(_ application: UIApplication) {
        // Keep audio session alive
        ensureAudioSessionActive()
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        ensureAudioSessionActive()
    }

    func applicationWillEnterForeground(_ application: UIApplication) {
        application.isIdleTimerDisabled = true
        ensureAudioSessionActive()
    }

    private func ensureAudioSessionActive() {
        do {
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            print("[RADDOC] Failed to reactivate audio session: \(error.localizedDescription)")
        }
    }

    // MARK: UISceneSession Lifecycle

    func application(_ application: UIApplication, configurationForConnecting connectingSceneSession: UISceneSession, options: UIScene.ConnectionOptions) -> UISceneConfiguration {
        return UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
    }

    func application(_ application: UIApplication, didDiscardSceneSessions sceneSessions: Set<UISceneSession>) {
    }
}
