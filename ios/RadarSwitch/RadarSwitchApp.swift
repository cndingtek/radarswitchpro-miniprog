import SwiftUI

@main
struct RadarSwitchApp: App {
    @StateObject private var bleManager = BLEManager()
    @AppStorage("appLanguage") private var appLanguage = "en"
    init() {
        let key = "appLanguage"
        if UserDefaults.standard.string(forKey: key) == nil {
            let preferred = Locale.preferredLanguages.first?.lowercased() ?? "en"
            let chosen: String
            if preferred.contains("zh") {
                chosen = "zh-Hans"
            } else if preferred.contains("en") {
                chosen = "en"
            } else {
                chosen = "en"
            }
            UserDefaults.standard.set(chosen, forKey: key)
            appLanguage = chosen
        }
    }
    
    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(bleManager)
                .environment(\.locale, Locale(identifier: appLanguage))
        }
    }
}
