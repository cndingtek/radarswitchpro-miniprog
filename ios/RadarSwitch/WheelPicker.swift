//
//  WheelPicker.swift
//  RadarSwitch
//
//  自定义滚轮选择器，用于延迟设置
//

import SwiftUI

struct WheelPicker: View {
    let title: String
    let range: ClosedRange<Int>
    let unit: String
    @Binding var selection: Int
    
    @State private var rotation: Double = 0
    @GestureState private var dragOffset: CGFloat = 0
    
    private let itemHeight: CGFloat = 50
    private let visibleItems = 5
    
    var body: some View {
        VStack(spacing: 10) {
            Text(title)
                .font(.headline)
                .foregroundColor(.primary)
            
            ZStack {
                // 背景
                RoundedRectangle(cornerRadius: 12)
                    .fill(Color(.systemGray6))
                    .frame(height: CGFloat(visibleItems) * itemHeight)
                
                // 选中项高亮
                RoundedRectangle(cornerRadius: 8)
                    .fill(Color.blue.opacity(0.2))
                    .stroke(Color.blue, lineWidth: 2)
                    .frame(height: itemHeight)
                    .offset(y: 0)
                
                // 滚轮项
                VStack(spacing: 0) {
                    ForEach(range.lowerBound...range.upperBound, id: \.self) { value in
                        WheelPickerItem(
                            value: value,
                            unit: unit,
                            isSelected: value == selection,
                            offset: calculateOffset(for: value)
                        )
                        .frame(height: itemHeight)
                    }
                }
                .offset(y: dragOffset + calculateSelectionOffset())
                .gesture(
                    DragGesture()
                        .updating($dragOffset) { value, state, _ in
                            state = value.translation.height
                        }
                        .onEnded { value in
                            let velocity = value.velocity.height
                            let predictedOffset = value.translation.height + (velocity * 0.1)
                            let itemOffset = Int(predictedOffset / itemHeight)
                            let newSelection = max(range.lowerBound, min(range.upperBound, selection - itemOffset))
                            
                            withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                                selection = newSelection
                            }
                        }
                )
            }
            .frame(height: CGFloat(visibleItems) * itemHeight)
            .clipped()
            
            // 数值显示
            HStack {
                Text("当前值:")
                    .font(.subheadline)
                    .foregroundColor(.secondary)
                
                Spacer()
                
                Text("\(selection)\(unit)")
                    .font(.title2)
                    .fontWeight(.semibold)
                    .foregroundColor(.blue)
            }
            .padding(.horizontal)
        }
        .padding()
        .background(Color(.systemBackground))
        .cornerRadius(16)
        .shadow(color: .black.opacity(0.1), radius: 4, x: 0, y: 2)
    }
    
    private func calculateOffset(for value: Int) -> CGFloat {
        let centerValue = (range.lowerBound + range.upperBound) / 2
        return CGFloat(value - centerValue) * itemHeight
    }
    
    private func calculateSelectionOffset() -> CGFloat {
        let centerValue = (range.lowerBound + range.upperBound) / 2
        return CGFloat(centerValue - selection) * itemHeight
    }
}

struct WheelPickerItem: View {
    let value: Int
    let unit: String
    let isSelected: Bool
    let offset: CGFloat
    
    private var opacity: Double {
        let distance = abs(offset) / 50
        return max(0.3, 1.0 - distance * 0.2)
    }
    
    private var scale: CGFloat {
        let distance = abs(offset) / 50
        return max(0.8, 1.0 - distance * 0.1)
    }
    
    var body: some View {
        HStack {
            Spacer()
            
            Text("\(value)\(unit)")
                .font(isSelected ? .title3 : .body)
                .fontWeight(isSelected ? .semibold : .regular)
                .foregroundColor(isSelected ? .blue : .primary)
                .scaleEffect(scale)
                .opacity(opacity)
            
            Spacer()
        }
        .frame(height: 50)
    }
}

// 自定义延迟设置视图
struct DelaySettingView: View {
    @Binding var enterDelay: Int
    @Binding var exitDelay: Int
    
    var body: some View {
        VStack(spacing: 20) {
            WheelPicker(
                title: "进入延迟",
                range: 1...30,
                unit: "秒",
                selection: $enterDelay
            )
            
            WheelPicker(
                title: "退出延迟",
                range: 1...30,
                unit: "秒",
                selection: $exitDelay
            )
            
            VStack(alignment: .leading, spacing: 8) {
                Text("设置说明")
                    .font(.headline)
                    .foregroundColor(.primary)
                
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Image(systemName: "arrow.right.circle.fill")
                            .foregroundColor(.green)
                        Text("进入延迟: 检测到目标后的响应时间")
                    }
                    
                    HStack {
                        Image(systemName: "arrow.left.circle.fill")
                            .foregroundColor(.orange)
                        Text("退出延迟: 目标离开后的保持时间")
                    }
                }
                .font(.caption)
                .foregroundColor(.secondary)
            }
            .padding()
            .background(Color(.systemGray6))
            .cornerRadius(12)
            
            Spacer()
        }
        .padding()
        .navigationTitle("延迟设置")
        .navigationBarTitleDisplayMode(.large)
    }
}

#Preview {
    DelaySettingView(
        enterDelay: .constant(5),
        exitDelay: .constant(10)
    )
}