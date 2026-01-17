import SwiftUI

struct SettingsView: View {
    // Use AppStorage for persistence
    @AppStorage("chartTimeIndex") private var chartTimeIndex = 0
    @AppStorage("logLimitIndex") private var logLimitIndex = 1
    @AppStorage("appLanguage") private var appLanguage = "en"
    @State private var languageIndexState: Int = 0
    @State private var showOnboarding: Bool = false
    @Environment(\.openURL) private var openURL
    private var appVersion: String { Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "—" }
    private var versionLabel: String { appLanguage == "zh-Hans" ? "版本：\(appVersion)" : "Version: \(appVersion)" }
    
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
            "App Guide": "软件使用教程",
            "Radar Switch Installation": "硬件安装说明",
            "About": "关于",
            "Version: 1.1.1": "版本：1.1.1",
            "Developer: Shenzhen Dingtek IoT Technology Corp.,Ltd.": "开发者：深圳鼎恒泰物联科技有限公司",
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
                        
                        HStack(spacing: 8) {
                            SmallActionCapsule(title: localized("App Guide"), isPrimary: true) {
                                showOnboarding = true
                            }
                            SmallActionCapsule(title: appLanguage == "zh-Hans" ? localized("Radar Switch Installation") : "Radar Install", isPrimary: false) {
                                let urlStr = appLanguage == "zh-Hans"
                                    ? "https://dify.dingtek.com/fb/api/public/dl/VflW1WYe?inline=true"
                                    : "https://dify.dingtek.com/fb/api/public/dl/LY_ZQhum?inline=true"
                                if let url = URL(string: urlStr) {
                                    openURL(url)
                                }
                            }
                        }
                        .padding(2)
                        .background(AppColors.deepBlue)
                        .cornerRadius(18)
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
                            Text(versionLabel)
                            Text(localized("Developer: Shenzhen Dingtek IoT Technology Corp.,Ltd."))
                            HStack(spacing: 4) {
                                Text(appLanguage == "zh-Hans" ? "网站：" : "Website: ")
                                    .foregroundColor(AppColors.textSecondary)
                                Link(appLanguage == "zh-Hans" ? "www.dingtek.com.cn" : "www.dingtek.com",
                                     destination: URL(string: appLanguage == "zh-Hans" ? "https://www.dingtek.com.cn" : "https://www.dingtek.com")!)
                                    .foregroundColor(AppColors.primaryBlue)
                                    .underline(true)
                            }
                            HStack(spacing: 4) {
                                Text(appLanguage == "zh-Hans" ? "邮箱：" : "Email: ")
                                    .foregroundColor(AppColors.textSecondary)
                                Link("service@dingtek.com", destination: URL(string: "mailto:service@dingtek.com")!)
                                    .foregroundColor(AppColors.primaryBlue)
                                    .underline(true)
                            }
                        }
                        .font(AppFonts.caption())
                        .foregroundColor(AppColors.textSecondary)
                        
                        VStack(alignment: .leading, spacing: 8) {
                            if appLanguage == "zh-Hans" {
                                Text("微信客服")
                                    .font(AppFonts.caption())
                                    .foregroundColor(AppColors.textSecondary)
                                QRImage(imageName: "wechat")
                                    .frame(width: 160, height: 160)
                            } else {
                                Text("Whatsapp")
                                    .font(AppFonts.caption())
                                    .foregroundColor(AppColors.textSecondary)
                                QRImage(imageName: "whatsapp")
                                    .frame(width: 160, height: 160)
                            }
                        }
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
            case "Scan Devices": return "扫描设备"
            case "Open Scan tab, click Start to scan nearby devices.": return "打开扫描标签，点击开始扫描附近设备。"
            case "Connect & Pair": return "连接与配对"
            case "Pick a device to connect, optionally add to My Devices.": return "选择设备连接，可加入“我的设备”。"
            case "Logs & Monitor": return "日志与监控"
            case "Open Logs tab to view status and distance charts.": return "打开日志标签查看状态与距离曲线。"
            case "Read Parameters": return "读取参数"
            case "On Parameters tab, tap Read-only to fetch parameters.": return "在参数标签，点击只读以读取参数。"
            case "Edit & Save": return "编辑与保存"
            case "Switch to Editable, adjust values, tap Save to write.": return "切换至可编辑，调整参数并点击保存写入。"
            case "Language": return "语言"
            case "Toggle app language between Chinese and English.": return "在中文与英文之间切换应用语言。"
            case "Get Started": return "开始体验"
            default: return key
            }
        }
        return key
    }
    
    var body: some View {
        VStack {
            TabView {
                VStack(spacing: 12) {
                    Text(l("Scan Devices"))
                        .font(AppFonts.titleMedium())
                        .foregroundColor(AppColors.textWhite)
                    ThumbnailPreview(imageName: "scan_start") {
                        CardView {
                            VStack(alignment: .leading, spacing: 8) {
                                Text(l("Bluetooth Scan"))
                                    .font(AppFonts.subheadline())
                                    .foregroundColor(AppColors.textWhite)
                                HStack {
                                    Text(l("Search nearby radar switch devices"))
                                        .font(AppFonts.caption())
                                        .foregroundColor(AppColors.textGray)
                                    Spacer()
                                    StatusBadge(isOnline: false)
                                }
                            }
                        }
                        .frame(width: 300, height: 180)
                    }
                    Text(l("Open Scan tab, click Start to scan nearby devices."))
                        .font(AppFonts.caption())
                        .foregroundColor(AppColors.textSecondary)
                        .multilineTextAlignment(.center)
                    SmallActionCapsule(title: appLanguage == "zh-Hans" ? "前往扫描" : "Go to Scan", isPrimary: true) {
                        NotificationCenter.default.post(name: .GuideNavigateTab, object: nil, userInfo: ["index": 0])
                        dismiss()
                    }
                }
                .padding()
                .background(AppColors.cardBackground)
                .cornerRadius(20)
                
                VStack(spacing: 12) {
                    Text(l("Connect & Pair"))
                        .font(AppFonts.titleMedium())
                        .foregroundColor(AppColors.textWhite)
                    ThumbnailPreview(imageName: "devices_list") {
                        CardView {
                            VStack(alignment: .leading, spacing: 8) {
                                Text(appLanguage == "zh-Hans" ? "我的设备" : "My Devices")
                                    .font(AppFonts.subheadline())
                                    .foregroundColor(AppColors.textWhite)
                                Text("UUID: XXXXX • Last 21:05:18")
                                    .font(AppFonts.caption())
                                    .foregroundColor(AppColors.textGray)
                            }
                        }
                        .frame(width: 300, height: 180)
                    }
                    Text(l("Pick a device to connect, optionally add to My Devices."))
                        .font(AppFonts.caption())
                        .foregroundColor(AppColors.textSecondary)
                        .multilineTextAlignment(.center)
                    SmallActionCapsule(title: appLanguage == "zh-Hans" ? "前往设备" : "Go to Devices") {
                        NotificationCenter.default.post(name: .GuideNavigateTab, object: nil, userInfo: ["index": 1])
                        dismiss()
                    }
                }
                .padding()
                .background(AppColors.cardBackground)
                .cornerRadius(20)
                
                VStack(spacing: 12) {
                    Text(l("Logs & Monitor"))
                        .font(AppFonts.titleMedium())
                        .foregroundColor(AppColors.textWhite)
                    ThumbnailPreview(imageName: "logs") {
                        CardView {
                            VStack(alignment: .leading, spacing: 8) {
                                Text(l("Logs & Monitor"))
                                    .font(AppFonts.subheadline())
                                    .foregroundColor(AppColors.textWhite)
                                Text("[21:05:18] 1, 92cm, ON")
                                    .font(.system(size: 10, design: .monospaced))
                                    .foregroundColor(AppColors.textSecondary)
                                Text("[21:05:19] 无目标")
                                    .font(.system(size: 10, design: .monospaced))
                                    .foregroundColor(AppColors.textSecondary)
                            }
                        }
                        .frame(width: 300, height: 180)
                    }
                    Text(l("Open Logs tab to view status and distance charts."))
                        .font(AppFonts.caption())
                        .foregroundColor(AppColors.textSecondary)
                        .multilineTextAlignment(.center)
                    SmallActionCapsule(title: appLanguage == "zh-Hans" ? "前往日志" : "Go to Logs") {
                        NotificationCenter.default.post(name: .GuideNavigateTab, object: nil, userInfo: ["index": 2])
                        NotificationCenter.default.post(name: .GuideSelectDetailMode, object: nil, userInfo: ["mode": 1])
                        dismiss()
                    }
                }
                .padding()
                .background(AppColors.cardBackground)
                .cornerRadius(20)
                
                VStack(spacing: 12) {
                    Text(l("Read Parameters"))
                        .font(AppFonts.titleMedium())
                        .foregroundColor(AppColors.textWhite)
                    ThumbnailPreview(imageName: "params_locked") {
                        CardView {
                            VStack(alignment: .leading, spacing: 8) {
                                Text(appLanguage == "zh-Hans" ? "参数" : "Params")
                                    .font(AppFonts.subheadline())
                                    .foregroundColor(AppColors.textWhite)
                                Text(appLanguage == "zh-Hans" ? "远距/中距/近距与延迟" : "Far/Mid/Near & Delay")
                                    .font(AppFonts.caption())
                                    .foregroundColor(AppColors.textGray)
                            }
                        }
                        .frame(width: 300, height: 180)
                    }
                    Text(l("On Parameters tab, tap Read-only to fetch parameters."))
                        .font(AppFonts.caption())
                        .foregroundColor(AppColors.textSecondary)
                        .multilineTextAlignment(.center)
                    SmallActionCapsule(title: appLanguage == "zh-Hans" ? "读取参数" : "Read Params", isPrimary: true) {
                        NotificationCenter.default.post(name: .GuideNavigateTab, object: nil, userInfo: ["index": 2])
                        NotificationCenter.default.post(name: .GuideSelectDetailMode, object: nil, userInfo: ["mode": 0])
                        NotificationCenter.default.post(name: .GuideReadParams, object: nil)
                        dismiss()
                    }
                }
                .padding()
                .background(AppColors.cardBackground)
                .cornerRadius(20)
                
                VStack(spacing: 12) {
                    Text(l("Edit & Save"))
                        .font(AppFonts.titleMedium())
                        .foregroundColor(AppColors.textWhite)
                    ThumbnailPreview(imageName: "params_edit")
                        .frame(height: 180)
                    Text(l("Switch to Editable, adjust values, tap Save to write."))
                        .font(AppFonts.caption())
                        .foregroundColor(AppColors.textSecondary)
                        .multilineTextAlignment(.center)
                    SmallActionCapsule(title: appLanguage == "zh-Hans" ? "切换可编辑" : "Editable") {
                        NotificationCenter.default.post(name: .GuideNavigateTab, object: nil, userInfo: ["index": 2])
                        NotificationCenter.default.post(name: .GuideSelectDetailMode, object: nil, userInfo: ["mode": 0])
                        NotificationCenter.default.post(name: .GuideToggleEditable, object: nil)
                        dismiss()
                    }
                }
                .padding()
                .background(AppColors.cardBackground)
                .cornerRadius(20)
                
                VStack(spacing: 12) {
                    Text(l("Language"))
                        .font(AppFonts.titleMedium())
                        .foregroundColor(AppColors.textWhite)
                    ThumbnailPreview(imageName: "settings_language")
                        .frame(height: 180)
                    Text(l("Toggle app language between Chinese and English."))
                        .font(AppFonts.caption())
                        .foregroundColor(AppColors.textSecondary)
                        .multilineTextAlignment(.center)
                    HStack(spacing: 8) {
                        SmallActionCapsule(title: "中文", isPrimary: true) {
                            NotificationCenter.default.post(name: .GuideSwitchLanguage, object: nil, userInfo: ["lang": "zh-Hans"])
                            dismiss()
                        }
                        SmallActionCapsule(title: "English") {
                            NotificationCenter.default.post(name: .GuideSwitchLanguage, object: nil, userInfo: ["lang": "en"])
                            dismiss()
                        }
                    }
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
