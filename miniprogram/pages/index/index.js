const app = getApp()
const bleManager = require('../../utils/ble.js')

Page({
  data: {
    devices: [],
    isScanning: false
  },

  onLoad() {
    this.initBLE();
  },

  onShow() {
    // Refresh list if needed
    if (!this.data.isScanning && this.data.devices.length === 0) {
       this.startScan();
    }
  },
  
  onPullDownRefresh() {
    this.startScan();
    wx.stopPullDownRefresh();
  },

  initBLE() {
    bleManager.onDevicesUpdated = (devices) => {
      this.setData({ devices: devices });
    };

    bleManager.init()
      .then(() => {
        this.startScan();
      })
      .catch(err => {
        wx.showToast({
          title: '蓝牙初始化失败',
          icon: 'none'
        });
      });
  },

  startScan() {
    this.setData({ isScanning: true });
    bleManager.startScan();
  },

  connectDevice(e) {
    const deviceId = e.currentTarget.dataset.id;
    const device = this.data.devices.find(d => d.deviceId === deviceId);
    
    if (device) {
      wx.showLoading({ title: '连接中...' });
      
      bleManager.onStateChanged = (connected) => {
        wx.hideLoading();
        if (connected) {
          wx.navigateTo({
            url: `/pages/device/device?id=${deviceId}&name=${encodeURIComponent(device.name)}`
          });
        } else {
            // If disconnected unexpectedly
            wx.showToast({ title: '断开连接', icon: 'none' });
        }
      };

      bleManager.connect(device);
    }
  }
})
