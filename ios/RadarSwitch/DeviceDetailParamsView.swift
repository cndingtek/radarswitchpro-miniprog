import SwiftUI

struct DeviceDetailParamsView: View {
    @ObservedObject var viewModel: MainViewModel
    
    // Local state for editing
    @State private var isEditable: Bool = false
    @State private var localParams: DeviceParameters
    @State private var showToast = false
    @State private var toastMessage = ""
    @AppStorage("appLanguage") private var appLanguage = "en"
    private func l(_ key: String) -> String {
        if appLanguage == "zh-Hans" {
            switch key {
            case "Read-only Mode": return "只读模式"
            case "Locked": return "锁定"
            case "Editable": return "可编辑"
            case "Unlocked • Editable now": return "已解锁 • 现在可编辑"
            case "Locked • View only, cannot edit": return "已锁定 • 仅查看，不能编辑"
            case "Sensing Distance": return "感应距离"
            case "Far Distance": return "远距"
            case "Mid Distance": return "中距"
            case "Near Distance": return "近距"
            case "Delay": return "延时设置"
            case "Trigger Sensitivity": return "触发灵敏度"
            case "Entry Delay": return "进入延迟"
            case "Exit Delay": return "离开延时"
            case "Save": return "保存"
            case "Restore": return "恢复出厂"
            case "s": return "秒"
            case "Switched to Read-only Mode": return "已切换至只读模式"
            case "Switched to Editable Mode": return "已切换至编辑模式"
            case "Saved Successfully": return "保存成功"
            case "Restored Successfully": return "恢复成功"
            default: return key
            }
        }
        return key
    }
    
    init(viewModel: MainViewModel) {
        self.viewModel = viewModel
        _localParams = State(initialValue: viewModel.bleManager.deviceParams)
    }
    
    var body: some View {
        VStack(spacing: 16) {
            // Read-only Mode Card
            CardView {
                VStack(spacing: 12) {
                    HStack {
                        Text(l("Read-only Mode"))
                            .font(AppFonts.subheadline())
                            .foregroundColor(AppColors.textWhite)
                        Spacer()
                        
                        // Custom small toggle or segment
                        HStack(spacing: 0) {
                            Button(action: { 
                                isEditable = false 
                                viewModel.bleManager.readParameters() // Send AT+RESET when locking (refresh)
                            }) {
                                Text(l("Locked"))
                                    .font(AppFonts.caption())
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(!isEditable ? AppColors.primaryBlue : Color.clear)
                                    .foregroundColor(!isEditable ? .white : AppColors.textGray)
                                    .cornerRadius(8)
                            }
                            
                            Button(action: { 
                                isEditable = true 
                                // Handshake is handled via connection usually, but ensuring it's ready
                                viewModel.bleManager.startHandshake() 
                                showToastMessage(l("Switched to Editable Mode"))
                            }) { 
                                Text(l("Editable"))
                                    .font(AppFonts.caption())
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(isEditable ? AppColors.primaryBlue : Color.clear)
                                    .foregroundColor(isEditable ? .white : AppColors.textGray)
                                    .cornerRadius(8)
                            }
                        }
                        .padding(2)
                        .background(AppColors.deepBlue)
                        .cornerRadius(10)
                    }
                    
                    HStack {
                        Image(systemName: isEditable ? "lock.open.fill" : "lock.fill")
                            .foregroundColor(isEditable ? AppColors.primaryBlue : AppColors.textGray)
                        Text(isEditable ? l("Unlocked • Editable now") : l("Locked • View only, cannot edit"))
                            .font(AppFonts.caption())
                            .foregroundColor(isEditable ? AppColors.textWhite : AppColors.textGray)
                        Spacer()
                    }
                }
            }
            
            // Sensing Distance
            CardView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(l("Sensing Distance"))
                        .font(AppFonts.subheadline())
                        .foregroundColor(AppColors.textWhite)
                    
                    // Far Distance
                    CustomSliderRow(
                        title: l("Far Distance"), 
                        value: Binding(
                            get: { localParams.range3 },
                            set: { newVal in
                                localParams.range3 = newVal
                                // Constraint: Near <= Mid <= Far
                                if localParams.range2 > newVal { localParams.range2 = newVal }
                                if localParams.range1 > newVal { localParams.range1 = newVal }
                            }
                        ), 
                        range: 0...10, 
                        unit: "m", 
                        isEnabled: isEditable
                    )
                    
                    // Mid Distance
                    CustomSliderRow(
                        title: l("Mid Distance"), 
                        value: Binding(
                            get: { localParams.range2 },
                            set: { newVal in
                                // Constraint: Near <= Mid <= Far
                                var val = newVal
                                if val > localParams.range3 { val = localParams.range3 }
                                localParams.range2 = val
                                if localParams.range1 > val { localParams.range1 = val }
                            }
                        ), 
                        range: 0...10, 
                        unit: "m", 
                        isEnabled: isEditable
                    )
                    
                    // Near Distance
                    CustomSliderRow(
                        title: l("Near Distance"), 
                        value: Binding(
                            get: { localParams.range1 },
                            set: { newVal in
                                // Constraint: Near <= Mid <= Far
                                var val = newVal
                                if val > localParams.range2 { val = localParams.range2 }
                                localParams.range1 = val
                            }
                        ), 
                        range: 0...10, 
                        unit: "m", 
                        isEnabled: isEditable
                    )
                }
            }
            
            // Delay
            CardView {
                VStack(alignment: .leading, spacing: 16) {
                    Text(l("Delay"))
                        .font(AppFonts.subheadline())
                        .foregroundColor(AppColors.textWhite)
                    
                    // Trigger Sensitivity (Int 1-10)
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(l("Trigger Sensitivity"))
                                .font(AppFonts.body())
                                .foregroundColor(AppColors.textSecondary)
                            Spacer()
                            Text("\(localParams.sensitivity)")
                                .font(AppFonts.body())
                                .foregroundColor(isEditable ? AppColors.primaryBlue : AppColors.textGray)
                        }
                        Slider(value: Binding(
                            get: { Double(localParams.sensitivity) },
                            set: { localParams.sensitivity = Int($0) }
                        ), in: 1...10, step: 1)
                        .accentColor(isEditable ? AppColors.primaryBlue : AppColors.textGray)
                        .disabled(!isEditable)
                    }
                    
                    // Entry Delay (Int 1-20)
                    VStack(alignment: .leading, spacing: 8) {
                        HStack {
                            Text(l("Entry Delay"))
                                .font(AppFonts.body())
                                .foregroundColor(AppColors.textSecondary)
                            Spacer()
                            Text("\(localParams.enterDelay) " + l("s"))
                                .font(AppFonts.body())
                                .foregroundColor(isEditable ? AppColors.primaryBlue : AppColors.textGray)
                        }
                        Slider(value: Binding(
                            get: { Double(localParams.enterDelay) },
                            set: { localParams.enterDelay = Int($0) }
                        ), in: 1...20, step: 1)
                        .accentColor(isEditable ? AppColors.primaryBlue : AppColors.textGray)
                        .disabled(!isEditable)
                    }
                    
                    // Exit Delay (Stepper 1-999)
                    HStack {
                        Text(l("Exit Delay"))
                            .font(AppFonts.body())
                            .foregroundColor(AppColors.textSecondary)
                        Spacer()
                        
                        // Custom Stepper Appearance
                        HStack {
                            Button(action: { if localParams.exitDelay > 1 { localParams.exitDelay -= 1 } }) {
                                Image(systemName: "minus")
                                    .frame(width: 32, height: 32)
                                    .background(isEditable ? AppColors.deepBlue : Color.clear)
                                    .foregroundColor(isEditable ? .white : AppColors.textGray)
                                    .cornerRadius(8)
                            }
                            .disabled(!isEditable)
                            
                            Text("\(localParams.exitDelay) " + l("s"))
                                .font(AppFonts.body())
                                .foregroundColor(isEditable ? .white : AppColors.textGray)
                                .frame(minWidth: 60)
                                .multilineTextAlignment(.center)
                            
                            Button(action: { if localParams.exitDelay < 999 { localParams.exitDelay += 1 } }) {
                                Image(systemName: "plus")
                                    .frame(width: 32, height: 32)
                                    .background(isEditable ? AppColors.deepBlue : Color.clear)
                                    .foregroundColor(isEditable ? .white : AppColors.textGray)
                                    .cornerRadius(8)
                            }
                            .disabled(!isEditable)
                        }
                    }
                }
            }
            
            // Actions
            HStack(spacing: 16) {
                CustomButton(
                    title: l("Save"),
                    isPrimary: true,
                    isDisabled: !isEditable
                ) {
                    viewModel.bleManager.saveParameters(localParams) // Call BLE Manager directly
                    // Do NOT switch to locked automatically, let user stay in edit mode or verify result
                }
                
                CustomButton(
                    title: l("Restore"),
                    isPrimary: false,
                    isDisabled: !isEditable // Only clickable in Editable mode per requirement
                ) {
                    viewModel.bleManager.restoreDefaults()
                }
            }
        }
        .onReceive(viewModel.bleManager.$deviceParams) { newParams in
            print("UI: deviceParams updated SENS=\(newParams.sensitivity) Enter=\(newParams.enterDelay)s Exit=\(newParams.exitDelay)s R1=\(newParams.range1)m R2=\(newParams.range2)m R3=\(newParams.range3)m")
            localParams = newParams
        }
        .onReceive(viewModel.bleManager.$lastToast) { msg in
            if let m = msg {
                showToastMessage(l(m))
            }
        }
        .onAppear {
            viewModel.bleManager.readParameters() // Auto read on appear
            localParams = viewModel.bleManager.deviceParams
        }
        .toast(isShowing: $showToast, message: toastMessage)
    }
    
    private func showToastMessage(_ message: String) {
        toastMessage = message
        showToast = true
        DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
            showToast = false
        }
    }
}

struct CustomSliderRow: View {
    let title: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    let unit: String
    let isEnabled: Bool
    
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title)
                    .font(AppFonts.body())
                    .foregroundColor(AppColors.textSecondary)
                Spacer()
                Text(String(format: "%.1f %@", value, unit))
                    .font(AppFonts.body())
                    .foregroundColor(isEnabled ? AppColors.primaryBlue : AppColors.textGray)
            }
            
            Slider(value: $value, in: range)
                .accentColor(isEnabled ? AppColors.primaryBlue : AppColors.textGray)
                .disabled(!isEnabled)
        }
    }
}
