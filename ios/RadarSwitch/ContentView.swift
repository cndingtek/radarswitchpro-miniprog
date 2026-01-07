import SwiftUI
import CoreBluetooth

struct ContentView: View {
    @EnvironmentObject var bleManager: BLEManager
    @StateObject private var viewModel = MainViewModel()
    @State private var selectedTab = 0
    
    var body: some View {
        ZStack {
            // Global Background
            AppColors.mainBackground
                .ignoresSafeArea()
            
            VStack(spacing: 0) {
                // Top Navigation
                TopNavigationBar()
                
                // Segmented Selector
                TopTabSelector(selectedTab: $selectedTab)
                    .padding(.top, 8)
                    .padding(.bottom, 8)
                
                // Content Area
                TabView(selection: $selectedTab) {
                    BluetoothScanView(viewModel: viewModel)
                        .tag(0)
                    
                    DeviceListView(viewModel: viewModel)
                        .tag(1)
                    
                    DeviceDetailView(viewModel: viewModel)
                        .tag(2)
                    
                    SettingsView()
                        .tag(3)
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .animation(.easeInOut, value: selectedTab)
            }
        }
        .onAppear {
            viewModel.updateBLEManager(bleManager)
            bleManager.requestBluetoothPermission()
            bleManager.setDataProcessingEnabled(false)
        }
        .onChange(of: selectedTab) { _, newTab in
            if newTab != 0 {
                bleManager.stopScanning()
            }
            // Enable data processing only on Device Detail tab
            bleManager.setDataProcessingEnabled(newTab == 2)
        }
        .onChange(of: viewModel.selectedDevice) { oldDevice, newDevice in
            if let device = newDevice, device.isConnected {
                selectedTab = 2
                bleManager.setDataProcessingEnabled(true)
            } else if newDevice == nil {
                bleManager.setDataProcessingEnabled(false)
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .GuideNavigateTab)) { notif in
            if let idx = notif.userInfo?["index"] as? Int {
                selectedTab = idx
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .GuideSwitchLanguage)) { notif in
            if let lang = notif.userInfo?["lang"] as? String {
                UserDefaults.standard.set(lang, forKey: "appLanguage")
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .GuideReadParams)) { _ in
            bleManager.readParameters()
        }
        .preferredColorScheme(.dark) // Force dark mode
    }
}
