
const SERVICE_UUID = '0000FFF0-0000-1000-8000-00805F9B34FB';
// We will also look for short UUIDs if full UUID doesn't match exactly in some cases, but usually full is safer.
const SHORT_SERVICE_UUID = 'FFF0';

class BLEManager {
  constructor() {
    this.isScanning = false;
    this.discoveredDevices = [];
    this.connectedDevice = null;
    this.deviceId = null;
    this.serviceId = null;
    this.writeCharacteristicId = null;
    this.notifyCharacteristicId = null;
    
    // Callbacks
    this.onDevicesUpdated = null;
    this.onStateChanged = null; // connected, disconnected
    this.onLog = null;
    this.onParamsLoaded = null; // (params) => {}

    // Protocol State
    this.hardwareType = 'unknown'; // 'modelA' or 'modelB'
    this.handshakeCompleted = false;
    this.pendingCommands = [];
    this.pendingBinaryCommands = [];
    this.isSending = false;
    this.lineBuffer = "";
    
    // Parameters
    this.deviceParams = {
      range1: 0, range2: 0, range3: 0,
      sensitivity: 0, enterDelay: 0, exitDelay: 0,
      holdFrame: 0, trith: 0, fastTime: 0, slowTime: 0
    };
  }

  log(msg) {
    console.log(`[BLE] ${msg}`);
    if (this.onLog) this.onLog(msg);
  }

  init() {
    return new Promise((resolve, reject) => {
      wx.openBluetoothAdapter({
        success: (res) => {
          this.log('Bluetooth Adapter initialized');
          this.monitorState();
          resolve(res);
        },
        fail: (err) => {
          this.log('Bluetooth Init failed: ' + JSON.stringify(err));
          reject(err);
        }
      });
    });
  }

  monitorState() {
    wx.onBluetoothAdapterStateChange((res) => {
      this.log('Adapter State Changed: ' + JSON.stringify(res));
      if (!res.available) {
        this.isScanning = false;
      }
    });
    
    wx.onBLEConnectionStateChange((res) => {
      this.log(`Connection state changed: ${res.deviceId} connected: ${res.connected}`);
      if (!res.connected) {
        if (this.deviceId === res.deviceId) {
          this.cleanupConnection();
          if (this.onStateChanged) this.onStateChanged(false);
        }
      }
    });
  }

  startScan() {
    if (this.isScanning) return;
    this.discoveredDevices = [];
    this.isScanning = true;
    if (this.onDevicesUpdated) this.onDevicesUpdated(this.discoveredDevices);

    wx.startBluetoothDevicesDiscovery({
      allowDuplicatesKey: false,
      success: () => {
        this.log('Scanning started');
        wx.onBluetoothDeviceFound((res) => {
          res.devices.forEach(device => {
            this.handleDiscoveredDevice(device);
          });
        });
      },
      fail: (err) => {
        this.log('Scan failed: ' + JSON.stringify(err));
        this.isScanning = false;
      }
    });
  }

  stopScan() {
    if (!this.isScanning) return;
    wx.stopBluetoothDevicesDiscovery({
      success: () => {
        this.isScanning = false;
        this.log('Scanning stopped');
      }
    });
  }

  handleDiscoveredDevice(device) {
    const name = device.name || device.localName || "";
    if (!name) return;
    
    const lowerName = name.toLowerCase();
    if (lowerName.startsWith("cndingtek") || lowerName.startsWith("dc59")) {
      // Check for duplicates
      const existingIdx = this.discoveredDevices.findIndex(d => d.deviceId === device.deviceId);
      if (existingIdx >= 0) {
        this.discoveredDevices[existingIdx] = device;
      } else {
        this.discoveredDevices.push(device);
      }
      if (this.onDevicesUpdated) this.onDevicesUpdated(this.discoveredDevices);
    }
  }

  connect(device) {
    this.stopScan();
    this.log(`Connecting to ${device.name} (${device.deviceId})...`);
    
    wx.createBLEConnection({
      deviceId: device.deviceId,
      success: () => {
        this.deviceId = device.deviceId;
        this.connectedDevice = device;
        this.log('Connected. Discovering services...');
        // Wait a bit for stability
        setTimeout(() => {
          this.getServices();
        }, 1000);
      },
      fail: (err) => {
        this.log('Connection failed: ' + JSON.stringify(err));
      }
    });
  }

  cleanupConnection() {
    this.connectedDevice = null;
    this.deviceId = null;
    this.serviceId = null;
    this.writeCharacteristicId = null;
    this.notifyCharacteristicId = null;
    this.handshakeCompleted = false;
    this.isSending = false;
    this.pendingCommands = [];
    this.pendingBinaryCommands = [];
  }

  disconnect() {
    if (this.deviceId) {
      wx.closeBLEConnection({
        deviceId: this.deviceId
      });
    }
  }

  getServices() {
    wx.getBLEDeviceServices({
      deviceId: this.deviceId,
      success: (res) => {
        this.log('Services: ' + JSON.stringify(res.services));
        let targetService = res.services.find(s => 
          s.uuid.toUpperCase().includes('FFF0')
        );
        
        if (targetService) {
          this.serviceId = targetService.uuid;
          this.getCharacteristics();
        } else {
          this.log('Target service FFF0 not found');
        }
      },
      fail: (err) => this.log('Get Services failed: ' + JSON.stringify(err))
    });
  }

  getCharacteristics() {
    wx.getBLEDeviceCharacteristics({
      deviceId: this.deviceId,
      serviceId: this.serviceId,
      success: (res) => {
        this.log('Characteristics: ' + JSON.stringify(res.characteristics));
        
        // Find Write and Notify characteristics
        // Usually FFF1 or FFF2
        
        for (let c of res.characteristics) {
          const uuid = c.uuid.toUpperCase();
          const props = c.properties;
          
          if (props.notify || props.indicate) {
            this.notifyCharacteristicId = c.uuid;
          }
          
          if (props.write || props.writeNoResponse) {
            this.writeCharacteristicId = c.uuid;
          }
        }
        
        if (this.notifyCharacteristicId && this.writeCharacteristicId) {
          this.enableNotify();
        } else {
          this.log('Could not find required characteristics');
        }
      },
      fail: (err) => this.log('Get Characteristics failed: ' + JSON.stringify(err))
    });
  }

  enableNotify() {
    wx.notifyBLECharacteristicValueChange({
      deviceId: this.deviceId,
      serviceId: this.serviceId,
      characteristicId: this.notifyCharacteristicId,
      state: true,
      success: () => {
        this.log('Notification enabled');
        if (this.onStateChanged) this.onStateChanged(true);
        
        wx.onBLECharacteristicValueChange((res) => {
          this.handleValueChange(res.value);
        });
        
        // Start Protocol
        // We guess hardware type or just try handshake
        // Based on Swift code, we can default to ModelB (AT commands) or ModelA logic
        // Let's assume we start by sending "AA" handshake if needed
        this.determineHardwareType();
      },
      fail: (err) => this.log('Enable notify failed: ' + JSON.stringify(err))
    });
  }
  
  determineHardwareType() {
      // In iOS code, it defaults to .unknown
      // Then if it receives binary, it switches to modelA
      // If it sees text, modelB
      // Let's try sending "AA\r\n" which is common handshake
      this.sendRaw("AA\r\n");
  }

  handleValueChange(buffer) {
    // buffer is ArrayBuffer
    const dataView = new DataView(buffer);
    
    // Check for Binary (Model A)
    // Model A binary usually starts with FD FC FB FA
    if (buffer.byteLength >= 4) {
        const h1 = dataView.getUint8(0);
        const h2 = dataView.getUint8(1);
        const h3 = dataView.getUint8(2);
        const h4 = dataView.getUint8(3);
        if (h1 === 0xFD && h2 === 0xFC && h3 === 0xFB && h4 === 0xFA) {
            this.hardwareType = 'modelA';
            this.handleBinaryData(buffer);
            return;
        }
    }

    // Treat as Text (Model B)
    const str = this.ab2str(buffer);
    this.lineBuffer += str;
    
    if (this.lineBuffer.includes('\n')) {
        const lines = this.lineBuffer.split('\n');
        this.lineBuffer = lines.pop(); // Keep incomplete line
        
        for (let line of lines) {
            line = line.trim();
            if (line.length > 0) {
                this.handleTextLine(line);
            }
        }
    }
  }
  
  ab2str(buf) {
    return String.fromCharCode.apply(null, new Uint8Array(buf));
  }
  
  handleTextLine(line) {
      this.log('RX: ' + line);
      this.hardwareType = 'modelB';
      
      if (line.includes("OK")) {
          // Handshake or Command success
          this.isSending = false;
          this.processQueue();
      }
      
      // Parse parameters
      // Example: +R1:2.50
      if (line.startsWith("+R1:")) {
          const val = parseFloat(line.split(":")[1]);
          this.deviceParams.range1 = val;
          this.notifyParamsUpdate();
      }
      if (line.startsWith("+R2:")) {
          const val = parseFloat(line.split(":")[1]);
          this.deviceParams.range2 = val;
          this.notifyParamsUpdate();
      }
      if (line.startsWith("+R3:")) {
          const val = parseFloat(line.split(":")[1]);
          this.deviceParams.range3 = val;
          this.notifyParamsUpdate();
      }
      // ... Add other parsers as needed
  }
  
  handleBinaryData(buffer) {
      // Implement Model A parsing if needed
      // Based on Swift handleBinaryA
      const bytes = new Uint8Array(buffer);
      if (bytes.length < 12) return;
      
      const cmdLo = bytes[6];
      const cmdHi = bytes[7];
      // ... logic
      
      this.isSending = false;
      this.processQueue();
  }
  
  notifyParamsUpdate() {
      if (this.onParamsLoaded) {
          this.onParamsLoaded(this.deviceParams);
      }
  }

  sendRaw(str) {
    if (!this.deviceId || !this.writeCharacteristicId) return;
    
    const buffer = new ArrayBuffer(str.length);
    const dataView = new DataView(buffer);
    for (let i = 0; i < str.length; i++) {
      dataView.setUint8(i, str.charCodeAt(i));
    }

    this.write(buffer);
  }
  
  write(buffer) {
      wx.writeBLECharacteristicValue({
        deviceId: this.deviceId,
        serviceId: this.serviceId,
        characteristicId: this.writeCharacteristicId,
        value: buffer,
        success: () => {
            // this.log('Write success');
        },
        fail: (err) => {
            this.log('Write failed: ' + JSON.stringify(err));
            this.isSending = false;
        }
      });
  }

  queueCommand(cmd) {
      this.pendingCommands.push(cmd);
      this.processQueue();
  }
  
  processQueue() {
      if (this.isSending) return;
      if (this.pendingCommands.length === 0) return;
      
      const cmd = this.pendingCommands.shift();
      this.isSending = true;
      this.sendRaw(cmd);
      
      // Safety timeout
      setTimeout(() => {
          if (this.isSending) {
              this.isSending = false; // Force reset
              this.processQueue();
          }
      }, 500); 
  }
  
  readParameters() {
      // Model B read sequence
      this.queueCommand("AT+R1?\r\n");
      this.queueCommand("AT+R2?\r\n");
      this.queueCommand("AT+R3?\r\n");
      this.queueCommand("AT+ONTH?\r\n");
  }
  
  saveParameters(params) {
      // Model B save sequence
      this.queueCommand(`AT+R1=${Math.round(params.range1 * 100)}\r\n`);
      this.queueCommand(`AT+R2=${Math.round(params.range2 * 100)}\r\n`);
      this.queueCommand(`AT+R3=${Math.round(params.range3 * 100)}\r\n`);
      // ...
  }
}

module.exports = new BLEManager();
