import SwiftUI

struct DeviceDetailView: View {
    @ObservedObject var viewModel: MainViewModel
    @State private var selectedMode: Int = 0 // 0: Params, 1: Logs
    @AppStorage("appLanguage") private var appLanguage = "en"
    private func l(_ key: String) -> String {
        if appLanguage == "zh-Hans" {
            switch key {
            case "Params": return "参数"
            case "Logs & Monitor": return "日志与监控"
            case "No Device Selected": return "未选择设备"
            case "Please select a device from the list.": return "请从列表中选择设备。"
            default: return key
            }
        }
        return key
    }
    
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                if let device = viewModel.selectedDevice {
                    // Device Info Card
                    CardView {
                        VStack(spacing: 12) {
                            HStack {
                                Text(device.name)
                                    .font(AppFonts.titleMedium())
                                    .foregroundColor(AppColors.textWhite)
                                
                                StatusBadge(isOnline: device.isConnected)
                                
                                Spacer()
                                
                                Text("1/1") // Placeholder index
                                    .font(AppFonts.caption())
                                    .foregroundColor(AppColors.textGray)
                            }
                            
                            HStack {
                                Text("UUID: \(device.macAddress)")
                                    .font(AppFonts.caption())
                                    .foregroundColor(AppColors.textGray)
                                Spacer()
                            }
                        }
                    }
                    
                    // Mode Toggle
                    ToggleCapsule(
                        options: [l("Params"), l("Logs & Monitor")],
                        selectedIndex: $selectedMode
                    )
                    
                    // Content
                    if selectedMode == 0 {
                        DeviceDetailParamsView(viewModel: viewModel)
                    } else {
                        DeviceDetailLogsView(viewModel: viewModel)
                    }
                } else {
                    // No device selected state
                    VStack(spacing: 20) {
                        Image(systemName: "waveform.path.ecg")
                            .font(.system(size: 60))
                            .foregroundColor(AppColors.textGray)
                        Text(l("No Device Selected"))
                            .font(AppFonts.titleMedium())
                            .foregroundColor(AppColors.textWhite)
                        Text(l("Please select a device from the list."))
                            .font(AppFonts.body())
                            .foregroundColor(AppColors.textGray)
                    }
                    .padding(.top, 60)
                }
            }
            .padding(16)
        }
        .onReceive(NotificationCenter.default.publisher(for: .GuideSelectDetailMode)) { notif in
            if let mode = notif.userInfo?["mode"] as? Int {
                selectedMode = mode
            }
        }
    }
}
