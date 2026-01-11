const bleManager = require('../../utils/ble.js')

Page({
  data: {
    deviceId: '',
    deviceName: '',
    isEditable: false,
    hardwareType: 'unknown',
    params: {
      range1: 0,
      range2: 0,
      range3: 0,
      sensitivity: 0,
      exitDelay: 0
    },
    logs: []
  },

  onLoad(options) {
    this.setData({
      deviceId: options.id,
      deviceName: decodeURIComponent(options.name || '')
    });
    
    wx.setNavigationBarTitle({
      title: this.data.deviceName
    });

    this.setupBLE();
  },

  onUnload() {
    bleManager.disconnect();
  },

  setupBLE() {
    // Initial read
    setTimeout(() => {
        bleManager.readParameters();
    }, 500);

    bleManager.onParamsLoaded = (params) => {
      this.setData({
        params: params,
        hardwareType: bleManager.hardwareType
      });
      this.addLog("Parameters loaded");
    };

    bleManager.onLog = (msg) => {
      this.addLog(msg);
    };
    
    // Periodically update logs if needed or just rely on events
  },

  addLog(msg) {
    const time = new Date().toLocaleTimeString();
    const newLog = `[${time}] ${msg}`;
    const logs = [newLog, ...this.data.logs].slice(0, 50); // Keep last 50
    this.setData({ logs });
  },

  setReadOnly() {
    this.setData({ isEditable: false });
    bleManager.readParameters(); // Refresh
  },

  setEditable() {
    this.setData({ isEditable: true });
    // Handshake might be needed
    // bleManager.sendRaw("AA\r\n"); 
    // Already handled in ble.js or logic
  },

  onRange1Change(e) {
    this.setData({ 'params.range1': e.detail.value });
  },

  onRange2Change(e) {
    this.setData({ 'params.range2': e.detail.value });
  },

  onRange3Change(e) {
    this.setData({ 'params.range3': e.detail.value });
  },

  onExitDelayChange(e) {
    this.setData({ 'params.exitDelay': parseInt(e.detail.value) || 0 });
  },

  onSensitivityChange(e) {
    this.setData({ 'params.sensitivity': parseInt(e.detail.value) || 0 });
  },

  saveSettings() {
    if (!this.data.isEditable) return;
    wx.showLoading({ title: '保存中...' });
    bleManager.saveParameters(this.data.params);
    setTimeout(() => {
        wx.hideLoading();
        wx.showToast({ title: '已发送保存指令' });
    }, 1000);
  },

  restoreSettings() {
    if (!this.data.isEditable) return;
    wx.showModal({
      title: '确认恢复出厂?',
      content: '所有设置将被重置',
      success: (res) => {
        if (res.confirm) {
           // Implement restore in BLE
           // bleManager.restoreDefaults();
           wx.showToast({ title: '功能开发中', icon: 'none' });
        }
      }
    });
  }
})
