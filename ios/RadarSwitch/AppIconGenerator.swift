//
//  AppIconGenerator.swift
//  RadarSwitch
//
//  简单的应用图标生成器（用于开发阶段）
//

import SwiftUI

struct AppIconGenerator {
    static func generateAppIcon() -> UIImage {
        let size = CGSize(width: 1024, height: 1024)
        let renderer = UIGraphicsImageRenderer(size: size)
        
        return renderer.image { context in
            // 背景渐变
            let colors = [
                UIColor.systemBlue.cgColor,
                UIColor.systemPurple.cgColor
            ]
            let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors as CFArray, locations: [0, 1])!
            
            context.cgContext.drawLinearGradient(
                gradient,
                start: CGPoint(x: 0, y: 0),
                end: CGPoint(x: size.width, y: size.height),
                options: []
            )
            
            // 雷达图标
            let iconSize = CGSize(width: 600, height: 600)
            let iconRect = CGRect(
                x: (size.width - iconSize.width) / 2,
                y: (size.height - iconSize.height) / 2,
                width: iconSize.width,
                height: iconSize.height
            )
            
            // 绘制雷达圆圈
            let path = UIBezierPath()
            let center = CGPoint(x: iconRect.midX, y: iconRect.midY)
            
            // 外圆
            path.addArc(withCenter: center, radius: iconSize.width / 2, startAngle: 0, endAngle: 2 * .pi, clockwise: true)
            
            // 内圆
            path.addArc(withCenter: center, radius: iconSize.width / 4, startAngle: 0, endAngle: 2 * .pi, clockwise: true)
            
            // 扇形扫描区域
            let scanPath = UIBezierPath()
            scanPath.move(to: center)
            scanPath.addArc(withCenter: center, radius: iconSize.width / 2, startAngle: -.pi/2, endAngle: .pi/6, clockwise: true)
            scanPath.close()
            
            // 设置颜色
            UIColor.white.setStroke()
            UIColor.white.withAlphaComponent(0.3).setFill()
            
            // 绘制
            path.lineWidth = 8
            path.stroke()
            scanPath.fill()
            
            // 中心点
            let centerPath = UIBezierPath()
            centerPath.addArc(withCenter: center, radius: 20, startAngle: 0, endAngle: 2 * .pi, clockwise: true)
            UIColor.white.setFill()
            centerPath.fill()
            
            // 扫描线
            let linePath = UIBezierPath()
            linePath.move(to: center)
            linePath.addLine(to: CGPoint(x: center.x, y: center.y - iconSize.width / 2))
            linePath.lineWidth = 6
            linePath.lineCapStyle = .round
            linePath.stroke()
        }
    }
    
    static func saveAppIcon() {
        let icon = generateAppIcon()
        
        // 保存不同尺寸
        let sizes = [16, 32, 64, 128, 256, 512, 1024]
        
        for size in sizes {
            let scaledSize = CGSize(width: size, height: size)
            let scaledIcon = UIGraphicsImageRenderer(size: scaledSize).image { _ in
                icon.draw(in: CGRect(origin: .zero, size: scaledSize))
            }
            
            if let data = scaledIcon.pngData() {
                let filename = "\(size).png"
                let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
                let fileURL = documentsPath.appendingPathComponent(filename)
                
                try? data.write(to: fileURL)
                print("保存图标: \(filename)")
            }
        }
    }
}

#Preview {
    Image(uiImage: AppIconGenerator.generateAppIcon())
        .resizable()
        .frame(width: 200, height: 200)
        .cornerRadius(40)
}