import SwiftUI

struct OnboardingView: View {
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
        .background(AppColors.background.ignoresSafeArea())
    }
}
