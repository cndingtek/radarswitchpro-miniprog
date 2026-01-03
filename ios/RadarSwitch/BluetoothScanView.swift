import SwiftUI

struct BluetoothScanView: View {
    @ObservedObject var viewModel: MainViewModel
    @State private var isScanning = false
    @AppStorage("appLanguage") private var appLanguage = "en"
    private func l(_ key: String) -> String {
        if appLanguage == "zh-Hans" {
            switch key {
            case "Bluetooth Scan": return "蓝牙扫描"
            case "Scanning...": return "扫描中..."
            case "Stopped": return "已停止"
            case "Start": return "开始"
            case "Stop": return "停止"
            case "Search nearby radar switch devices": return "搜索附近的雷达开关设备"
            case "Connected": return "已连接"
            case "Connect": return "连接"
            case "RSSI:": return "RSSI："
            case "Protocol: BLE": return "协议：BLE"
            default: return key
            }
        }
        return key
    }
    
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // Header Card
                CardView {
                    VStack(alignment: .leading, spacing: 12) {
                        HStack {
                            Text(l("Bluetooth Scan"))
                                .font(AppFonts.titleMedium())
                                .foregroundColor(AppColors.textWhite)
                            
                            Spacer()
                            
                            Text(isScanning ? l("Scanning...") : l("Stopped"))
                                .font(AppFonts.caption())
                                .foregroundColor(AppColors.textGray)
                            
                            Button(action: {
                                isScanning.toggle()
                                if isScanning {
                                    viewModel.scanForDevices()
                                }
                            }) {
                                Text(isScanning ? l("Stop") : l("Start"))
                                    .font(AppFonts.caption())
                                    .fontWeight(.bold)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 6)
                                    .background(AppColors.primaryBlue)
                                    .foregroundColor(.white)
                                    .cornerRadius(12)
                            }
                        }
                        
                        Text(l("Search nearby radar switch devices"))
                            .font(AppFonts.caption())
                            .foregroundColor(AppColors.textGray)
                    }
                }
                
                // Device List
                LazyVStack(spacing: 12) {
                    ForEach(viewModel.devices) { device in
                        ScanDeviceRow(device: device, onConnect: {
                            viewModel.connectToDevice(device)
                        })
                    }
                }
            }
            .padding(16)
        }
        .onAppear {
            // Auto start scan? Maybe not, let user decide or respect state
        }
    }
}

struct ScanDeviceRow: View {
    let device: Device
    let onConnect: () -> Void
    @AppStorage("appLanguage") private var appLanguage = "en"
    private func l(_ key: String) -> String {
        if appLanguage == "zh-Hans" {
            switch key {
            case "Connected": return "已连接"
            case "Connect": return "连接"
            case "RSSI:": return "RSSI："
            case "Protocol: BLE": return "协议：BLE"
            default: return key
            }
        }
        return key
    }
    
    var body: some View {
        CardView {
            HStack {
                VStack(alignment: .leading, spacing: 6) {
                    Text(device.name)
                        .font(AppFonts.subheadline())
                        .foregroundColor(AppColors.textWhite)
                    
                    Text("UUID: \(device.macAddress)")
                        .font(.system(size: 10)) // Smaller font for long UUID
                        .foregroundColor(AppColors.textGray)
                        .lineLimit(1)
                        .truncationMode(.middle)
                    
                    Text(l("RSSI:") + " \(device.rssi) • " + l("Protocol: BLE"))
                        .font(AppFonts.caption())
                        .foregroundColor(AppColors.textGray)
                }
                
                Spacer()
                
                Button(action: onConnect) {
                    if device.isConnected {
                        Text(l("Connected"))
                            .font(AppFonts.caption())
                            .fontWeight(.bold)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(AppColors.primaryBlue)
                            .foregroundColor(.white)
                            .cornerRadius(16)
                    } else {
                        Text(l("Connect"))
                            .font(AppFonts.caption())
                            .fontWeight(.bold)
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(Color.clear)
                            .foregroundColor(AppColors.primaryBlue)
                            .overlay(
                                RoundedRectangle(cornerRadius: 16)
                                    .stroke(AppColors.primaryBlue, lineWidth: 1)
                            )
                    }
                }
                .disabled(device.isConnected)
            }
        }
    }
}
