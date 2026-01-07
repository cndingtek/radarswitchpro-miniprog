import SwiftUI
#if canImport(UIKit)
import UIKit
#endif
#if canImport(Photos)
import Photos
#endif

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

// MARK: - Guide Notifications
extension Notification.Name {
    static let GuideNavigateTab = Notification.Name("GuideNavigateTab")
    static let GuideSwitchLanguage = Notification.Name("GuideSwitchLanguage")
    static let GuideReadParams = Notification.Name("GuideReadParams")
    static let GuideSelectDetailMode = Notification.Name("GuideSelectDetailMode")
    static let GuideToggleEditable = Notification.Name("GuideToggleEditable")
}

struct ThumbnailPreview<Content: View>: View {
    var imageName: String?
    var imagePath: String?
    var content: Content
    var size: CGSize = CGSize(width: 300, height: 180)
    
    init(imageName: String? = nil, imagePath: String? = nil, size: CGSize = CGSize(width: 300, height: 180), @ViewBuilder content: () -> Content) {
        self.imageName = imageName
        self.imagePath = imagePath
        self.size = size
        self.content = content()
    }
    // Convenience initializer for image-only usage (no content builder)
    init(imageName: String? = nil, imagePath: String? = nil, size: CGSize = CGSize(width: 300, height: 180)) where Content == EmptyView {
        self.imageName = imageName
        self.imagePath = imagePath
        self.size = size
        self.content = EmptyView()
    }
    
    var body: some View {
        Group {
            if let name = imageName {
                #if canImport(UIKit)
                if let uiImg = GuideImageProvider.image(named: name) {
                    Image(uiImage: uiImg)
                        .resizable()
                        .scaledToFill()
                        .frame(width: size.width, height: size.height)
                        .clipped()
                        .cornerRadius(12)
                } else {
                    Color.clear
                        .frame(width: size.width, height: size.height)
                        .cornerRadius(12)
                }
                #else
                Color.clear
                    .frame(width: size.width, height: size.height)
                    .cornerRadius(12)
                #endif
            } else if let path = imagePath {
                #if canImport(UIKit)
                if let uiImg = UIImage(contentsOfFile: path) {
                    Image(uiImage: uiImg)
                        .resizable()
                        .scaledToFill()
                        .frame(width: size.width, height: size.height)
                        .clipped()
                        .cornerRadius(12)
                } else {
                    Color.clear
                        .frame(width: size.width, height: size.height)
                        .cornerRadius(12)
                }
                #else
                Color.clear
                    .frame(width: size.width, height: size.height)
                    .cornerRadius(12)
                #endif
            } else {
                Color.clear
                    .frame(width: size.width, height: size.height)
                    .cornerRadius(12)
            }
        }
    }
}

#if canImport(UIKit)
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]
    let applicationActivities: [UIActivity]? = nil
    
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: applicationActivities)
    }
    
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}
#endif

struct QRImage: View {
    let imageName: String
    @State private var showShare = false
    @State private var showSavedAlert = false
    @AppStorage("appLanguage") private var appLanguage = "en"
    
    private var uiImage: UIImage? {
        #if canImport(UIKit)
        return UIImage(named: imageName)
        #else
        return nil
        #endif
    }
    
    private var saveLabel: String { appLanguage == "zh-Hans" ? "保存图片" : "Save Image" }
    private var shareLabel: String { appLanguage == "zh-Hans" ? "分享" : "Share" }
    private var savedOK: String { appLanguage == "zh-Hans" ? "已保存到照片" : "Saved to Photos" }
    private var savedFail: String { appLanguage == "zh-Hans" ? "保存失败" : "Save Failed" }
    
    var body: some View {
        Image(imageName)
            .resizable()
            .scaledToFit()
            .contextMenu {
                Button(saveLabel) {
                    saveToPhotos()
                }
                Button(shareLabel) {
                    #if canImport(UIKit)
                    showShare = true
                    #endif
                }
            }
            #if canImport(UIKit)
            .sheet(isPresented: $showShare) {
                if let img = uiImage {
                    ActivityView(items: [img])
                } else {
                    ActivityView(items: [])
                }
            }
            #endif
            .alert(isPresented: $showSavedAlert) {
                Alert(title: Text(savedOK))
            }
    }
    
    private func saveToPhotos() {
        #if canImport(UIKit)
        guard let img = uiImage else { return }
        #if canImport(Photos)
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            if status == .authorized || status == .limited {
                UIImageWriteToSavedPhotosAlbum(img, nil, nil, nil)
                DispatchQueue.main.async { showSavedAlert = true }
            } else {
                DispatchQueue.main.async { showSavedAlert = true }
            }
        }
        #else
        UIImageWriteToSavedPhotosAlbum(img, nil, nil, nil)
        DispatchQueue.main.async { showSavedAlert = true }
        #endif
        #endif
    }
}

#if canImport(UIKit)
final class GuideImageProvider {
    static func image(named: String) -> UIImage? {
        // Try language-specific asset names
        let lang = UserDefaults.standard.string(forKey: "appLanguage") ?? "en"
        let variants: [String] = {
            if lang == "zh-Hans" {
                return ["\(named)-zh", "\(named)_zh", "\(named)-cn", "\(named)_cn", named]
            } else {
                return ["\(named)-en", "\(named)_en", named]
            }
        }()
        for v in variants {
            if let asset = UIImage(named: v) { return asset }
        }
        return nil
    }
}
#endif
