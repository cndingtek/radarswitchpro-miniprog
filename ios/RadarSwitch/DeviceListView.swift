import SwiftUI

struct DeviceListView: View {
    @ObservedObject var viewModel: MainViewModel
    @AppStorage("appLanguage") private var appLanguage = "en"
    private func l(_ key: String) -> String {
        if appLanguage == "zh-Hans" {
            switch key {
            case "My Devices": return "我的设备"
            case "No paired devices": return "没有已配对设备"
            default: return key
            }
        }
        return key
    }
    
    var body: some View {
        ScrollView {
            VStack(spacing: 16) {
                // Title Card
                CardView {
                    HStack {
                        Text(l("My Devices"))
                        .font(AppFonts.titleMedium())
                        .foregroundColor(AppColors.textWhite)
                        Spacer()
                    }
                }
                
                if viewModel.savedDevices.isEmpty {
                    VStack(spacing: 20) {
                        Image(systemName: "antenna.radiowaves.left.and.right.slash")
                            .font(.system(size: 40))
                            .foregroundColor(AppColors.textGray)
                        Text(l("No paired devices"))
                            .font(AppFonts.body())
                            .foregroundColor(AppColors.textGray)
                    }
                    .padding(.top, 40)
                } else {
                    ForEach(viewModel.savedDevices) { saved in
                        // Pass isOnline status by checking if it's in connectedDevices or has isConnected=true in discovered
                        let isOnline = viewModel.devices.first(where: { $0.id == saved.id })?.isConnected ?? false
                        
                        DeviceListRow(device: saved, isOnline: isOnline, onDelete: {
                            viewModel.removeSavedDevice(saved)
                        })
                        .onTapGesture {
                            // Find current Device object if available (for connection)
                            // or create a temporary one for connection attempt
                            let deviceToConnect = viewModel.devices.first(where: { $0.id == saved.id }) 
                                ?? Device(id: saved.id, name: saved.name, macAddress: saved.macAddress, rssi: 0, isConnected: false)
                            viewModel.connectToDevice(deviceToConnect)
                        }
                    }
                }
            }
            .padding(16)
        }
    }
}

struct DeviceListRow: View {
    let device: SavedDevice
    let isOnline: Bool
    let onDelete: () -> Void
    @AppStorage("appLanguage") private var appLanguage = "en"
    
    var body: some View {
        CardView {
            HStack {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(device.name)
                            .font(AppFonts.subheadline())
                            .foregroundColor(AppColors.textWhite)
                        
                        StatusBadge(isOnline: isOnline)
                    }
                    
                    Text("UUID: \(device.macAddress) • Last \(formatDate(device.lastConnected))")
                        .font(AppFonts.caption())
                        .foregroundColor(AppColors.textGray)
                }
                
                Spacer()
                
                Button(action: onDelete) {
                    Image(systemName: "trash")
                        .foregroundColor(AppColors.textGray)
                        .padding(10)
                        .background(Color.white.opacity(0.1))
                        .clipShape(Circle())
                }
            }
        }
    }
    
    private func formatDate(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: date)
    }
}
