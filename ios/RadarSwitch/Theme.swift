import SwiftUI

struct AppColors {
    // Backgrounds
    static let mainBackground = LinearGradient(
        gradient: Gradient(colors: [Color(hex: "050F24"), Color(hex: "071A33")]),
        startPoint: .top,
        endPoint: .bottom
    )
    static let cardBackground = Color(hex: "0C2343")
    static let deepBlue = Color(hex: "050F24")
    
    // Accents
    static let primaryBlue = Color(hex: "2D7BFF")
    static let primaryBluePressed = Color(hex: "1A60D6")
    static let disabledBlue = Color(hex: "4C5876")
    static let disabledButtonBackground = Color(hex: "2C354C")
    
    // Text
    static let textWhite = Color.white
    static let textSecondary = Color(hex: "9BA7C8")
    static let textGray = Color(hex: "7A8196")
    
    // Status
    static let statusOnline = Color(hex: "38C976")
    static let statusOffline = Color(hex: "7A8196")
    
    // Charts
    static let chartGrid = Color.gray.opacity(0.2)
    static let chartLine = Color(hex: "2D7BFF")
}

extension Color {
    init(hex: String) {
        let hex = hex.trimmingCharacters(in: CharacterSet.alphanumerics.inverted)
        var int: UInt64 = 0
        Scanner(string: hex).scanHexInt64(&int)
        let a, r, g, b: UInt64
        switch hex.count {
        case 3: // RGB (12-bit)
            (a, r, g, b) = (255, (int >> 8) * 17, (int >> 4 & 0xF) * 17, (int & 0xF) * 17)
        case 6: // RGB (24-bit)
            (a, r, g, b) = (255, int >> 16, int >> 8 & 0xFF, int & 0xFF)
        case 8: // ARGB (32-bit)
            (a, r, g, b) = (int >> 24, int >> 16 & 0xFF, int >> 8 & 0xFF, int & 0xFF)
        default:
            (a, r, g, b) = (1, 1, 1, 0)
        }

        self.init(
            .sRGB,
            red: Double(r) / 255,
            green: Double(g) / 255,
            blue: Double(b) / 255,
            opacity: Double(a) / 255
        )
    }
}

struct AppFonts {
    static func titleLarge() -> Font {
        .system(size: 20, weight: .semibold, design: .default)
    }
    
    static func titleMedium() -> Font {
        .system(size: 18, weight: .semibold, design: .default)
    }
    
    static func subheadline() -> Font {
        .system(size: 16, weight: .semibold, design: .default)
    }
    
    static func body() -> Font {
        .system(size: 14, weight: .regular, design: .default)
    }
    
    static func caption() -> Font {
        .system(size: 12, weight: .regular, design: .default)
    }
}
