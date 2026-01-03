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
        }
        .onChange(of: viewModel.selectedDevice) { oldDevice, newDevice in
            if let device = newDevice, device.isConnected {
                selectedTab = 2 // Navigate to Device Detail
            }
        }
        .preferredColorScheme(.dark) // Force dark mode
    }
}
