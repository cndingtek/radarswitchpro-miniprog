# 雷达开关助手专业版 (RadarSwitch Pro) - 小程序版

[中文](#zh) | [English](#en)

<a id="zh"></a>
# 雷达开关助手专业版 (RadarSwitch Pro)

这是用于配置 CNDingtek 雷达开关设备的微信小程序。支持通过蓝牙（BLE）连接设备，自动识别设备类型（Model A / Model B），并提供参数配置、日志监控及恢复出厂设置等功能。

## 主要功能

*   **设备扫描与连接**：自动扫描附近的雷达开关设备，支持名称过滤与快速连接。
*   **智能识别**：
    *   自动识别 **Model A** 设备（提示“雷达类型:A”）。
    *   自动识别 **Model B** 设备（提示“雷达类型:B”）。
*   **参数配置**：
    *   **只读/可编辑模式**：防止误操作，需手动切换至“可编辑”模式。
    *   **Model A**：支持最小距离、最大距离（双滑块调节）、延迟时间设置。
    *   **Model B**：支持近距、中距、远距（三滑块调节）、离开延迟、灵敏度设置。
*   **日志监控**：
    *   实时显示设备返回数据。
    *   **智能解析 (Model A)**：`Range xx` -> 距离xxcm，`ON` -> 检测到运动，`OFF` -> 无目标。
    *   **智能解析 (Model B)**：`0` -> 无目标，`1,xxcm,yy` -> 检测到运动，距离xxcm，`2,xxcm,yy` -> 检测到微动，距离xxcm。
*   **一键维护**：
    *   **保存设置**：将当前参数写入设备。
    *   **恢复出厂**：一键重置设备参数（默认：最小0m，最大3m，延迟10s）。
*   **多语言支持**：根据系统语言自动切换标题（中文“雷达开关助手专业版” / 英文 "RadarSwitch Pro"）。

## 使用说明

1.  打开手机蓝牙与定位权限（Android需要）。
2.  进入小程序，点击“重新扫描”查找设备。
3.  点击“连接”进入设备详情页。
4.  **查看参数**：默认为只读模式。
5.  **修改参数**：点击右上角“可编辑”，调整滑块或输入数值，点击“保存设置”。
6.  **恢复默认**：在可编辑模式下，点击“恢复出厂”。

## 版本信息
*   当前版本：V1.1.2
*   版权所有：2026 北京鼎恒泰科技有限公司

---

<a id="en"></a>
# RadarSwitch Pro (Mini Program)

WeChat Mini Program for configuring CNDingtek Radar Switch devices. Supports BLE connection, automatic device type detection (Model A / Model B), parameter configuration, log monitoring, and factory reset.

## Features

*   **Scan & Connect**: Auto-scan nearby devices via BLE.
*   **Smart Detection**:
    *   Auto-detect **Model A** (Toast: "Model A").
    *   Auto-detect **Model B** (Toast: "Model B").
*   **Parameter Configuration**:
    *   **Read-Only/Editable Mode**: Toggle to prevent accidental changes.
    *   **Model A**: Min Distance, Max Distance (Dual Slider), Delay Time.
    *   **Model B**: Near, Mid, Far Distance (Triple Slider), Exit Delay, Sensitivity.
*   **Log Monitoring**:
    *   Real-time data logs.
    *   **Smart Parsing (Model A)**: `Range xx` -> distance xxcm, `ON` -> motion detected, `OFF` -> no object.
    *   **Smart Parsing (Model B)**: `0` -> no object, `1,xxcm,yy` -> motion detected, distance xxcm, `2,xxcm,yy` -> presence detected, distance xxcm.
*   **Maintenance**:
    *   **Save Settings**: Write parameters to device.
    *   **Restore Defaults**: Reset parameters (Min 0m, Max 3m, Delay 10s).
*   **Multi-language**: Auto-switch title based on system language (ZH/EN).

## Usage

1.  Enable Bluetooth (and Location for Android).
2.  Scan and connect to a device.
3.  **View**: Read-only by default.
4.  **Edit**: Toggle "Editable", adjust sliders, click "Save Settings".
5.  **Reset**: Click "Restore Defaults" in editable mode.

## Version Info
*   Version: V1.1.2
*   Copyright: 2026 Beijing Dinghengtai Technology Co., Ltd.
