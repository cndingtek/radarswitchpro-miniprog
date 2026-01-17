const bleManager = require('../../utils/ble.js')
const theme = require('../../styles/theme.js')

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
    logs: [],
    theme
  },

  onLoad(options) {
    this.setData({
      deviceId: options.id,
      deviceName: decodeURIComponent(options.name || '')
    });
    
    // Set title based on language, not device name
    try {
        const sys = wx.getSystemInfoSync();
        const isZh = sys.language && sys.language.indexOf('zh') !== -1;
        const title = isZh ? "雷达开关助手专业版" : "RadarSwitch Pro";
        wx.setNavigationBarTitle({ title });
    } catch (e) {
        wx.setNavigationBarTitle({ title: 'RadarSwitch Pro' });
    }

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
      if (bleManager.hardwareType === 'modelA') {
          if (params.range2 === 0 && params.range1 === 0) {
              params.range1 = 0;
              params.range2 = 10;
          }
      }
      this.setData({
        params: params,
        hardwareType: bleManager.hardwareType
      });
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

  onSliderChange(e) {
    if (!this.data.isEditable) return;
    const vals = e.detail.values;
    if (this.data.hardwareType === 'modelA') {
        this.setData({
            'params.range1': vals[0], // Min
            'params.range2': vals[1]  // Max
        });
    } else {
        this.setData({
            'params.range1': vals[0], // Near
            'params.range2': vals[1], // Mid
            'params.range3': vals[2]  // Far
        });
    }
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
      content: '所有设置将被重置 (最小0m, 最大3m, 延迟10s)',
      success: (res) => {
        if (res.confirm) {
           wx.showLoading({ title: '恢复中...' });
           const defaults = {
               range1: 0,
               range2: 3,
               range3: 6, 
               sensitivity: 75,
               exitDelay: 10
           };
           bleManager.saveParameters(defaults);
           setTimeout(() => {
               wx.hideLoading();
               wx.showToast({ title: '已发送重置指令' });
               bleManager.readParameters();
           }, 1000);
        }
      }
    });
  }
})
