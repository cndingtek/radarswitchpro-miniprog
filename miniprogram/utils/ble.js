
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
    this.hasShownModelAToast = false;
    this.hasShownModelBToast = false;
    this.lastAParamId = null;
    this.pendingCommands = [];
    this.pendingBinaryCommands = [];
    this.isSending = false;
    this.lineBuffer = "";
    
    // Mock Mode
    this.mockMode = false; // Set to true to enable simulation on Mac
    
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
    // Check if we are in dev tools
    try {
        const res = wx.getSystemInfoSync();
        if (res.platform === 'devtools') {
            this.log('Detected DevTools environment. Enabling Mock Mode.');
            this.mockMode = true;
            return Promise.resolve();
        }
    } catch (e) {}

    return new Promise(async (resolve, reject) => {
      try {
        const sys = wx.getSystemInfoSync();
        if (sys.platform === 'android') {
          await this.ensureAndroidLocationPermission();
        }

        await this.openAdapterWithPrompt();
        this.monitorState();
        // Verify adapter state
        wx.getBluetoothAdapterState({
          success: (s) => {
            if (!s.available) {
              this.log('Bluetooth unavailable after open, ask user to enable');
              wx.showModal({
                title: '请打开蓝牙',
                content: '请在系统设置中开启蓝牙后重试',
                showCancel: false
              });
              reject({ msg: 'bluetooth unavailable' });
              return;
            }
            resolve(s);
          },
          fail: (e) => {
            this.log('Get adapter state failed: ' + JSON.stringify(e));
            resolve({});
          }
        });
      } catch (e) {
        reject(e);
      }
    });
  }

  ensureAndroidLocationPermission() {
    return new Promise((resolve) => {
      wx.getSetting({
        success: (st) => {
          if (!st.authSetting['scope.userLocation']) {
            wx.authorize({
              scope: 'scope.userLocation',
              success: () => resolve(true),
              fail: () => {
                wx.showModal({
                  title: '需要位置权限',
                  content: 'Android 扫描蓝牙需要开启定位权限，请授权后重试',
                  success: () => {
                    wx.openSetting({ success: () => resolve(true) });
                  }
                });
              }
            });
          } else {
            resolve(true);
          }
        },
        fail: () => resolve(true)
      });
    });
  }

  openAdapterWithPrompt() {
    return new Promise((resolve, reject) => {
      wx.openBluetoothAdapter({
        success: (res) => resolve(res),
        fail: (err) => {
          this.log('Bluetooth Init failed: ' + JSON.stringify(err));
          wx.showModal({
            title: '蓝牙不可用',
            content: '请确认系统蓝牙已开启，且授予应用蓝牙权限后重试',
            showCancel: false
          });
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

    if (this.mockMode) {
        this.log('Starting Mock Scan...');
        setTimeout(() => {
            const mockDevice = {
                deviceId: 'MOCK-DEVICE-001',
                name: 'CNDingtek Radar (Mock)',
                RSSI: -55,
                advertisData: new ArrayBuffer(0)
            };
            this.handleDiscoveredDevice(mockDevice);
        }, 1500);
        return;
    }

    wx.getBluetoothAdapterState({
      success: (s) => {
        if (!s.available) {
          this.log('Bluetooth unavailable');
          wx.showToast({ title: '请先开启手机蓝牙', icon: 'none' });
          this.isScanning = false;
          return;
        }
        this._doDiscovery();
      },
      fail: () => {
        this._doDiscovery();
      }
    });
  }

  _doDiscovery() {
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
    
    if (this.mockMode) {
        this.isScanning = false;
        this.log('Mock Scanning stopped');
        return;
    }

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
    
    if (this.mockMode) {
        setTimeout(() => {
            this.deviceId = device.deviceId;
            this.connectedDevice = device;
            this.log('Mock Connected. Discovering services...');
            setTimeout(() => {
                this.log('Mock Services Discovered');
                this.log('Mock Characteristics Discovered');
                this.log('Mock Notification enabled');
                if (this.onStateChanged) this.onStateChanged(true);
                
                // Simulate initial handshake/read
                this.hardwareType = 'modelB';
                this.deviceParams = {
                    range1: 2.5, range2: 4.0, range3: 6.0,
                    sensitivity: 75, enterDelay: 5, exitDelay: 30
                };
                if (this.onParamsLoaded) this.onParamsLoaded(this.deviceParams);
                this.log('Mock Params Loaded');
            }, 1000);
        }, 1000);
        return;
    }

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

  getToastMsg(type) {
      try {
          const sys = wx.getSystemInfoSync();
          const isZh = sys.language && sys.language.indexOf('zh') !== -1;
          if (type === 'A') {
              return isZh ? '雷达类型:A' : 'Model A';
          } else {
              return isZh ? '雷达类型:B' : 'Model B';
          }
      } catch (e) {
          return type === 'A' ? 'Model A' : 'Model B';
      }
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
            if (!this.hasShownModelAToast) {
                wx.showToast({ title: this.getToastMsg('A'), icon: 'none', duration: 1500 });
                this.hasShownModelAToast = true;
            }
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
      let displayMsg = 'RX: ' + line;
      if (line.includes("Range")) {
          const match = line.match(/Range\s*(\d+(\.\d+)?)/i);
          if (match) {
             displayMsg = `距离 ${match[1]}cm`;
          }
      } else if (line.trim() === "ON") {
          displayMsg = "检测到运动";
      } else if (line.trim() === "OFF") {
          displayMsg = "无目标";
      }
      this.log(displayMsg);

      if (line.includes("Range")) {
          if (this.hardwareType !== 'modelA') {
              this.hardwareType = 'modelA';
              if (!this.hasShownModelAToast) {
                  wx.showToast({ title: this.getToastMsg('A'), icon: 'none', duration: 1500 });
                  this.hasShownModelAToast = true;
              }
              this.notifyParamsUpdate();
          }
          // Optional: Parse Range value
          return;
      }
      
      this.hardwareType = 'modelB';
      if (line.includes("OK") || line.startsWith("+R") || line.startsWith("+ONTH") || line.startsWith("+SENS")) {
          if (!this.hasShownModelBToast) {
              wx.showToast({ title: this.getToastMsg('B'), icon: 'none', duration: 1500 });
              this.hasShownModelBToast = true;
          }
      }
      
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
      const bytes = new Uint8Array(buffer);
      if (bytes.length < 12) return;
      
      const cmdLo = bytes[6];
      const cmdHi = bytes[7];
      const statusLo = bytes[8];
      
      if (cmdLo === 0x08 && cmdHi === 0x01 && statusLo === 0x00) {
          const val = bytes[10];
          if (this.lastAParamId !== null) {
              if (this.lastAParamId === 0x0001) { // Max
                  this.deviceParams.range2 = val * 0.75;
              } else if (this.lastAParamId === 0x0000) { // Min
                  this.deviceParams.range1 = val * 0.75;
              } else if (this.lastAParamId === 0x0004) { // Delay
                  this.deviceParams.exitDelay = val;
              }
              this.notifyParamsUpdate();
              this.lastAParamId = null;
          }
      }
      
      this.isSending = false;
      this.processQueue();
  }
  
  notifyParamsUpdate() {
      if (this.onParamsLoaded) {
          this.onParamsLoaded(this.deviceParams);
      }
  }

  sendRaw(data) {
    if (this.mockMode) {
        const msg = (typeof data === 'string') ? data.trim() : 'BINARY [' + data.byteLength + ']';
        this.log(`[MOCK TX] ${msg}`);
        this.isSending = false;
        if (typeof data === 'string' && data.includes("?")) {
             setTimeout(() => { this.processQueue(); }, 100);
        } else if (typeof data !== 'string') {
             setTimeout(() => { this.processQueue(); }, 100);
        }
        return;
    }

    if (!this.deviceId || !this.writeCharacteristicId) return;
    
    let buffer;
    if (typeof data === 'string') {
        buffer = new ArrayBuffer(data.length);
        const dataView = new DataView(buffer);
        for (let i = 0; i < data.length; i++) {
            dataView.setUint8(i, data.charCodeAt(i));
        }
    } else {
        buffer = data;
    }

    this.write(buffer);
    
    // Track Model A Read Request
    if (this.hardwareType === 'modelA' && buffer.byteLength >= 10) {
        const bytes = new Uint8Array(buffer);
        // Command 0x08 0x00 is Read
        if (bytes[6] === 0x08 && bytes[7] === 0x00) {
             const idLo = bytes[8];
             const idHi = bytes[9];
             this.lastAParamId = (idHi << 8) | idLo;
        }
    }
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
      if (this.hardwareType === 'modelA') {
          // Model A Read Sequence
          const cmdMax = new Uint8Array([0xFD,0xFC,0xFB,0xFA,0x04,0x00,0x08,0x00,0x01,0x00,0x04,0x03,0x02,0x01]);
          const cmdMin = new Uint8Array([0xFD,0xFC,0xFB,0xFA,0x04,0x00,0x08,0x00,0x00,0x00,0x04,0x03,0x02,0x01]);
          const cmdDelay = new Uint8Array([0xFD,0xFC,0xFB,0xFA,0x04,0x00,0x08,0x00,0x04,0x00,0x04,0x03,0x02,0x01]);
          this.queueCommand(cmdMax.buffer);
          this.queueCommand(cmdMin.buffer);
          this.queueCommand(cmdDelay.buffer);
      } else {
          // Model B Read Sequence
          this.queueCommand("AT+R1?\r\n");
          this.queueCommand("AT+R2?\r\n");
          this.queueCommand("AT+R3?\r\n");
          this.queueCommand("AT+ONTH?\r\n");
          this.queueCommand("AT+SENS?\r\n");
      }
  }

  saveParameters(params) {
      if (this.hardwareType === 'modelA') {
          // 1. Enter Config
          this.queueCommand(new Uint8Array([0xFD,0xFC,0xFB,0xFA,0x04,0x00,0xFF,0x00,0x01,0x00,0x04,0x03,0x02,0x01]).buffer);
          
          const minVal = Math.max(0, Math.round(params.range1 / 0.75));
          const maxVal = Math.max(0, Math.round(params.range2 / 0.75)); 
          const delayVal = Math.max(0, Math.min(255, params.exitDelay));
          
          // 2. Set Min (0x0000)
          const cmdMin = new Uint8Array([0xFD,0xFC,0xFB,0xFA,0x0E,0x00,0x07,0x00,0x00,0x00,minVal,0x00,0x00,0x00,0x2F,0x00,0x64,0x00,0x00,0x00,0x04,0x03,0x02,0x01]);
          this.queueCommand(cmdMin.buffer);
          
          // 3. Set Max (0x0001)
          const cmdMax = new Uint8Array([0xFD,0xFC,0xFB,0xFA,0x0E,0x00,0x07,0x00,0x01,0x00,maxVal,0x00,0x00,0x00,0x2F,0x00,0x64,0x00,0x00,0x00,0x04,0x03,0x02,0x01]);
          this.queueCommand(cmdMax.buffer);
          
          // 4. Set Delay (0x0004)
          const cmdDelay = new Uint8Array([0xFD,0xFC,0xFB,0xFA,0x0E,0x00,0x07,0x00,0x04,0x00,delayVal,0x00,0x00,0x00,0x2F,0x00,0x64,0x00,0x00,0x00,0x04,0x03,0x02,0x01]);
          this.queueCommand(cmdDelay.buffer);
          
          // 5. Exit Config
          this.queueCommand(new Uint8Array([0xFD,0xFC,0xFB,0xFA,0x02,0x00,0xFE,0x00,0x04,0x03,0x02,0x01]).buffer);
          
      } else {
          // Model B
          this.queueCommand(`AT+R1=${Math.round(params.range1 * 100)}\r\n`);
          this.queueCommand(`AT+R2=${Math.round(params.range2 * 100)}\r\n`);
          this.queueCommand(`AT+R3=${Math.round(params.range3 * 100)}\r\n`);
          this.queueCommand(`AT+ONTH=${params.exitDelay}\r\n`);
          this.queueCommand(`AT+SENS=${params.sensitivity}\r\n`);
          this.queueCommand("AT+RESET\r\n");
      }
  }
}

module.exports = new BLEManager();
