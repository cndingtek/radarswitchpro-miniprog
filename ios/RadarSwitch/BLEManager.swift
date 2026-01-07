import Foundation
import CoreBluetooth
import Combine

class BLEManager: NSObject, ObservableObject, CBCentralManagerDelegate, CBPeripheralDelegate {
    @Published var isScanning = false
    @Published var discoveredDevices: [Device] = []
    @Published var connectedDevices: [CBPeripheral] = []
    @Published var bluetoothState: CBManagerState = .unknown
    
    @Published var deviceParams: DeviceParameters = DeviceParameters()
    @Published var logs: [String] = []
    @Published var distanceHistory: [Int] = [] // Store distance values
    @Published var distanceSeries: [DistanceSample] = []
    @Published var lastToast: String?
    enum HardwareType { case unknown, modelA, modelB }
    @Published var hardwareType: HardwareType = .unknown
    
    private var centralManager: CBCentralManager!
    private var connectedPeripheral: CBPeripheral?
    private var writeCharacteristic: CBCharacteristic?
    private var notifyCharacteristic: CBCharacteristic?
    private var discoveredPeripherals: [UUID: CBPeripheral] = [:]
    
    // Protocol State
    private var handshakeCompleted = false
    private var pendingCommands: [String] = []
    private var isSending = false
    private var isRunningMode = false
    private var isInHandshake = false
    private var pendingRangeKey: Int? // 1,2,3 indicates which RangeX waiting for Received
    private var lineBuffer: String = ""
    private var lastSentWasAA = false
    private var pendingRangeValue: Double?
    private var rangeSegmentBuffer: String = ""
    private var paramCaptureActive: Bool = false
    private var paramCaptureBuffer: String = ""
    private var commandTimeoutWork: DispatchWorkItem?
    private var lastSentCommand: String = ""
    private var pendingBinaryCommands: [Data] = []
    private var lastAParamId: UInt16?
    
    private enum OperationType { case none, read, save, restore }
    private var currentOperation: OperationType = .none
    private var bypassHandshake: Bool = false
    private var dataProcessingEnabled: Bool = true
    
    // Raw RX state (only set when actually received from device)
    private var rxFastTime: Int?
    private var rxSlowTime: Int?
    private var rxTrith: Int?
    private var rxHoldFrame: Int?
    private var retryCounts: [String: Int] = [:]
    
    // BLE UUIDs
    // Many SPP modules use FFF0 service.
    // Common: FFF1 (Notify/Read), FFF2 (Write) OR FFF1 (Read/Write/Notify)
    private let serviceUUID = CBUUID(string: "0000FFF0-0000-1000-8000-00805F9B34FB")
    // We will discover all characteristics, so we don't strictly need to hardcode the characteristic UUIDs for discovery.
    // However, keeping them for reference or specific filtering if needed.
    
    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }
    
    private func handleBinaryA(_ data: Data) {
        if data.count < 12 { return }
        let bytes = [UInt8](data)
        let cmdLo = bytes[6]
        let cmdHi = bytes[7]
        let statusLo = bytes[8]
        let statusHi = bytes[9]
        if statusLo != 0x00 || statusHi != 0x00 { return }
        if cmdLo == 0x08 && cmdHi == 0x01 {
            let val = Int(bytes[10])
            if let pid = lastAParamId {
                switch pid {
                case 0x0001:
                    let meters = Double(val) * 0.75
                    var p = deviceParams; p.range3 = meters
                    DispatchQueue.main.async { [weak self] in self?.deviceParams = p }
                case 0x0000:
                    let meters = Double(val) * 0.75
                    var p = deviceParams; p.range1 = meters
                    DispatchQueue.main.async { [weak self] in self?.deviceParams = p }
                case 0x0004:
                    var p = deviceParams; p.exitDelay = val
                    DispatchQueue.main.async { [weak self] in self?.deviceParams = p }
                default: break
                }
            }
            isSending = false
            commandTimeoutWork?.cancel(); commandTimeoutWork = nil
            lastAParamId = nil
            processQueue()
        }
    }
    // MARK: - Public Methods
    
    func requestBluetoothPermission() {
        // iOS handles permission automatically
    }
    
    func setDataProcessingEnabled(_ enabled: Bool) {
        dataProcessingEnabled = enabled
    }
    
    func scanForDevices() {
        #if targetEnvironment(simulator)
        if isScanning {
            isScanning = false
            discoveredDevices = []
            return
        }
        discoveredDevices.removeAll()
        discoveredPeripherals.removeAll()
        isScanning = true
        let id1 = UUID()
        let id2 = UUID()
        let id3 = UUID()
        let id4 = UUID()
        let id5 = UUID()
        let mock1 = Device(id: id1, name: "CNDingtek", macAddress: id1.uuidString, rssi: -45, isConnected: false)
        let mock2 = Device(id: id2, name: "DC590", macAddress: id2.uuidString, rssi: -62, isConnected: false)
        let mock3 = Device(id: id3, name: "DC591", macAddress: id3.uuidString, rssi: -68, isConnected: false)
        let mock4 = Device(id: id4, name: "DC591Plus", macAddress: id4.uuidString, rssi: -75, isConnected: false)
        let mock5 = Device(id: id5, name: "DC590Plus", macAddress: id5.uuidString, rssi: -82, isConnected: false)
        discoveredDevices = [mock1, mock2, mock3, mock4, mock5]
        return
        #endif

        guard centralManager.state == .poweredOn else { return }
        if isScanning {
            centralManager.stopScan()
            isScanning = false
            return
        }
        discoveredDevices.removeAll()
        discoveredPeripherals.removeAll()
        centralManager.scanForPeripherals(withServices: nil, options: nil)
        isScanning = true
    }
    
    func stopScanning() {
        #if !targetEnvironment(simulator)
        centralManager.stopScan()
        #endif
        isScanning = false
    }
    
    func connectToDevice(_ device: Device) {
        // Mock connection for Simulator
        #if targetEnvironment(simulator)
        print("SIMULATOR: Connecting to mock device \(device.name)...")
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.5, qos: .userInitiated, flags: [], execute: { [weak self] in
            guard let self = self else { return }
            // Mark as connected in list
            if let index = self.discoveredDevices.firstIndex(where: { $0.id == device.id }) {
                var updated = self.discoveredDevices[index]
                updated.isConnected = true
                self.discoveredDevices[index] = updated
            }
            
            // Mock logs and params
            self.appendLog("[SIM] Connected to \(device.name)")
            self.appendLog("[SIM] Service Discovery: OK")
            self.appendLog("[SIM] Characteristic: Write/Notify OK")
            
            // Simulate some initial data
            var simParams = DeviceParameters()
            simParams.range1 = 2.5
            simParams.range2 = 1.0
            simParams.range3 = 4.5
            simParams.enterDelay = 5
            simParams.exitDelay = 30
            simParams.sensitivity = 75
            self.deviceParams = simParams
            
            // Start a timer to generate fake logs
            Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { _ in
                let dist = Int.random(in: 50...400)
                let status = dist < 200 ? "ON" : "OFF"
                self.appendLog("[\(self.formatTime())] 1, \(dist)cm, \(status)")
                self.appendDistance(dist)
            }
        })
        return
        #endif
        
        if let peripheral = discoveredPeripherals[device.id] {
            centralManager.connect(peripheral, options: nil)
            return
        }
        
        if let peripheral = centralManager.retrievePeripherals(withIdentifiers: [device.id]).first {
            centralManager.connect(peripheral, options: nil)
        }
    }
    
    func disconnectFromDevice(_ device: Device) {
        if let peripheral = centralManager.retrievePeripherals(withIdentifiers: [device.id]).first {
            centralManager.cancelPeripheralConnection(peripheral)
        }
    }
    
    // MARK: - Protocol Logic
    
    func startHandshake() {
        handshakeCompleted = false
        isInHandshake = true
        sendRaw("AA\r\n")
        lastSentWasAA = true
    }
    
    func readParameters() {
        currentOperation = .read
        if hardwareType == .modelA {
            pendingBinaryCommands.removeAll()
            pendingBinaryCommands.append(Data([0xFD,0xFC,0xFB,0xFA,0x04,0x00,0x08,0x00,0x01,0x00,0x04,0x03,0x02,0x01]))
            pendingBinaryCommands.append(Data([0xFD,0xFC,0xFB,0xFA,0x04,0x00,0x08,0x00,0x00,0x00,0x04,0x03,0x02,0x01]))
            pendingBinaryCommands.append(Data([0xFD,0xFC,0xFB,0xFA,0x04,0x00,0x08,0x00,0x04,0x00,0x04,0x03,0x02,0x01]))
            processQueue()
        } else {
            bypassHandshake = false
            isInHandshake = false
            handshakeCompleted = false
            pendingCommands.removeAll()
            queueCommand("AT+R1?\r\n")
            queueCommand("AT+R2?\r\n")
            queueCommand("AT+R3?\r\n")
            queueCommand("AT+ONTH?\r\n")
            queueCommand("AT+HOLD?\r\n")
            queueCommand("AT+STIME=100\r\n")
            queueCommand("AT+FTIME=100\r\n")
            startHandshake()
        }
    }
    
    func saveParameters(_ params: DeviceParameters) {
        currentOperation = .save
        if hardwareType == .modelA {
            pendingBinaryCommands.removeAll()
            pendingBinaryCommands.append(Data([0xFD,0xFC,0xFB,0xFA,0x04,0x00,0xFF,0x00,0x01,0x00,0x04,0x03,0x02,0x01]))
            let minUnits = UInt8(max(0, Int(round(params.range1 / 0.75))))
            let maxUnits = UInt8(max(0, Int(round(params.range3 / 0.75))))
            let dis = UInt8(max(0, min(255, params.exitDelay)))
            pendingBinaryCommands.append(Data([0xFD,0xFC,0xFB,0xFA,0x0E,0x00,0x07,0x00,0x00,0x00,minUnits,0x00,0x00,0x00,0x2F,0x00,0x64,0x00,0x00,0x00,0x04,0x03,0x02,0x01]))
            pendingBinaryCommands.append(Data([0xFD,0xFC,0xFB,0xFA,0x0E,0x00,0x07,0x00,0x01,0x00,maxUnits,0x00,0x00,0x00,0x2F,0x00,0x64,0x00,0x00,0x00,0x04,0x03,0x02,0x01]))
            pendingBinaryCommands.append(Data([0xFD,0xFC,0xFB,0xFA,0x0E,0x00,0x07,0x00,0x04,0x00,dis,0x00,0x00,0x00,0x2F,0x00,0x64,0x00,0x00,0x00,0x04,0x03,0x02,0x01]))
            pendingBinaryCommands.append(Data([0xFD,0xFC,0xFB,0xFA,0x02,0x00,0xFE,0x00,0x04,0x03,0x02,0x01]))
            processQueue()
        } else {
            handshakeCompleted = false
            pendingCommands.removeAll()
            let hold = params.exitDelay * 10
            queueCommand("AT+R1=\(Int(params.range1 * 100))\r\n")
            queueCommand("AT+R2=\(Int(params.range2 * 100))\r\n")
            queueCommand("AT+R3=\(Int(params.range3 * 100))\r\n")
            queueCommand("AT+ONTH=\(params.sensitivity)\r\n")
            queueCommand("AT+STIME=10\r\n")
            queueCommand("AT+HOLD=\(hold)\r\n")
            queueCommand("AT+FTIME=100\r\n")
            startHandshake()
        }
    }
    
    func sendCommand(_ command: String) {
        // Public generic sender, but if used for config, it might fail without handshake.
        // Assuming this is used for simple commands or custom logic.
        // Ideally, we should also enforce handshake here if it's a config command.
        // For now, let's just send it.
        sendRaw(command + "\r\n")
    }

    private func queueCommand(_ cmd: String) {
        pendingCommands.append(cmd)
    }
    
    private func processQueue() {
        guard !isSending else { return }
        if hardwareType == .modelA {
            guard !pendingBinaryCommands.isEmpty else {
                if currentOperation != .none {
                    DispatchQueue.main.async {
                        switch self.currentOperation {
                        case .save: self.lastToast = "Saved Successfully"
                        case .restore: self.lastToast = "Restored Successfully"
                        case .read: self.lastToast = "Switched to Read-only Mode"
                        default: break
                        }
                        self.currentOperation = .none
                    }
                }
                return
            }
            let d = pendingBinaryCommands.removeFirst()
            if d.count >= 10 && d[6] == 0x08 && d[7] == 0x00 {
                let idLo = d[8]
                let idHi = d[9]
                lastAParamId = UInt16(idLo) | (UInt16(idHi) << 8)
            }
            sendBinary(d)
            return
        }
        
        // If running or handshake not complete, initiate handshake
        if (isRunningMode || !handshakeCompleted) && !bypassHandshake {
            if !isInHandshake {
                startHandshake()
            }
            return
        }
        
        guard !pendingCommands.isEmpty else {
            // Queue empty, operation complete
            if currentOperation != .none {
                DispatchQueue.main.async {
                    switch self.currentOperation {
                    case .save: self.lastToast = "Saved Successfully"
                    case .restore: self.lastToast = "Restored Successfully"
                    case .read: self.lastToast = "Switched to Read-only Mode"
                    default: break
                    }
                    self.currentOperation = .none
                    self.bypassHandshake = false
                }
            }
            return
        }
        let cmd = pendingCommands.removeFirst()
        sendRaw(cmd)
        isSending = true
        lastSentWasAA = cmd.hasPrefix("AA")
    }
    
    private func sendRaw(_ command: String) {
        guard let peripheral = connectedPeripheral,
              let characteristic = writeCharacteristic,
              let data = command.data(using: .utf8) else {
            print("Failed to send: Device not ready or encoding error")
            return
        }
        
        // Determine write type based on properties
        let type: CBCharacteristicWriteType = characteristic.properties.contains(.write) ? .withResponse : .withoutResponse
        
        isSending = true
        peripheral.writeValue(data, for: characteristic, type: type)
        let trimmed = command.trimmingCharacters(in: .whitespacesAndNewlines)
        lastSentCommand = trimmed
        print("TX: \(trimmed)")
        lastSentWasAA = command.hasPrefix("AA")
        commandTimeoutWork?.cancel()
        
        // Custom timeout: RESET longer; queries a bit longer than normal
        let upper = command.uppercased()
        let isReset = upper.contains("AT+RESET")
        let isQuery = upper.contains("?")
        let timeout: Double = isReset ? 3.0 : (isQuery ? 0.2 : 0.5)

        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.isSending = false
            if self.lastSentCommand.contains("?") {
                let key = self.lastSentCommand
                let c = self.retryCounts[key] ?? 0
                if c < 3 {
                    self.retryCounts[key] = c + 1
                    self.pendingCommands.insert(key + "\r\n", at: 0)
                    self.processQueue()
                    return
                }
            }
            self.processQueue()
        }
        commandTimeoutWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + timeout, execute: work)
    }
    
    private func hexString(_ data: Data) -> String {
        data.map { String(format: "%02X", $0) }.joined()
    }
    
    private func sendBinary(_ frame: Data) {
        guard let peripheral = connectedPeripheral,
              let characteristic = writeCharacteristic else { return }
        isSending = true
        let type: CBCharacteristicWriteType = characteristic.properties.contains(.write) ? .withResponse : .withoutResponse
        peripheral.writeValue(frame, for: characteristic, type: type)
        lastSentCommand = "BIN:" + hexString(frame)
        commandTimeoutWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self = self else { return }
            self.isSending = false
            let key = self.lastSentCommand
            let c = self.retryCounts[key] ?? 0
            if c < 3 {
                self.retryCounts[key] = c + 1
                self.pendingBinaryCommands.insert(frame, at: 0)
                self.processQueue()
                return
            }
            self.processQueue()
        }
        commandTimeoutWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.2, execute: work)
    }

    func restoreDefaults() {
        currentOperation = .restore
        if hardwareType == .modelA {
            pendingBinaryCommands.removeAll()
            pendingBinaryCommands.append(Data([0xFD,0xFC,0xFB,0xFA,0x04,0x00,0xFF,0x00,0x01,0x00,0x04,0x03,0x02,0x01]))
            pendingBinaryCommands.append(Data([0xFD,0xFC,0xFB,0xFA,0x0E,0x00,0x07,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x2F,0x00,0x64,0x00,0x00,0x00,0x04,0x03,0x02,0x01]))
            pendingBinaryCommands.append(Data([0xFD,0xFC,0xFB,0xFA,0x0E,0x00,0x07,0x00,0x01,0x00,0x04,0x00,0x00,0x00,0x2F,0x00,0x64,0x00,0x00,0x00,0x04,0x03,0x02,0x01]))
            pendingBinaryCommands.append(Data([0xFD,0xFC,0xFB,0xFA,0x0E,0x00,0x07,0x00,0x04,0x00,0x1E,0x00,0x00,0x00,0x2F,0x00,0x64,0x00,0x00,0x00,0x04,0x03,0x02,0x01]))
            pendingBinaryCommands.append(Data([0xFD,0xFC,0xFB,0xFA,0x02,0x00,0xFE,0x00,0x04,0x03,0x02,0x01]))
            processQueue()
        } else {
            bypassHandshake = true
            sendCommand("AT+INIT")
        }
    }
    
    // MARK: - CBCentralManagerDelegate
    
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        bluetoothState = central.state
        
        switch central.state {
        case .poweredOn:
            print("蓝牙已开启")
        case .poweredOff:
            print("蓝牙已关闭")
            isScanning = false
        case .unsupported:
            print("设备不支持蓝牙")
        case .unauthorized:
            print("未授权蓝牙使用")
        case .resetting:
            print("蓝牙正在重置")
        case .unknown:
            print("蓝牙状态未知")
        @unknown default:
            print("未知的蓝牙状态")
        }
    }
    
    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                       advertisementData: [String: Any], rssi RSSI: NSNumber) {
        // Store the peripheral reference
        discoveredPeripherals[peripheral.identifier] = peripheral
        
        // Try to get name from advertisement data
        let deviceName = advertisementData[CBAdvertisementDataLocalNameKey] as? String ?? peripheral.name ?? "Unknown Device"
        
        // Filter: only show names starting with CNDingtek or DC59 (case-insensitive)
        let lowerName = deviceName.lowercased()
        if !(lowerName.hasPrefix("cndingtek") || lowerName.hasPrefix("dc59")) {
            return
        }
        
        // On iOS, we cannot read real MAC; show UUID for identification
        
        let device = Device(
            id: peripheral.identifier,
            name: deviceName,
            macAddress: peripheral.identifier.uuidString, // Use UUID as unique identifier display
            rssi: RSSI.intValue,
            isConnected: peripheral.state == .connected,
            lastSeen: Date()
        )
        
        // 更新或添加设备
        if let index = discoveredDevices.firstIndex(where: { $0.id == device.id }) {
            discoveredDevices[index] = device
        } else {
            discoveredDevices.append(device)
        }
    }
    
    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        print("已连接到设备: \(peripheral.name ?? "Unknown")")
        connectedPeripheral = peripheral
        peripheral.delegate = self
        peripheral.discoverServices([serviceUUID])
        
        if let index = discoveredDevices.firstIndex(where: { $0.id == peripheral.identifier }) {
            discoveredDevices[index].isConnected = true
        }
    }
    
    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                       error: Error?) {
        print("设备断开连接: \(peripheral.name ?? "Unknown")")
        connectedPeripheral = nil
        writeCharacteristic = nil
        notifyCharacteristic = nil
        
        if let index = discoveredDevices.firstIndex(where: { $0.id == peripheral.identifier }) {
            discoveredDevices[index].isConnected = false
        }
    }
    
    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,
                       error: Error?) {
        print("连接失败: \(error?.localizedDescription ?? "Unknown error")")
    }
    
    // MARK: - CBPeripheralDelegate
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil else {
            print("发现服务失败: \(error!.localizedDescription)")
            return
        }
        
        guard let services = peripheral.services else { return }
        
        for service in services {
            // Check for FFF0 service or potentially others if needed
            if service.uuid == serviceUUID || service.uuid.uuidString.contains("FFF0") {
                print("Found Service: \(service.uuid)")
                // Discover ALL characteristics to find the correct Write/Notify ones
                peripheral.discoverCharacteristics(nil, for: service)
            }
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                   error: Error?) {
        guard error == nil else {
            print("发现特征失败: \(error!.localizedDescription)")
            return
        }
        
        guard let characteristics = service.characteristics else { return }
        
        for characteristic in characteristics {
            print("Found Characteristic: \(characteristic.uuid), Properties: \(characteristic.properties)")
            
            // Look for Notify property
            if characteristic.properties.contains(.notify) {
                print(" -> Subscribing to Notify: \(characteristic.uuid)")
                self.notifyCharacteristic = characteristic
                peripheral.setNotifyValue(true, for: characteristic)
            }
            
            // Look for Write or WriteWithoutResponse property
            if characteristic.properties.contains(.write) || characteristic.properties.contains(.writeWithoutResponse) {
                print(" -> Found Write Characteristic: \(characteristic.uuid)")
                self.writeCharacteristic = characteristic
            }
        }
        
        // Fallback: If we found a characteristic that does both (common in some modules)
        if writeCharacteristic == nil && notifyCharacteristic != nil {
             let char = notifyCharacteristic!
             if char.properties.contains(.write) || char.properties.contains(.writeWithoutResponse) {
                 writeCharacteristic = char
             }
        }
    }
    
    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                   error: Error?) {
        guard dataProcessingEnabled else { return }
        guard error == nil else {
            print("更新特征值失败: \(error!.localizedDescription)")
            return
        }
        
        guard let data = characteristic.value else { return }
        if data.count >= 4 {
            let hdr = [UInt8](data.prefix(4))
            if hdr == [0xFD,0xFC,0xFB,0xFA] {
                hardwareType = .modelA
                handleBinaryA(data)
                return
            }
        }
        guard let response = String(data: data, encoding: .utf8) else { return }
        let trimmed = response.trimmingCharacters(in: .whitespacesAndNewlines)
        if hardwareType == .unknown {
            if let rangeDetect = try? NSRegularExpression(pattern: #"(?i)^\s*Range\s+\d+\s*$"#, options: []),
               rangeDetect.firstMatch(in: trimmed, options: [], range: NSRange(location: 0, length: (trimmed as NSString).length)) != nil {
                hardwareType = .modelA
            } else if let bDetect = try? NSRegularExpression(pattern: #"^\s*[0-2]\s*,\s*\d+cm"#, options: []),
                      bDetect.firstMatch(in: trimmed, options: [], range: NSRange(location: 0, length: (trimmed as NSString).length)) != nil {
                hardwareType = .modelB
            }
        }
        
        print("RX: \(response)")
        // Accumulate and process complete lines, buffering partial fragments
        let combined = lineBuffer + response
        let parts = combined.components(separatedBy: CharacterSet.newlines)
        let endsWithNewline = combined.hasSuffix("\n") || combined.hasSuffix("\r")
        let processCount = parts.count
        
        for i in 0..<processCount {
            let line = parts[i].trimmingCharacters(in: .whitespacesAndNewlines)
            if !line.isEmpty {
                handleResponse(line)
            }
        }
        
        // Save leftover partial line
        lineBuffer = endsWithNewline ? "" : (parts.last ?? "")
    }
    
    private func handleResponse(_ response: String) {
        // 1. Handshake
        if response.contains("STOP") || response.contains("TOP") {
            handshakeCompleted = true
            isRunningMode = false
            isInHandshake = false
            // If we have pending commands, process them now
            processQueue()
            return
        }
        if response.contains("RUN") {
            isRunningMode = true
            if currentOperation == .read { finalizeReading() }
            return
        }
        
        // 2. Command Response (OK)
        if response.contains("AT+OK") || response == "OK" || response.contains(",OK") {
            isSending = false
            commandTimeoutWork?.cancel()
            commandTimeoutWork = nil
            // Special handling for acknowledged set commands
            if lastSentCommand.hasPrefix("AT+STIME=") {
                if let valStr = lastSentCommand.split(separator: "=").last, let v = Int(valStr) {
                    var p = deviceParams; p.slowTime = v
                    DispatchQueue.main.async { [weak self] in self?.deviceParams = p }
                }
            } else if lastSentCommand.hasPrefix("AT+FTIME=") {
                if let valStr = lastSentCommand.split(separator: "=").last, let v = Int(valStr) {
                    var p = deviceParams; p.fastTime = v
                    DispatchQueue.main.async { [weak self] in self?.deviceParams = p }
                }
            } else if lastSentCommand.hasPrefix("AT+ONTH=") {
                if let valStr = lastSentCommand.split(separator: "=").last, let v = Int(valStr) {
                    var p = deviceParams; p.sensitivity = v
                    DispatchQueue.main.async { [weak self] in self?.deviceParams = p }
                }
            } else if lastSentCommand.hasPrefix("AT+R1=") || lastSentCommand.hasPrefix("AT+R2=") || lastSentCommand.hasPrefix("AT+R3=") {
                let parts = lastSentCommand.split(separator: "=")
                if parts.count == 2, let v = Double(parts[1]) {
                    let meters = v / 100.0
                    var p = deviceParams
                    if lastSentCommand.hasPrefix("AT+R1=") { p.range1 = meters }
                    if lastSentCommand.hasPrefix("AT+R2=") { p.range2 = meters }
                    if lastSentCommand.hasPrefix("AT+R3=") { p.range3 = meters }
                    DispatchQueue.main.async { [weak self] in self?.deviceParams = p }
                }
            } else if lastSentCommand.hasPrefix("AT+HOLD=") {
                if let valStr = lastSentCommand.split(separator: "=").last, let v = Int(valStr) {
                    let seconds = v / 10
                    var p = deviceParams; p.holdFrame = v; p.exitDelay = seconds
                    DispatchQueue.main.async { [weak self] in self?.deviceParams = p }
                }
            } else if currentOperation == .restore && lastSentCommand == "AT+INIT" {
                // After INIT OK, push default settings
                pendingCommands.append("AT+R1=200\r\n")   // 2m
                pendingCommands.append("AT+R2=500\r\n")   // 5m
                pendingCommands.append("AT+R3=1000\r\n")  // 10m
                pendingCommands.append("AT+ONTH=4\r\n")   // sensitivity
                // Align with latest scheme: no TRITH, fix STIME/FTIME and HOLD only
                pendingCommands.append("AT+STIME=10\r\n")   // write SlowTime fixed 10
                pendingCommands.append("AT+FTIME=100\r\n")  // FastTime fixed 100
                pendingCommands.append("AT+HOLD=200\r\n")   // Delay Time default 20s -> HOLD=20*10
                processQueue()
            }
            processQueue()
            return
        }
        
        // 2.1 Handle AT+RESET special case (responds with GPIO dump, not OK)
        if lastSentCommand == "AT+RESET" && response.range(of: "(?i)gpio", options: .regularExpression) != nil {
             print("INFO: AT+RESET acknowledged via GPIO response")
             isSending = false
             commandTimeoutWork?.cancel()
             commandTimeoutWork = nil
             processQueue()
             // Fall through to allow GPIO parsing
        }

        if response.contains("AT+ERR") {
            // If we just tried AA while device already in STOP, treat as handshake OK and continue
            if lastSentWasAA {
                handshakeCompleted = true
                isInHandshake = false
                isRunningMode = false
                isSending = false
                commandTimeoutWork?.cancel()
                commandTimeoutWork = nil
                print("INFO: AA rejected with AT+ERR, assuming already in STOP; proceeding with AT queue")
                processQueue()
                return
            } else {
                // AT command error
                isSending = false
                commandTimeoutWork?.cancel()
                commandTimeoutWork = nil
                print("ERROR: AT command rejected (AT+ERR). Command format invalid or not allowed in current state")
                return
            }
        }

        // RX log of raw line
        print("RX: \(response)")

        // 2.2 Query responses AT+XXXX=YYYY
        if let m = try? NSRegularExpression(pattern: "(?i)\\bAT\\+R([123])\\s*=\\s*(\\d+)\\b", options: []).firstMatch(in: response, options: [], range: NSRange(location: 0, length: (response as NSString).length)) {
            let idxStr = (response as NSString).substring(with: m.range(at: 1))
            let valStr = (response as NSString).substring(with: m.range(at: 2))
            let cm = Double(valStr) ?? 0
            let meters = cm / 100.0
            var p = deviceParams
            if idxStr == "1" { p.range1 = meters }
            if idxStr == "2" { p.range2 = meters }
            if idxStr == "3" { p.range3 = meters }
            DispatchQueue.main.async { [weak self] in self?.deviceParams = p }
            retryCounts["AT+R" + idxStr + "?"] = nil
            // Treat query response as ack: cancel timeout and continue
            isSending = false
            commandTimeoutWork?.cancel(); commandTimeoutWork = nil
            processQueue()
            return
        } else if let m2 = try? NSRegularExpression(pattern: "(?i)\\bAT\\+ONTH\\s*=\\s*(\\d+)\\b", options: []).firstMatch(in: response, options: [], range: NSRange(location: 0, length: (response as NSString).length)) {
            let valStr = (response as NSString).substring(with: m2.range(at: 1))
            let v = Int(valStr) ?? 0
            var p = deviceParams; p.sensitivity = v
            DispatchQueue.main.async { [weak self] in self?.deviceParams = p }
            retryCounts["AT+ONTH?"] = nil
            isSending = false
            commandTimeoutWork?.cancel(); commandTimeoutWork = nil
            processQueue()
            return
        } else if let m3 = try? NSRegularExpression(pattern: "(?i)\\bAT\\+TRITH\\s*=\\s*(\\d+)\\b", options: []).firstMatch(in: response, options: [], range: NSRange(location: 0, length: (response as NSString).length)) {
            let valStr = (response as NSString).substring(with: m3.range(at: 1))
            let v = Int(valStr) ?? 0
            var p = deviceParams; p.trith = v; p.enterDelay = v
            DispatchQueue.main.async { [weak self] in self?.deviceParams = p }
            retryCounts["AT+TRITH?"] = nil
            isSending = false
            commandTimeoutWork?.cancel(); commandTimeoutWork = nil
            processQueue()
            return
        } else if let m4 = try? NSRegularExpression(pattern: "(?i)\\bAT\\+HOLD\\s*=\\s*(\\d+)\\b", options: []).firstMatch(in: response, options: [], range: NSRange(location: 0, length: (response as NSString).length)) {
            let valStr = (response as NSString).substring(with: m4.range(at: 1))
            let v = Int(valStr) ?? 0
            // As per requirement for read: seconds = HOLD/10
            let seconds = v / 10
            var p = deviceParams; p.holdFrame = v; p.exitDelay = seconds
            DispatchQueue.main.async { [weak self] in self?.deviceParams = p }
            retryCounts["AT+HOLD?"] = nil
            isSending = false
            commandTimeoutWork?.cancel(); commandTimeoutWork = nil
            processQueue()
            return
        }

        // Capture window from GPIO ... MR1TH= by concatenating lines (only in read flow)
        if currentOperation == .read,
           response.range(of: "(?i)gpio", options: .regularExpression) != nil && !paramCaptureActive {
            paramCaptureActive = true
            paramCaptureBuffer = ""
            print("CAPTURE: start GPIO")
            // Reset RX raw state and clear displayed params to baseline zeros for fresh parse
            rxFastTime = nil
            rxSlowTime = nil
            rxTrith = nil
            rxHoldFrame = nil
            var zero = deviceParams
            zero.sensitivity = 0
            zero.enterDelay = 0
            zero.exitDelay = 0
            zero.range1 = 0
            zero.range2 = 0
            zero.range3 = 0
            zero.holdFrame = 0
            zero.trith = 0
            zero.fastTime = 0
            zero.slowTime = 0
            DispatchQueue.main.async { [weak self] in
                self?.deviceParams = zero
            }
        }
        if currentOperation == .read, paramCaptureActive {
            let part = response.components(separatedBy: CharacterSet.whitespacesAndNewlines).joined()
            paramCaptureBuffer += part
            if response.range(of: "(?i)mr1th\\s*=", options: .regularExpression) != nil {
                let s = paramCaptureBuffer.components(separatedBy: CharacterSet.whitespacesAndNewlines).joined()
                func between(_ start: String, _ end: String, in text: String) -> String? {
                    guard let r1 = text.range(of: start, options: .caseInsensitive) else { return nil }
                    let tail = text[r1.upperBound...]
                    guard let r2 = tail.range(of: end, options: .caseInsensitive) else { return nil }
                    return String(tail[..<r2.lowerBound])
                }
                let v1 = between("Range1=", "Range2=", in: s)
                let v2 = between("Range2=", "Range3=", in: s)
                let v3 = between("Range3=", "MR1TH=", in: s)
                func toMeters(_ t: String?) -> Double? {
                    guard var str = t else { return nil }
                    str = str.replacingOccurrences(of: "ｍ", with: "m")
                    if str.hasSuffix("m") { str = String(str.dropLast()) }
                    return Double(str)
                }
                let d1 = toMeters(v1)
                let d2 = toMeters(v2)
                let d3 = toMeters(v3)
                print("CAPTURE: window GPIO→MR1TH parsed R1=\(d1 ?? -1)m R2=\(d2 ?? -1)m R3=\(d3 ?? -1)m")
                var newParams = deviceParams
                if let d = d1 { newParams.range1 = d }
                if let d = d2 { newParams.range2 = d }
                if let d = d3 { newParams.range3 = d }
                DispatchQueue.main.async { [weak self] in
                    self?.deviceParams = newParams
                }
                paramCaptureActive = false
                paramCaptureBuffer = ""
                if currentOperation == .read {
                    DispatchQueue.main.async { [weak self] in
                        self?.lastToast = "Switched to Read-only Mode"
                    }
                }
            }
        }
        // 2.5 Range handling, including inline concatenations (only in read flow)
        if currentOperation == .read, let currentPending = pendingRangeKey {
            if let inlineKeyRegex = try? NSRegularExpression(pattern: "(?i)range(\\d)\\s*=", options: []),
               let inlineMatch = inlineKeyRegex.firstMatch(in: response, options: [], range: NSRange(location: 0, length: (response as NSString).length)) {
                let keyStr = (response as NSString).substring(with: inlineMatch.range(at: 1))
                let start = inlineMatch.range.location
                let end = start + inlineMatch.range.length
                let prefix = (response as NSString).substring(with: NSRange(location: 0, length: start))
                let suffix = end < (response as NSString).length ? (response as NSString).substring(from: end) : ""
                
                // First, try to commit value for the previous pending segment from prefix
                if let valRegex = try? NSRegularExpression(pattern: "(?i)(\\d+(?:\\.\\d+)?)\\s*[m\\uFF4D]", options: []),
                   let valMatch = valRegex.firstMatch(in: prefix, options: [], range: NSRange(location: 0, length: (prefix as NSString).length)) {
                    let valStr = (prefix as NSString).substring(with: valMatch.range(at: 1))
                    let val = Double(valStr) ?? 0
                    var newParams = deviceParams
                    switch currentPending {
                    case 1: newParams.range1 = val
                    case 2: newParams.range2 = val
                    case 3: newParams.range3 = val
                    default: break
                    }
                    
                    DispatchQueue.main.async { [weak self] in
                        self?.deviceParams = newParams
                    }
                } else {
                    commitPendingRange(reason: "inline Range key R\(keyStr)")
                }
                
                // Now, if the same line also includes a value for the new key in the suffix, set it immediately
                if let valRegex2 = try? NSRegularExpression(pattern: "(?i)^\\s*(\\d+(?:\\.\\d+)?)\\s*[m\\uFF4D]", options: []),
                   let valMatch2 = valRegex2.firstMatch(in: suffix, options: [], range: NSRange(location: 0, length: (suffix as NSString).length)) {
                    let valStr2 = (suffix as NSString).substring(with: valMatch2.range(at: 1))
                    let val2 = Double(valStr2) ?? 0
                    var newParams = deviceParams
                    switch Int(keyStr) ?? 0 {
                    case 1: newParams.range1 = val2
                    case 2: newParams.range2 = val2
                    case 3: newParams.range3 = val2
                    default: break
                    }
                    pendingRangeKey = nil
                    rangeSegmentBuffer = ""
                    
                    DispatchQueue.main.async { [weak self] in
                        self?.deviceParams = newParams
                    }
                } else {
                    pendingRangeKey = Int(keyStr)
                    rangeSegmentBuffer = ""
                    
                }
                return
            }
        }
        if currentOperation == .read,
           let inlineBothRegex = try? NSRegularExpression(pattern: "(?i)\\brange(\\d)\\s*=\\s*(\\d+(?:\\.\\d+)?)\\s*[m\\uFF4D]\\b", options: []),
           let bothMatch = inlineBothRegex.firstMatch(in: response, options: [], range: NSRange(location: 0, length: (response as NSString).length)) {
            let kRange = bothMatch.range(at: 1)
            let vRange = bothMatch.range(at: 2)
            let kStr = (response as NSString).substring(with: kRange)
            let vStr = (response as NSString).substring(with: vRange)
            let val = Double(vStr) ?? 0
            var newParams = deviceParams
            switch Int(kStr) ?? 0 {
            case 1: newParams.range1 = val
            case 2: newParams.range2 = val
            case 3: newParams.range3 = val
            default: break
            }
            pendingRangeKey = nil
            rangeSegmentBuffer = ""
            
            DispatchQueue.main.async { [weak self] in
                self?.deviceParams = newParams
            }
            return
        }
        // Split-line handling: "RangeX=" on its own line
        if currentOperation == .read,
           let keyRegex = try? NSRegularExpression(pattern: "(?i)^\\s*range(\\d)\\s*=\\s*$", options: []),
           let keyMatch = keyRegex.firstMatch(in: response, options: [], range: NSRange(location: 0, length: (response as NSString).length)) {
            let keyRange = keyMatch.range(at: 1)
            let keyStr = (response as NSString).substring(with: keyRange)
            // Commit previous pending if exists before switching key
            if pendingRangeKey != nil {
                commitPendingRange(reason: "next Range key R\(keyStr)")
            }
            pendingRangeKey = Int(keyStr)
            rangeSegmentBuffer = ""
            
            return
        }
        if currentOperation == .read, let key = pendingRangeKey {
            // Pure numeric line in config mode (meters only)
            if let valRegex = try? NSRegularExpression(pattern: "(?i)^\\s*(\\d+(?:\\.\\d+)?)\\s*[m\\uFF4D]\\s*$", options: []),
               let valMatch = valRegex.firstMatch(in: response, options: [], range: NSRange(location: 0, length: (response as NSString).length)) {
                let valRange = valMatch.range(at: 1)
                let valStr = (response as NSString).substring(with: valRange)
                let val = Double(valStr) ?? 0
                var newParams = deviceParams
                switch key {
                case 1: newParams.range1 = val
                case 2: newParams.range2 = val
                case 3: newParams.range3 = val
                default: break
                }
                pendingRangeKey = nil
                pendingRangeValue = nil
                rangeSegmentBuffer = ""
                
                DispatchQueue.main.async { [weak self] in
                    self?.deviceParams = newParams
                }
                return
            }
            // console prefix not parsed; only raw line is considered
        }
        // While collecting a range segment, accumulate content for robust extraction
        if currentOperation == .read, pendingRangeKey != nil {
            rangeSegmentBuffer += (rangeSegmentBuffer.isEmpty ? response : (" " + response))
        }
        
        // Boundary markers: when MR1TH appears, commit any pending range (typically after Range3 sequence)
        if currentOperation == .read, response.range(of: "(?i)mr1th", options: .regularExpression) != nil {
            commitPendingRange(reason: "MR1TH boundary")
        }

        // 3. Monitor Data
        if response.contains("ON") {
            let lang = UserDefaults.standard.string(forKey: "appLanguage") ?? "en"
            if lang == "zh-Hans" {
                appendLog("[\(formatTime())] 检测到运动")
            } else {
                appendLog("[\(formatTime())] motion")
            }
        } else if response.contains("OFF") {
            let lang = UserDefaults.standard.string(forKey: "appLanguage") ?? "en"
            if lang == "zh-Hans" {
                appendLog("[\(formatTime())] 无目标")
            } else {
                appendLog("[\(formatTime())] no object")
            }
        } else if response.trimmingCharacters(in: .whitespacesAndNewlines) == "0" {
            let lang = UserDefaults.standard.string(forKey: "appLanguage") ?? "en"
            if lang == "zh-Hans" {
                appendLog("[\(formatTime())] 无目标")
            } else {
                appendLog("[\(formatTime())] no object")
            }
        } else if let rangeM = try? NSRegularExpression(pattern: #"(?i)^\s*Range\s+(\d+)\s*$"#, options: []),
                  let m = rangeM.firstMatch(in: response, options: [], range: NSRange(location: 0, length: (response as NSString).length)) {
            let distStr = (response as NSString).substring(with: m.range(at: 1))
            let dist = Int(distStr) ?? 0
            let lang = UserDefaults.standard.string(forKey: "appLanguage") ?? "en"
            if lang == "zh-Hans" {
                appendLog("[\(formatTime())] 距离\(dist)cm")
            } else {
                appendLog("[\(formatTime())] distance \(dist)cm")
            }
            appendDistance(dist)
            if hardwareType != .modelB { hardwareType = .modelA }
            isRunningMode = true
        } else if let regex = try? NSRegularExpression(pattern: #"^([0-2])\s*,\s*(\d+)cm(?:\s*,\s*(\d+))?"#, options: []),
                  let match = regex.firstMatch(in: response, options: [], range: NSRange(location: 0, length: (response as NSString).length)) {
            isRunningMode = true
            let codeStr = (response as NSString).substring(with: match.range(at: 1))
            let distStr = (response as NSString).substring(with: match.range(at: 2))
            let code = Int(codeStr) ?? 0
            let dist = Int(distStr) ?? 0
            let lang = UserDefaults.standard.string(forKey: "appLanguage") ?? "en"
            let statusZh = (code == 0 ? "无目标" : (code == 1 ? "检测到运动" : "检测到微动"))
            let statusEn = (code == 0 ? "no object" : (code == 1 ? "motion" : "presence"))
            if lang == "zh-Hans" {
                appendLog("[\(formatTime())] " + statusZh)
                appendLog("[\(formatTime())] 距离\(dist)cm")
            } else {
                appendLog("[\(formatTime())] " + statusEn)
                appendLog("[\(formatTime())] distance \(dist)cm")
            }
            appendDistance(dist)
            if currentOperation == .read { finalizeReading() }
        }
        
        // 4. Parameters (Parsing complex string) — only during explicit read flow
        if currentOperation == .read {
            parseParameters(response)
        }
    }
    
    private func monitorHumanText(code: Int, dist: Int) -> String {
        let lang = UserDefaults.standard.string(forKey: "appLanguage") ?? "en"
        if dist == 0 {
            return lang == "zh-Hans" ? "无目标" : "no object"
        }
        if lang == "zh-Hans" {
            switch code {
            case 1: return "检测到运动，距离\(dist)cm"
            case 2: return "检测到微动，距离\(dist)cm"
            default: return "无目标"
            }
        } else {
            switch code {
            case 1: return "motion detected \(dist)cm"
            case 2: return "presence detected \(dist)cm"
            default: return "no object"
            }
        }
    }

    private func commitPendingRange(reason: String) {
        guard let key = pendingRangeKey else { return }
        defer { pendingRangeKey = nil; pendingRangeValue = nil; rangeSegmentBuffer = "" }
        var val: Double? = pendingRangeValue
        if val == nil {
            // Try to extract from accumulated segment buffer, removing CRLFs/spaces
            let buf = rangeSegmentBuffer.replacingOccurrences(of: "\n", with: " ")
                                         .replacingOccurrences(of: "\r", with: " ")
            if let regex = try? NSRegularExpression(pattern: "(?i)(\\d+(?:\\.\\d+)?)\\s*m", options: []),
               let match = regex.firstMatch(in: buf, options: [], range: NSRange(location: 0, length: (buf as NSString).length)) {
                let vRange = match.range(at: 1)
                let vStr = (buf as NSString).substring(with: vRange)
                let extracted = Double(vStr) ?? 0
                val = extracted
                
            }
        }
        guard let finalVal = val else {
            
            return
        }
        var newParams = deviceParams
        switch key {
        case 1: newParams.range1 = finalVal
        case 2: newParams.range2 = finalVal
        case 3: newParams.range3 = finalVal
        default: break
        }
        
        DispatchQueue.main.async { [weak self] in
        self?.deviceParams = newParams
        }
    }
    
    private func finalizeReading() {
        currentOperation = .none
        rxFastTime = nil
        rxSlowTime = nil
        rxTrith = nil
        rxHoldFrame = nil
        paramCaptureActive = false
        paramCaptureBuffer = ""
    }
    
    private func appendLog(_ message: String) {
        DispatchQueue.main.async { [weak self] in
            self?.logs.append(message)
            if (self?.logs.count ?? 0) > 1000 { self?.logs.removeFirst() }
        }
    }
    
    private func appendDistance(_ distance: Int) {
        DispatchQueue.main.async { [weak self] in
            self?.distanceHistory.append(distance)
            if (self?.distanceHistory.count ?? 0) > 100 { self?.distanceHistory.removeFirst() }
            let sample = DistanceSample(time: Date(), value: distance)
            self?.distanceSeries.append(sample)
            if (self?.distanceSeries.count ?? 0) > 300 { self?.distanceSeries.removeFirst() }
        }
    }
    
    private func parseParameters(_ response: String) {
        // Regex parsing for params
        // Example: HoldFrame=300, SlowTime=100, TRITH=10, HOLDONTH=4, Range1=\nReceived: 2.0m
        
        var newParams = deviceParams
        var changed = false
        let text = response
        
        // 1) Parse raw fields only from actual RX content (do NOT use defaults)
        if let ft = extractInt(from: text, pattern: "(?i)(?:fasttime|ftime)\\s*=\\s*(\\d+)") {
            rxFastTime = ft
            newParams.fastTime = ft
        }
        if let st = extractInt(from: text, pattern: "(?i)slowtime=(\\d+)") ?? extractInt(from: text, pattern: "(?i)stime=(\\d+)") {
            rxSlowTime = st
            newParams.slowTime = st
        }
        if let t = extractInt(from: text, pattern: "(?i)trith=(\\d+)") {
            rxTrith = t
            newParams.trith = t
        }
        if let h = extractInt(from: text, pattern: "(?i)holdframe\\s*=\\s*(\\d+)") {
            rxHoldFrame = h
            newParams.holdFrame = h
        }
        if let sens = extractInt(from: text, pattern: "(?i)(?:holdonth|onth)\\s*=\\s*(\\d+)") {
            if newParams.sensitivity != sens {
                newParams.sensitivity = sens
                changed = true
                
            }
        }
        
        // 2) Recalculate derived values ONLY when dependent raw values are known from RX
        if let hf = rxHoldFrame, let ft = rxFastTime {
            let exitVal = (hf * ft) / 1000
            let changedExit = newParams.exitDelay != exitVal
            if changedExit {
                
                newParams.exitDelay = exitVal
                changed = true
            }
        }
        if let t = rxTrith, let st = rxSlowTime {
            let enterVal = (t * st) / 100
            let changedEnter = newParams.enterDelay != enterVal
            if changedEnter {
                
                newParams.enterDelay = enterVal
                changed = true
            }
        }
        
        // 3) Direct RangeX=2.0m format
        if let r1m = extractDouble(from: text, pattern: "(?i)range1=\\s*(\\d+\\.?\\d*)m") { if newParams.range1 != r1m { newParams.range1 = r1m; changed = true } }
        if let r2m = extractDouble(from: text, pattern: "(?i)range2=\\s*(\\d+\\.?\\d*)m") { if newParams.range2 != r2m { newParams.range2 = r2m; changed = true } }
        if let r3m = extractDouble(from: text, pattern: "(?i)range3=\\s*(\\d+\\.?\\d*)m") { if newParams.range3 != r3m { newParams.range3 = r3m; changed = true } }
        
        if changed {
            DispatchQueue.main.async { [weak self] in
                self?.deviceParams = newParams
            }
        }
    }
    
    private func extractInt(from text: String, pattern: String) -> Int? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return nil }
        let nsString = text as NSString
        guard let match = regex.firstMatch(in: text, options: [], range: NSRange(location: 0, length: nsString.length)) else { return nil }
        return Int(nsString.substring(with: match.range(at: 1)))
    }
    
    private func extractDouble(from text: String, pattern: String) -> Double? {
        guard let regex = try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive]) else { return nil }
        let nsString = text as NSString
        guard let match = regex.firstMatch(in: text, options: [], range: NSRange(location: 0, length: nsString.length)) else { return nil }
        return Double(nsString.substring(with: match.range(at: 1)))
    }
    
    private func formatTime() -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "HH:mm:ss"
        return formatter.string(from: Date())
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic,
                   error: Error?) {
        if let error = error {
            print("写入特征值失败: \(error.localizedDescription)")
            isSending = false // Reset on error
            processQueue() // Try next
        } else {
            print("Command sent successfully")
            // Wait for OK response in didUpdateValueFor
        }
    }
}
