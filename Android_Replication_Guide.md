# RadarLinkPro - Android 版本复刻设计文档 (Design Document)

**目标**: 复刻 iOS 版本 `RadarSwitch` (v1.1.2) 的功能与 UI，开发对应的 Android 版本。
**核心功能**: 蓝牙雷达设备的扫描、连接、参数配置（支持 Model A/B 双协议）、实时监控与波形图显示。

## 1. 整体架构 (Architecture)

建议使用 **Kotlin + Jetpack Compose** 进行现代化开发。

*   **架构模式**: MVVM (Model-View-ViewModel)
*   **导航**: Single Activity (`MainActivity`) + `Jetpack Navigation Compose` (底部导航栏)。
*   **蓝牙管理**: 单例 `BLEManager` (Repository 层)，负责所有 BLE 操作，通过 `StateFlow` 或 `SharedFlow` 向 ViewModel 暴露数据。

## 2. 视觉设计规范 (UI Design System)

### 2.1 颜色系统 (Color Palette)
请在 `ui/theme/Color.kt` 中定义以下颜色，保持与 iOS 一致的深色科技风。

| 颜色名称 (Token) | Hex Code | 说明 |
| :--- | :--- | :--- |
| **Main Background** | Gradient: `#050F24` (Top) -> `#071A33` (Bottom) | 全局背景渐变 |
| **Card Background** | `#0C2343` | 列表项、设置卡片背景 |
| **Primary Blue** | `#2D7BFF` | 按钮、选中状态、波形图线条 |
| **Primary Pressed** | `#1A60D6` | 按钮按下状态 |
| **Text White** | `#FFFFFF` | 主要标题、数值 |
| **Text Secondary** | `#9BA7C8` | 副标题、标签 |
| **Text Gray** | `#7A8196` | 辅助说明、未选中状态 |
| **Status Online** | `#38C976` | 设备在线、连接成功 |
| **Status Offline** | `#7A8196` | 设备离线 |
| **Divider/Grid** | `Color.Gray` (alpha 0.2) | 分割线、图表网格 |

### 2.2 字体 (Typography)
使用系统默认无衬线字体 (Roboto/San Francisco)，字重如下：
*   **Title Large**: 20sp, SemiBold
*   **Title Medium**: 18sp, SemiBold
*   **Body**: 14sp, Regular
*   **Caption**: 12sp, Regular

### 2.3 资源文件 (Assets)
请准备以下图片资源（放入 `res/drawable`），文件名需严格一致以匹配逻辑：

*   **教程/引导图**:
    *   `scan_start.jpg` (扫描页截图)
    *   `devices_list.jpg` (设备列表截图)
    *   `logs.jpg` (日志页截图)
    *   `params_locked.jpg` (参数锁定状态截图)
    *   `params_edit.jpg` (参数编辑状态截图)
    *   `settings_language.jpg` (设置页语言切换截图)
*   **二维码**:
    *   `wechat.png` (微信客服二维码)
    *   `whatsapp.png` (WhatsApp 客服二维码)
*   **图标**: 使用 Material Icons (Bluetooth, Settings, List, Info 等)。

---

## 3. 页面导航与功能 (Navigation & Features)

应用底部包含 4 个 Tab：

### Tab 1: 扫描 (Scan)
*   **功能**: 自动/手动扫描蓝牙设备。
*   **UI**: 顶部标题 "Bluetooth Scan"，中间大圆环雷达动画，底部显示 "Scanning..." 或列表。
*   **逻辑**: 
    *   进入页面自动 `startScan()`。
    *   **过滤规则**: 仅显示广播名以 `cndingtek` 或 `dc59` 开头的设备（忽略大小写）。
    *   点击设备 -> 发起连接 -> 连接成功后自动跳转至 **Tab 3 (Detail)**。

### Tab 2: 设备列表 (My Devices)
*   **功能**: 显示历史连接过的设备或收藏设备（当前版本可作为已连接设备列表）。
*   **UI**: 列表展示，每行包含 图标、名称、MAC/UUID、RSSI 信号强度。

### Tab 3: 设备详情 (Detail) - *核心页面*
仅在设备连接后可用。包含顶部 Toggle Switch 切换两个子视图：

#### 子视图 A: 参数 (Params)
*   **状态**: 
    *   **Locked (只读)**: 默认状态，显示 "Locked • View only"。
    *   **Unlocked (编辑)**: 点击 "Save" / "Restore" 后或特定指令解锁。
*   **字段列表**:
    1.  **Sensing Distance (感应距离)**: Range1, Range2, Range3 (Model B) 或 Range1, Range3 (Model A)。单位: 米。
    2.  **Delay (延迟)**: Exit Delay (退出延迟)。单位: 秒。
    3.  **Sensitivity (灵敏度)**: 0-100 (Model B) 或不可调 (Model A)。
*   **操作**: 
    *   `Read`: 页面加载时自动读取。
    *   `Save`: 将输入框数值写入设备。
    *   `Restore`: 恢复出厂设置。

#### 子视图 B: 日志与监控 (Logs & Monitor)
*   **波形图**: 顶部显示实时距离折线图 (X轴: 时间, Y轴: 距离 0-1000cm)。保留最近 300 个点。
*   **日志窗口**: 底部滚动文本区域，显示接收到的原始或解析后的 Log。
*   **日志格式 (统一)**:
    *   中文: `[HH:mm:ss] 检测到运动` / `无目标` / `距离 120cm`
    *   英文: `[HH:mm:ss] motion` / `no object` / `distance 120cm`

### Tab 4: 设置 (Settings)
*   **Language**: 切换 中文 / English (应用内重启 Activity 生效)。
*   **App Guide**: 点击进入教程页 (详见 3.1)。
*   **About**: 
    *   版本号 (v1.1.2)。
    *   **Website**: 中文环境显示 `www.dingtek.com.cn`，英文显示 `www.dingtek.com`。点击蓝色下划线链接打开浏览器。
    *   **Email**: 点击发送邮件。
    *   **QR Code**: 底部根据语言显示 `wechat.png` (CN) 或 `whatsapp.png` (EN)。
        *   **交互**: 长按二维码弹出菜单 "Save Image" / "Share Image"。

### 3.1 特殊功能: App Guide (教程)
*   **形式**: 全屏分页弹窗 (ViewPager)。
*   **内容**: 5页图文，使用上述 `scan_start.jpg` 等资源。
*   **Deep Link**: 教程中的按钮 (如 "Go to Scan") 点击后不仅关闭教程，还要通过 EventBus/SharedFlow 通知 MainActivity 切换到底部对应的 Tab。
    *   Page 1 Button -> Tab 0 (Scan)
    *   Page 2 Button -> Tab 1 (Devices)
    *   Page 3 Button -> Tab 3 (Logs)

---

## 4. 蓝牙通信协议 (BLE Protocol Logic)

这是复刻最关键的部分，必须严格区分 **Model A** (二进制协议) 和 **Model B** (ASCII AT指令协议)。

**UUID 配置**:
*   Service: `0000FFF0-0000-1000-8000-00805F9B34FB`
*   Write Characteristic: `FFF2` (或带有 Write 属性的特征)
*   Notify Characteristic: `FFF1` (或带有 Notify 属性的特征)

### 4.1 握手与模式识别
1.  **连接后**: 订阅 Notify，发送 ASCII `AA\r\n`。
2.  **Model A 识别**:
    *   收到以 `FD FC FB FA` 开头的二进制数据。
    *   或者收到 ASCII 文本符合正则 `(?i)^\s*Range\s+\d+\s*$` (例如 `Range 105`)。
3.  **Model B 识别**:
    *   收到 ASCII 文本符合正则 `^\s*[0-2]\s*,\s*\d+cm` (例如 `1, 105cm`)。

### 4.2 Model A (二进制) 协议细节
*   **基本单位**: 数据包中 1 单位 = 0.75 米 (读取时 `val * 0.75`, 写入时 `val / 0.75`)。
*   **指令头**: `FD FC FB FA`
*   **读取参数 (Read)**:
    *   发送: `FD FC FB FA 04 00 08 00 [ID] 00 04 03 02 01`
    *   ID: `00` (Range1/Min), `01` (Range3/Max), `04` (Exit Delay)。
    *   解析: 响应包第 11 字节 (index 10) 为数值。
*   **写入参数 (Save)**:
    1.  **解锁**: `FD FC FB FA 04 00 FF 00 01 00 04 03 02 01`
    2.  **设置**: `FD FC FB FA 0E 00 07 00 [ID] 00 [VAL] 00 00 00 2F 00 64 00 00 00 04 03 02 01`
    3.  **保存**: `FD FC FB FA 02 00 FE 00 04 03 02 01`

### 4.3 Model B (ASCII) 协议细节
*   **指令格式**: `AT+COMMAND\r\n`。需实现命令队列，等待 `OK` 响应后再发下一条。
*   **读取流程**:
    *   发送: `AT+R1?`, `AT+R2?`, `AT+R3?` (距离, 单位 cm/100 -> m)
    *   发送: `AT+ONTH?` (灵敏度)
    *   发送: `AT+HOLD?` (延迟, 原始值/10 -> 秒)
*   **写入流程**:
    *   `AT+R1=200` (设置 R1 为 2m)
    *   `AT+HOLD=200` (设置延迟 20s)
    *   `AT+ONTH=4`
    *   `AT+RESET` (重启生效)
*   **日志解析 (Notify)**:
    *   格式: `Code, Distance cm, [Optional]`
    *   **Code 0**: 无目标 (No object)
    *   **Code 1**: 运动 (Motion)
    *   **Code 2**: 微动 (Presence)

## 5. Android 特有实现注意点

1.  **权限**:
    *   `AndroidManifest.xml` 需声明:
        *   `BLUETOOTH`, `BLUETOOTH_ADMIN` (Legacy)
        *   `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` (Android 12+)
        *   `ACCESS_FINE_LOCATION` (扫描蓝牙必需)
        *   `WRITE_EXTERNAL_STORAGE` (如果保存图片到旧版系统相册)
2.  **本地化**:
    *   使用 `res/values/strings.xml` (默认英文) 和 `res/values-zh-rCN/strings.xml` (中文)。
3.  **保存图片**:
    *   iOS 使用 `UIImageWriteToSavedPhotosAlbum`。
    *   Android 需使用 `MediaStore` API 将 Bitmap 插入到相册，并处理 `ContentResolver`。
