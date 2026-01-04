import Foundation
import CoreBluetooth
import Combine

// MARK: - 数据模型

struct Device: Identifiable, Codable, Equatable {
    let id: UUID
    var name: String
    var macAddress: String
    var rssi: Int
    var isConnected: Bool
    var lastSeen: Date
    
    init(id: UUID = UUID(), name: String, macAddress: String, rssi: Int, isConnected: Bool = false, lastSeen: Date = Date()) {
        self.id = id
        self.name = name
        self.macAddress = macAddress
        self.rssi = rssi
        self.isConnected = isConnected
        self.lastSeen = lastSeen
    }
}

struct SavedDevice: Codable, Identifiable, Equatable {
    let id: UUID
    let name: String
    let macAddress: String
    var lastConnected: Date
    
    // For Equatable
    static func == (lhs: SavedDevice, rhs: SavedDevice) -> Bool {
        return lhs.id == rhs.id
    }
}

struct DeviceParameters: Codable, Equatable {
    var range1: Double  // 最小距离 (米)
    var range2: Double  // 中等距离 (米)
    var range3: Double  // 最大距离 (米)
    var sensitivity: Int
    var enterDelay: Int  // 进入延迟 (秒)
    var exitDelay: Int   // 离开延迟 (秒)
    var holdFrame: Int
    var trith: Int
    var fastTime: Int
    var slowTime: Int
    
    init() {
        self.range1 = 0
        self.range2 = 0
        self.range3 = 0
        self.sensitivity = 0
        self.enterDelay = 0
        self.exitDelay = 0
        self.holdFrame = 0
        self.trith = 0
        self.fastTime = 0
        self.slowTime = 0
    }
}

struct LogEntry: Identifiable, Codable {
    let id: UUID
    let timestamp: Date
    let type: LogType
    let message: String
    let distance: Double?
    let targetType: TargetType?
    
    init(id: UUID = UUID(), timestamp: Date = Date(), type: LogType, message: String, distance: Double? = nil, targetType: TargetType? = nil) {
        self.id = id
        self.timestamp = timestamp
        self.type = type
        self.message = message
        self.distance = distance
        self.targetType = targetType
    }
}

enum LogType: String, Codable, CaseIterable {
    case alarm = "报警"
    case noAlarm = "无报警"
    case system = "系统"
    case parameter = "参数"
    
    var color: String {
        switch self {
        case .alarm: return "red"
        case .noAlarm: return "green"
        case .system: return "blue"
        case .parameter: return "orange"
        }
    }
}

enum TargetType: String, Codable {
    case moving = "运动目标"
    case microMotion = "微动目标"
    
    var description: String {
        switch self {
        case .moving: return "运动目标"
        case .microMotion: return "微动目标"
        }
    }
}

struct DistanceSample: Identifiable, Codable {
    let id: UUID
    let time: Date
    let value: Int
    
    init(id: UUID = UUID(), time: Date, value: Int) {
        self.id = id
        self.time = time
        self.value = value
    }
}

// MARK: - 视图模型

class MainViewModel: ObservableObject {
    @Published var devices: [Device] = []
    @Published var logs: [LogEntry] = []
    @Published var parameters: DeviceParameters?
    @Published var isLoading = false
    @Published var errorMessage: String?
    @Published var selectedDevice: Device?
    @Published var savedDevices: [SavedDevice] = []
    
    private var cancellables = Set<AnyCancellable>()
    var bleManager: BLEManager
    
    init(bleManager: BLEManager = BLEManager()) {
        self.bleManager = bleManager
        loadSavedDevices()
        setupBindings()
    }
    
    func updateBLEManager(_ manager: BLEManager) {
        self.bleManager = manager
        setupBindings()
    }
    
    private func setupBindings() {
        // 绑定BLE管理器的状态
        bleManager.$discoveredDevices
            .receive(on: DispatchQueue.main)
            .sink { [weak self] devices in
                self?.devices = devices
                // Sync selectedDevice state
                if let selectedId = self?.selectedDevice?.id,
                   let updatedDevice = devices.first(where: { $0.id == selectedId }) {
                    self?.selectedDevice = updatedDevice
                    if updatedDevice.isConnected {
                        self?.saveDevice(updatedDevice)
                    }
                }
            }
            .store(in: &cancellables)
        
        // 绑定设备参数，保持全局参数一致
        bleManager.$deviceParams
            .receive(on: DispatchQueue.main)
            .sink { [weak self] params in
                self?.parameters = params
            }
            .store(in: &cancellables)
    }
    
    func loadSavedDevices() {
        if let data = UserDefaults.standard.data(forKey: "SavedDevices"),
           let decoded = try? JSONDecoder().decode([SavedDevice].self, from: data) {
            savedDevices = decoded
        }
    }
    
    func saveDevice(_ device: Device) {
        if !savedDevices.contains(where: { $0.id == device.id }) {
            let saved = SavedDevice(id: device.id, name: device.name, macAddress: device.macAddress, lastConnected: Date())
            savedDevices.append(saved)
            persistSavedDevices()
        } else {
            // Update last connected time
            if let index = savedDevices.firstIndex(where: { $0.id == device.id }) {
                var saved = savedDevices[index]
                saved.lastConnected = Date()
                savedDevices[index] = saved
                persistSavedDevices()
            }
        }
    }
    
    func removeSavedDevice(_ device: SavedDevice) {
        savedDevices.removeAll(where: { $0.id == device.id })
        persistSavedDevices()
        if let current = selectedDevice, current.id == device.id {
            bleManager.disconnectFromDevice(current)
            selectedDevice = nil
            parameters = nil
        }
    }
    
    private func persistSavedDevices() {
        if let encoded = try? JSONEncoder().encode(savedDevices) {
            UserDefaults.standard.set(encoded, forKey: "SavedDevices")
        }
    }
    
    func scanForDevices() {
        bleManager.scanForDevices()
    }
    
    func connectToDevice(_ device: Device) {
        bleManager.connectToDevice(device)
        selectedDevice = device
    }
    
    func disconnectFromDevice(_ device: Device) {
        bleManager.disconnectFromDevice(device)
        if selectedDevice?.id == device.id {
            selectedDevice = nil
            parameters = nil
        }
    }
    
    func loadParameters() {
        guard selectedDevice != nil else { return }
        isLoading = true
        bleManager.readParameters()
        
        // 模拟延迟
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            self.parameters = DeviceParameters()
            self.isLoading = false
            
            self.logs.append(LogEntry(
                type: .parameter,
                message: "参数读取成功"
            ))
        }
    }
    
    func saveParameters(_ parameters: DeviceParameters) {
        guard selectedDevice != nil else { return }
        isLoading = true
        
        // 根据进入延迟范围设置不同的命令（占位：具体参数映射在 BLEManager 内部完成）
        
        bleManager.saveParameters(parameters)
        
        // 模拟延迟
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
            self.parameters = parameters
            self.isLoading = false
            
            self.logs.append(LogEntry(
                type: .parameter,
                message: "参数保存成功"
            ))
        }
    }
    
    func restoreDefaults() {
        guard selectedDevice != nil else { return }
        isLoading = true
        bleManager.restoreDefaults()
        
        // 模拟延迟
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) {
            self.parameters = DeviceParameters()
            self.isLoading = false
            
            self.logs.append(LogEntry(
                type: .parameter,
                message: "恢复出厂设置成功"
            ))
        }
    }
    
    func loadLogs(limit: Int = 20) {
        // 模拟日志数据
        let mockLogs = [
            LogEntry(type: .alarm, message: "检测到运动目标", distance: 2.3, targetType: .moving),
            LogEntry(type: .noAlarm, message: "未检测到目标"),
            LogEntry(type: .alarm, message: "检测到微动目标", distance: 1.8, targetType: .microMotion),
            LogEntry(type: .system, message: "设备连接成功"),
            LogEntry(type: .parameter, message: "参数更新成功")
        ]
        
        logs = Array(mockLogs.prefix(limit))
    }
}

class ParametersViewModel: ObservableObject {
    @Published var parameters: DeviceParameters?
    @Published var isReadOnly = true
    @Published var isEditing = false
    @Published var editedParameters: DeviceParameters?
    
    func toggleEditMode() {
        isEditing.toggle()
        if isEditing {
            editedParameters = parameters
        }
    }
    
    func saveChanges() {
        if let edited = editedParameters {
            parameters = edited
        }
        isEditing = false
    }
    
    func discardChanges() {
        editedParameters = parameters
        isEditing = false
    }
}

// MARK: - 扩展

extension Device {
    var signalStrength: String {
        switch rssi {
        case -50...0:
            return "强"
        case -70..<(-50):
            return "中"
        case -90..<(-70):
            return "弱"
        default:
            return "极弱"
        }
    }
    
    var signalColor: String {
        switch rssi {
        case -50...0:
            return "green"
        case -70..<(-50):
            return "yellow"
        case -90..<(-70):
            return "orange"
        default:
            return "red"
        }
    }
}
