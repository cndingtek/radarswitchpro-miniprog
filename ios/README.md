# RadarSwitch iOS

专业的雷达设备管理工具 - iOS版本

## 功能特性

- 🔍 **蓝牙设备扫描** - 快速发现附近的DC59X系列雷达设备
- 📱 **设备连接管理** - 稳定的蓝牙连接和断线重连
- ⚙️ **参数配置** - 灵活的延迟设置和参数调整
- 📊 **实时日志** - 监控设备状态和数据变化
- 🎨 **iOS原生设计** - 符合iOS Human Interface Guidelines
- 🌓 **深色模式** - 支持系统外观设置

## 技术架构

- **SwiftUI** - 声明式UI框架
- **CoreBluetooth** - 原生蓝牙通信
- **Combine** - 响应式编程
- **MVVM** - 设计模式
- **SwiftData** - 数据持久化 (iOS 17+)

## 系统要求

- iOS 16.0+
- iPhone 8 或更新机型
- iPad (支持iPadOS 16+)

## 开发环境

- Xcode 15.0+
- Swift 5.9+
- macOS 13.0+

## 快速开始

### 1. 克隆项目
```bash
git clone https://github.com/your-repo/radarlink.git
cd radarlink/ios
```

### 2. 打开项目
```bash
open RadarSwitch.xcodeproj
```

### 3. 配置签名
1. 在Xcode中选择项目
2. 进入"Signing & Capabilities"标签
3. 选择你的开发团队
4. 更新Bundle Identifier（如果需要）

### 4. 构建和运行
- 选择目标设备或模拟器
- 点击运行按钮 (⌘+R)

## 项目结构

```
RadarSwitch/
├── RadarSwitchApp.swift          # 应用入口
├── ContentView.swift              # 主界面
├── BLEManager.swift               # 蓝牙管理器
├── DeviceModels.swift            # 数据模型
├── ScanView.swift                # 设备扫描
├── ParametersView.swift          # 参数设置
├── LogsView.swift                # 日志监控
├── SettingsView.swift             # 设置界面
├── WheelPicker.swift             # 自定义选择器
└── Assets.xcassets/              # 资源文件
```

## 蓝牙通信协议

应用使用AT命令集与DC59X设备通信：

- **设备发现** - 扫描BLE设备并识别服务
- **连接管理** - 建立和维护GATT连接
- **参数配置** - 发送配置命令和接收响应
- **数据监控** - 实时接收设备状态数据

## 界面操作说明

- 蓝牙扫描
  - 仅显示名称以 CNDingtek 或 DC59 开头的设备（不区分大小写）
  - 离开扫描页自动停止扫描；返回后不会自动开始，需手动点击“开始”
  - 扫描状态在界面与内部状态同步，点击“停止”立即停止
- 我的设备
  - 可删除已配对设备；若删除的是当前选中设备，则断开连接并清空详情页
  - 删除后的设备不会在“设备详情”页显示；如需再次显示需在扫描页重新扫描并配对
- 设备详情
  - 模式切换：参数/日志与监控
  - 只读模式：点击“只读”后发起查询（见设计文档）
  - 可编辑模式：支持参数修改与保存（见设计文档）
  - 日志展示：去重处理，避免因相同文本导致渲染警告
- 设置
  - 关于：版本号动态读取、开发者中文名称“深圳鼎恒泰物联科技有限公司”
  - 帮助与教程：提供“软件使用教程”与“硬件安装说明”两个胶囊按钮（链接打开内联 PDF）

## 设计文档

详见 [Design](RadarSwitch/Design.md)。

## 版本 1.1.2 更新

- 日志文案统一（A/B 型）：
  - 中文：ON → 检测到运动，OFF/0 → 无目标，距离行统一为 距离XXcm
  - 英文：ON → motion，OFF/0 → no object，距离行统一为 distance XXcm
  - B 型 code=2 细化为 presence（中文：检测到微动）
- A 型识别与数据：
  - ASCII Range N 行识别并归类为 A 型，距离写入曲线
  - 只读查询（08 00）：读取最小/最大距离（值×0.75m）与目标消失时间（秒）
  - 保存（FF 00→07 00→FE 00）：距离按 米/0.75 四舍五入为单位写入，消失时间按秒写入，附带保持阈值 2F 00=100
- 教程与关于：
  - 教程页使用真实截图缩略预览（scan/devices/logs/params/settings_language）
  - 关于页支持点击网站/邮箱链接，二维码长按保存或分享（微信/Whatsapp）
- 设置：
  - 版本号更新为 1.1.2（CFBundleShortVersionString）

## 构建脚本

使用提供的构建脚本自动化构建过程：

```bash
chmod +x build.sh
./build.sh
```

## 测试

### 单元测试
```bash
xcodebuild test -project RadarSwitch.xcodeproj -scheme RadarSwitch -destination 'platform=iOS Simulator,name=iPhone 15'
```

### UI测试
```bash
xcodebuild test -project RadarSwitch.xcodeproj -scheme RadarSwitchUITests -destination 'platform=iOS Simulator,name=iPhone 15'
```

## 发布

### TestFlight测试
1. 在Xcode中配置App Store Connect
2. 创建应用存档
3. 上传到App Store Connect
4. 通过TestFlight分发测试

### App Store发布
1. 准备应用元数据
2. 创建应用截图
3. 提交应用审核
4. 发布到App Store

## 贡献指南

欢迎提交Issue和Pull Request：

1. Fork项目
2. 创建特性分支 (`git checkout -b feature/amazing-feature`)
3. 提交更改 (`git commit -m 'Add some amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing-feature`)
5. 创建Pull Request

## 许可证

本项目采用MIT许可证 - 详见 [LICENSE](../LICENSE) 文件

## 支持

- 📧 邮箱: support@dingtek.com
- 🌐 网站: https://dingtek.com
- 📱 技术支持: 在应用内提交反馈

## 更新日志

查看 [CHANGELOG](../CHANGELOG.md) 了解版本更新内容

---

**RadarSwitch iOS** - 让雷达设备管理更简单！
