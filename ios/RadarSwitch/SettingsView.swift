import SwiftUI

struct SettingsView: View {
    // Use AppStorage for persistence
    @AppStorage("chartTimeIndex") private var chartTimeIndex = 0
    @AppStorage("logLimitIndex") private var logLimitIndex = 1
    @AppStorage("appLanguage") private var appLanguage = "en"
    @State private var languageIndexState: Int = 0
    @State private var showOnboarding: Bool = false
    
    private func localized(_ key: String) -> String {
        let zh: [String: String] = [
            "Settings": "设置",
            "App Settings": "应用设置",
            "Language": "语言",
            "English": "英语",
            "Chinese": "中文",
            "Chart Time Window": "图表时间窗口",
            "Log Count Limit": "日志条数上限",
            "Help & Tutorial": "帮助与教程",
            "We recommend reading the tutorial first.": "建议先阅读教程。",
            "View Tutorial Again": "再次查看教程",
            "About": "关于",
            "Version: 1.1.1": "版本：1.1.1",
            "Developer: Shenzhen Dingtek IoT Technology Corp.,Ltd.": "开发者：深圳鼎阳物联科技股份有限公司",
            "Website: www.dingtek.com": "网站：www.dingtek.com",
            "Email: service@dingtek.com": "邮箱：service@dingtek.com"
        ]
        if appLanguage == "zh-Hans" { return zh[key] ?? key }
        return key
    }
    
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // Header
                CardView {
                    HStack {
                        Text(localized("Settings"))
                        .font(AppFonts.titleMedium())
                        .foregroundColor(AppColors.textWhite)
                        Spacer()
                    }
                }
                
                // App Settings
                CardView {
                    VStack(alignment: .leading, spacing: 20) {
                        Text(localized("App Settings"))
                        .font(AppFonts.subheadline())
                        .foregroundColor(AppColors.textWhite)
                        
                        // Language
                        VStack(alignment: .leading, spacing: 8) {
                            Text(localized("Language"))
                                .font(AppFonts.caption())
                                .foregroundColor(AppColors.textSecondary)
                            
                            Picker(selection: Binding(
                                get: { languageIndexState },
                                set: { idx in
                                    languageIndexState = idx
                                    appLanguage = (idx == 1) ? "zh-Hans" : "en"
                                }
                            ), label: HStack {
                                Text(languageIndexState == 1 ? localized("Chinese") : localized("English"))
                                    .font(AppFonts.caption())
                                    .foregroundColor(AppColors.textSecondary)
                            }) {
                                Text(localized("English")).tag(0)
                                Text(localized("Chinese")).tag(1)
                            }
                            .pickerStyle(.menu)
                        }
                        
                        // Chart Time Window
                        VStack(alignment: .leading, spacing: 8) {
                            Text(localized("Chart Time Window"))
                                .font(AppFonts.caption())
                                .foregroundColor(AppColors.textSecondary)
                            
                            ToggleCapsule(
                                options: ["60s", "180s", "300s"],
                                selectedIndex: $chartTimeIndex
                            )
                        }
                        
                        // Log Count Limit
                        VStack(alignment: .leading, spacing: 8) {
                            Text(localized("Log Count Limit"))
                                .font(AppFonts.caption())
                                .foregroundColor(AppColors.textSecondary)
                            
                            ToggleCapsule(
                                options: ["5", "10", "20"],
                                selectedIndex: $logLimitIndex
                            )
                        }
                    }
                }
                
                // Help & Tutorial
                CardView {
                    VStack(alignment: .leading, spacing: 12) {
                        Text(localized("Help & Tutorial"))
                            .font(AppFonts.subheadline())
                            .foregroundColor(AppColors.textWhite)
                        
                        Text(localized("We recommend reading the tutorial first."))
                            .font(AppFonts.caption())
                            .foregroundColor(AppColors.textSecondary)
                        
                        CustomButton(title: localized("View Tutorial Again"), isPrimary: true) {
                            showOnboarding = true
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                
                // Firmware Update removed
                
                // About
                CardView {
                    VStack(alignment: .leading, spacing: 8) {
                        Text(localized("About"))
                            .font(AppFonts.subheadline())
                            .foregroundColor(AppColors.textWhite)
                            .padding(.bottom, 4)
                        
                        Group {
                            Text("RadarSwitch Pro")
                                .fontWeight(.bold)
                            Text(localized("Version: 1.1.1"))
                            Text(localized("Developer: Shenzhen Dingtek IoT Technology Corp.,Ltd."))
                            Text(localized("Website: www.dingtek.com"))
                            Text(localized("Email: service@dingtek.com"))
                        }
                        .font(AppFonts.caption())
                        .foregroundColor(AppColors.textSecondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(16)
        }
        .sheet(isPresented: $showOnboarding) {
            SettingsOnboardingView()
        }
        .onAppear {
            languageIndexState = (appLanguage == "zh-Hans") ? 1 : 0
        }
    }
}

struct SettingsOnboardingView: View {
    @AppStorage("appLanguage") private var appLanguage = "en"
    @Environment(\.dismiss) private var dismiss
    
    private func l(_ key: String) -> String {
        if appLanguage == "zh-Hans" {
            switch key {
            case "Welcome": return "欢迎使用"
            case "Connect your device to start monitoring.": return "连接设备开始监控。"
            case "Monitoring": return "监控"
            case "View distance charts and event logs in real time.": return "实时查看距离曲线与事件日志。"
            case "Settings": return "设置"
            case "Adjust sensing ranges, delays and language preferences.": return "调整感应范围、延迟与语言偏好。"
            case "Get Started": return "开始体验"
            default: return key
            }
        }
        return key
    }
    
    var body: some View {
        VStack {
            TabView {
                VStack(spacing: 16) {
                    Image(systemName: "antenna.radiowaves.left.and.right")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 72, height: 72)
                        .foregroundColor(AppColors.primaryBlue)
                    Text(l("Welcome"))
                        .font(AppFonts.titleMedium())
                        .foregroundColor(AppColors.textWhite)
                    Text(l("Connect your device to start monitoring."))
                        .font(AppFonts.body())
                        .foregroundColor(AppColors.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .padding()
                .background(AppColors.cardBackground)
                .cornerRadius(20)
                
                VStack(spacing: 16) {
                    Image(systemName: "waveform.path.ecg")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 72, height: 72)
                        .foregroundColor(AppColors.primaryBlue)
                    Text(l("Monitoring"))
                        .font(AppFonts.titleMedium())
                        .foregroundColor(AppColors.textWhite)
                    Text(l("View distance charts and event logs in real time."))
                        .font(AppFonts.body())
                        .foregroundColor(AppColors.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .padding()
                .background(AppColors.cardBackground)
                .cornerRadius(20)
                
                VStack(spacing: 16) {
                    Image(systemName: "gearshape")
                        .resizable()
                        .scaledToFit()
                        .frame(width: 72, height: 72)
                        .foregroundColor(AppColors.primaryBlue)
                    Text(l("Settings"))
                        .font(AppFonts.titleMedium())
                        .foregroundColor(AppColors.textWhite)
                    Text(l("Adjust sensing ranges, delays and language preferences."))
                        .font(AppFonts.body())
                        .foregroundColor(AppColors.textSecondary)
                        .multilineTextAlignment(.center)
                }
                .padding()
                .background(AppColors.cardBackground)
                .cornerRadius(20)
            }
            .tabViewStyle(.page)
            .indexViewStyle(.page(backgroundDisplayMode: .always))
            .frame(maxWidth: .infinity, maxHeight: 380)
            .padding()
            
            CustomButton(title: l("Get Started"), isPrimary: true) {
                dismiss()
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
        }
        .background(AppColors.mainBackground.ignoresSafeArea())
    }
}
