import SwiftUI
import Charts

struct DeviceDetailLogsView: View {
    @ObservedObject var viewModel: MainViewModel
    @State private var logs: [String] = []
    @State private var distanceData: [ChartDataPoint] = []
    @AppStorage("chartTimeIndex") private var chartTimeIndex = 0
    @AppStorage("logLimitIndex") private var logLimitIndex = 1
    @AppStorage("appLanguage") private var appLanguage = "en"
    private func l(_ key: String) -> String {
        if appLanguage == "zh-Hans" {
            switch key {
            case "Logs & Monitor": return "日志与监控"
            case "Target Distance (cm)": return "目标距离（厘米）"
            case "Time(s)": return "时间(秒)"
            case "Distance": return "距离"
            default: return key
            }
        }
        return key
    }
    
    private var chartSeconds: Int {
        [60, 180, 300][min(max(chartTimeIndex, 0), 2)]
    }
    private var logLimit: Int {
        [5, 10, 20][min(max(logLimitIndex, 0), 2)]
    }
    
    var body: some View {
        VStack(spacing: 16) {
            // Logs Card
            CardView {
                VStack(alignment: .leading, spacing: 12) {
                    HStack {
                        Text(l("Logs & Monitor"))
                            .font(AppFonts.subheadline())
                            .foregroundColor(AppColors.textWhite)
                        Spacer()
                    }
                    
                    if let device = viewModel.selectedDevice {
                        HStack {
                            Text("\(device.name)")
                                .font(AppFonts.caption())
                                .foregroundColor(AppColors.textGray)
                            Spacer()
                            StatusBadge(isOnline: device.isConnected)
                        }
                    }
                    
                    // Logs ScrollView
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 4) {
                            ForEach(logs.suffix(logLimit).reversed(), id: \.self) { log in
                                Text(log)
                                    .font(.system(size: 10, design: .monospaced))
                                    .foregroundColor(AppColors.textSecondary)
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                        }
                    }
                    .frame(height: 150)
                    .background(Color.black.opacity(0.2))
                    .cornerRadius(8)
                }
            }
            
            // Chart Card
            CardView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(l("Target Distance (cm)"))
                        .font(AppFonts.subheadline())
                        .foregroundColor(AppColors.textWhite)
                    
                    Chart {
                        ForEach(distanceData) { point in
                            LineMark(
                                x: .value(l("Time(s)"), point.time),
                                y: .value(l("Distance"), point.value)
                            )
                            .foregroundStyle(AppColors.chartLine)
                            .interpolationMethod(.catmullRom)
                            
                            AreaMark(
                                x: .value(l("Time(s)"), point.time),
                                y: .value(l("Distance"), point.value)
                            )
                            .foregroundStyle(
                                LinearGradient(
                                    colors: [AppColors.primaryBlue.opacity(0.3), AppColors.primaryBlue.opacity(0.0)],
                                    startPoint: .top,
                                    endPoint: .bottom
                                )
                            )
                        }
                    }
                    .chartYScale(domain: 0...800) // 0-800cm
                    .chartXScale(domain: 0...Double(chartSeconds))
                    .chartXAxis {
                        AxisMarks(values: .automatic(desiredCount: 6)) {
                            AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5, dash: [4, 4]))
                                .foregroundStyle(AppColors.chartGrid)
                            AxisValueLabel()
                                .foregroundStyle(AppColors.textGray)
                        }
                    }
                    .chartYAxis {
                        AxisMarks(values: .automatic(desiredCount: 5)) {
                            AxisGridLine(stroke: StrokeStyle(lineWidth: 0.5, dash: [4, 4]))
                                .foregroundStyle(AppColors.chartGrid)
                            AxisValueLabel()
                                .foregroundStyle(AppColors.textGray)
                        }
                    }
                    .frame(height: 200)
                }
            }
        }
        .onReceive(viewModel.bleManager.$logs) { newLogs in
            self.logs = newLogs
        }
        .onReceive(viewModel.bleManager.$distanceSeries) { series in
            let now = Date()
            let cutoff = now.addingTimeInterval(-Double(chartSeconds))
            let filtered = series.filter { $0.time >= cutoff }
            self.distanceData = filtered.map { sample in
                let t = Double(chartSeconds) - now.timeIntervalSince(sample.time)
                return ChartDataPoint(time: max(0, min(Double(chartSeconds), t)), value: Double(sample.value))
            }
        }
    }
}

struct ChartDataPoint: Identifiable {
    let id = UUID()
    let time: Double
    let value: Double
}
