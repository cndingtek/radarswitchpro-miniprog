import SwiftUI

// MARK: - Card Component
struct CardView<Content: View>: View {
    var content: Content
    
    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }
    
    var body: some View {
        content
            .padding(16)
            .background(AppColors.cardBackground)
            .cornerRadius(20)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Top Navigation Bar
struct TopNavigationBar: View {
    @AppStorage("appLanguage") private var appLanguage = "en"
    var title: String = "RadarSwitch Pro"
    var subtitle: String = "Radar Switch Smart Controller"
    
    private func l(_ key: String) -> String {
        if appLanguage == "zh-Hans" {
            switch key {
            case "RadarSwitch Pro": return "雷达开关专业版"
            case "Radar Switch Smart Controller": return "雷达开关智能控制器"
            default: return key
            }
        }
        return key
    }
    
    var body: some View {
        HStack(spacing: 12) {
            // App Icon Placeholder
            ZStack {
                Circle()
                .fill(AppColors.primaryBlue)
                .frame(width: 48, height: 48)
                
                Image(systemName: "waveform.path.ecg")
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(width: 24, height: 24)
                    .foregroundColor(.white)
            }
            
            VStack(alignment: .leading, spacing: 2) {
                Text(l(title))
                    .font(AppFonts.titleLarge())
                    .foregroundColor(AppColors.textWhite)
                
                Text(l(subtitle))
                    .font(AppFonts.caption())
                    .foregroundColor(AppColors.textSecondary)
            }
            
            Spacer()
        }
        .padding()
        // Removed card styling to make it part of window background
        // .background(AppColors.cardBackground)
        // .cornerRadius(20)
        .padding(.horizontal, 16)
        .padding(.top, 0) // Adjust based on SafeArea in parent
    }
}

// MARK: - Top Segmented Tab Selector
struct TopTabSelector: View {
    @Binding var selectedTab: Int
    
    let tabs = [
        "bluetooth", // Bluetooth Scan
        "list.bullet", // Device List
        "waveform.path.ecg", // Device Detail
        "gearshape" // Settings
    ]
    
    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<tabs.count, id: \.self) { index in
                Button(action: {
                    withAnimation {
                        selectedTab = index
                    }
                }) {
                    ZStack {
                        if selectedTab == index {
                            RoundedRectangle(cornerRadius: 12)
                                .fill(AppColors.primaryBlue)
                                .matchedGeometryEffect(id: "TabBackground", in: namespace)
                        }
                        
                        Group {
                            if index == 0 {
                                SystemSymbolImage(primary: "bluetooth", fallback: "antenna.radiowaves.left.and.right")
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundColor(selectedTab == index ? .white : AppColors.textGray)
                                    .padding(.vertical, 6)
                            } else {
                                Image(systemName: tabs[index])
                                    .font(.system(size: 16, weight: .semibold))
                                    .foregroundColor(selectedTab == index ? .white : AppColors.textGray)
                                    .padding(.vertical, 6)
                            }
                        }
                    }
                }
                .frame(maxWidth: .infinity)
            }
        }
        .padding(2) // Reduced outer padding
        .frame(height: 44) // Explicit height to match scan header
        .background(AppColors.cardBackground)
        .cornerRadius(16) // Reduced radius
        .padding(.horizontal, 16)
    }
    
    @Namespace private var namespace
}

// MARK: - Custom Button
struct CustomButton: View {
    var title: String
    var icon: String? = nil
    var isPrimary: Bool = true
    var isDisabled: Bool = false
    var action: () -> Void
    
    var body: some View {
        Button(action: action) {
            HStack {
                if let icon = icon {
                    Image(systemName: icon)
                }
                Text(title)
                    .font(AppFonts.subheadline())
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 14)
            .background(
                Group {
                    if isDisabled {
                        AppColors.disabledButtonBackground
                    } else if isPrimary {
                        AppColors.primaryBlue
                    } else {
                        Color.clear
                    }
                }
            )
            .overlay(
                RoundedRectangle(cornerRadius: 20)
                    .stroke(isPrimary || isDisabled ? Color.clear : AppColors.primaryBlue, lineWidth: 1.5)
            )
            .cornerRadius(20)
            .foregroundColor(isDisabled ? AppColors.textGray : (isPrimary ? .white : AppColors.textWhite))
            .shadow(color: isPrimary && !isDisabled ? AppColors.primaryBlue.opacity(0.3) : .clear, radius: 8, x: 0, y: 4)
        }
        .disabled(isDisabled)
    }
}

// Compact capsule-styled action button (matches ToggleCapsule style)
struct SmallActionCapsule: View {
    var title: String
    var isPrimary: Bool = false
    var action: () -> Void
    
    var body: some View {
        Button(action: action) {
            Text(title)
                .font(AppFonts.caption())
                .fontWeight(.semibold)
                .padding(.vertical, 8)
                .padding(.horizontal, 16)
                .background(isPrimary ? AppColors.primaryBlue : Color.clear)
                .foregroundColor(isPrimary ? .white : AppColors.textSecondary)
                .overlay(
                    RoundedRectangle(cornerRadius: 16)
                        .stroke(AppColors.primaryBlue, lineWidth: isPrimary ? 0 : 1)
                )
                .cornerRadius(16)
        }
    }
}

// MARK: - Status Badge
struct StatusBadge: View {
    var isOnline: Bool
    @AppStorage("appLanguage") private var appLanguage = "en"
    private func l(_ key: String) -> String {
        if appLanguage == "zh-Hans" {
            switch key {
            case "Online": return "在线"
            case "Offline": return "离线"
            default: return key
            }
        }
        return key
    }
    
    var body: some View {
        Text(isOnline ? l("Online") : l("Offline"))
            .font(.system(size: 10, weight: .bold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(isOnline ? AppColors.statusOnline : AppColors.statusOffline)
            .foregroundColor(.white)
            .cornerRadius(10)
    }
}

// Runtime-checked SF Symbol with fallback
struct SystemSymbolImage: View {
    let primary: String
    let fallback: String
    
    var body: some View {
        #if canImport(UIKit)
        if let uiImage = UIImage(systemName: primary) {
            Image(uiImage: uiImage)
        } else {
            Image(systemName: fallback)
        }
        #else
        Image(systemName: fallback)
        #endif
    }
}
// MARK: - Toggle Capsule
struct ToggleCapsule: View {
    let options: [String]
    @Binding var selectedIndex: Int
    
    var body: some View {
        HStack(spacing: 0) {
            ForEach(0..<options.count, id: \.self) { index in
                Button(action: {
                    withAnimation {
                        selectedIndex = index
                    }
                }) {
                    Text(options[index])
                        .font(AppFonts.caption())
                        .fontWeight(.semibold)
                        .padding(.vertical, 8)
                        .padding(.horizontal, 16)
                        .frame(maxWidth: .infinity)
                        .background(selectedIndex == index ? AppColors.primaryBlue : Color.clear)
                        .foregroundColor(selectedIndex == index ? .white : AppColors.textSecondary)
                        .overlay(
                            RoundedRectangle(cornerRadius: 16)
                                .stroke(AppColors.primaryBlue, lineWidth: selectedIndex == index ? 0 : 1)
                        )
                        .cornerRadius(16)
                }
            }
        }
        .padding(2)
        .background(AppColors.deepBlue)
        .cornerRadius(18)
    }
}

// MARK: - Toast Component
struct ToastView: View {
    let message: String
    
    var body: some View {
        Text(message)
            .font(AppFonts.body())
            .foregroundColor(.white)
            .padding(.horizontal, 20)
            .padding(.vertical, 10)
            .background(Color.black.opacity(0.8))
            .cornerRadius(25)
            .shadow(radius: 5)
    }
}

struct ToastModifier: ViewModifier {
    @Binding var isShowing: Bool
    let message: String
    
    func body(content: Content) -> some View {
        ZStack {
            content
            
            if isShowing {
                VStack {
                    Spacer()
                    ToastView(message: message)
                        .padding(.bottom, 50)
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                }
                .zIndex(1)
            }
        }
        .animation(.spring(), value: isShowing)
    }
}

extension View {
    func toast(isShowing: Binding<Bool>, message: String) -> some View {
        self.modifier(ToastModifier(isShowing: isShowing, message: message))
    }
}
