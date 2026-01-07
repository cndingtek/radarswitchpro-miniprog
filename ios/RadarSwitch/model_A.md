# 模型 A (Model A) 日志解析、参数读取与配置流程说明

本文档详细梳理了当前代码版本中针对蓝牙设备的日志解析、参数读取及配置的具体流程和指令集。

## 1. 协议基础结构

通信协议基于二进制字节流，采用小端序（Little Endian）格式。

*   **起始位 (Header)**: `FD FC FB FA` (4 bytes)
*   **结束位 (Tail)**: `04 03 02 01` (4 bytes)
*   **通用包结构**:
    `[Header] [Length] [Command] [Data/Params...] [Tail]`

## 2. 日志解析流程 (Log Parsing)

日志解析主要在 `ViewModel.kt` 的 `processPacket` 方法中进行，支持 **ASCII 文本模式** 和 **二进制协议模式**。

### 2.1 ASCII 模式
当数据包以文本形式开头时触发：
*   **ON**: 检测到运动。
*   **OFF**: 无目标。
*   **Range <数值>**: 实时测距数据。
    *   解析逻辑: 提取 `Range` 后的数值。
    *   转换: 数值=厘米。
    *   显示：距离XXcm.

### 2.2 协议模式
当数据包以 `FD FC FB FA` 开头时触发。解析步骤如下：
1.  **分片**:
    *   **Header**: 0-3 字节
    *   **Length**: 4-5 字节 (表示后续指令+数据的长度)
    *   **Command**: 6-7 字节 (指令字)
    *   **Status**: 8-9 字节 (响应状态，`00 00` 为成功)
    *   **Value**: 10 字节至 (总长度-4) 字节
    *   **Tail**: 最后 4 字节

2.  **指令响应解析**:
    *   **`00 01` (Read Firmware)**:
        *   将 `Value` 段转换为 ASCII 字符串，作为固件版本号。
    *   **`FF 01` (Start Configuration)**:
        *   Byte 0: 协议号。
        *   Byte 1-2: 缓冲区长度。
    *   **`08 01` (Read Parameter)**:
        *   **距离参数 (Max/Min Distance)**:
            *   读取第一个字节为整型数值。
            *   转换公式: `数值 * 0.75` = 米 (m)。
        *   **消失时间 (Object Disappear Time)**:
            *   直接读取第一个字节，单位为秒 (s)。

## 3. 具体指令集 (Commands)

所有指令均包含 Header 和 Tail，以下列出核心部分的十六进制表示。

### 3.1 系统指令
| 功能 | 指令字 (Cmd) | 完整数据包 (Hex) | 说明 |
| :--- | :--- | :--- | :--- |
| **读取固件版本** | `00 00` | `FD FC FB FA 02 00 00 00 04 03 02 01` | 响应指令: `00 01` |
| **开始配置模式** | `FF 00` | `FD FC FB FA 04 00 FF 00 01 00 04 03 02 01` | 参数 `01 00` |
| **结束配置模式** | `FE 00` | `FD FC FB FA 02 00 FE 00 04 03 02 01` | 保存并退出 |

### 3.2 参数读取指令 (Read Parameters)
指令字均为 `08 00`，通过参数 ID 区分。

| 参数名称 | 参数 ID | 完整数据包 (Hex) |
| :--- | :--- | :--- |
| **读取最大距离** | `01 00` | `FD FC FB FA 04 00 08 00 01 00 04 03 02 01` |
| **读取最小距离** | `00 00` | `FD FC FB FA 04 00 08 00 00 00 04 03 02 01` |
| **读取消失时间** | `04 00` | `FD FC FB FA 04 00 08 00 04 00 04 03 02 01` |

### 3.3 参数设置指令 (Set Parameters)
指令字均为 `07 00`。设置时通常会附带“保持阈值”的参数 (`2F 00` = 100)。

#### 设置最大距离 (Max Distance)
*   **参数 ID**: `01 00`
*   **结构**: `Header` + `Length(0E 00)` + `Cmd(07 00)` + `ID(01 00)` + `Value(4 bytes)` + `ID(2F 00)` + `Value(64 00 00 00)` + `Tail`
*   **示例 (设置值为 12)**:
    `FD FC FB FA 0E 00 07 00 01 00 0C 00 00 00 2F 00 64 00 00 00 04 03 02 01`

#### 设置最小距离 (Min Distance)
*   **参数 ID**: `00 00`
*   **结构**: 同上，仅 ID 变为 `00 00`。

#### 设置消失时间 (Object Disappear Time)
*   **参数 ID**: `04 00`
*   **结构**: 同上，仅 ID 变为 `04 00`。

#### 设置自动阈值 (Auto Threshold)
*   **指令字**: `09 00`
*   **结构**: `Header` + `Length(06 00)` + `Cmd(09 00)` + `Trigger(2 bytes)` + `Hold(2 bytes)` + `Tail`
*   **示例**:
    `FD FC FB FA 06 00 09 00 [TriggerLow] [TriggerHigh] [HoldLow] [HoldHigh] 04 03 02 01`

## 4. 配置操作流程 (Workflow)

### 4.1 连接设备
1.  扫描并连接蓝牙设备。
2.  应用自动监听 `InputStream`。

### 4.2 读取参数流程
1.  用户点击“读取”按钮 (如 Read Max Distance)。
2.  App 发送 `08 00` 读指令。
3.  App 接收 `08 01` 响应。
4.  App 解析响应数据，根据当前上下文 (`isDistanceCommand` 标记) 将数据转换为米或秒显示在 UI 上。

### 4.3 修改参数流程
1.  用户拖动滑块或输入数值。
2.  **App 不会自动进入配置模式** (根据代码逻辑，发送写指令前未强制检查是否在配置模式，但通常建议先发送 Start Config)。
    *   *注：部分设备可能要求先发送 `FF 00` 才能接受写指令。*
3.  App 发送 `07 00` 写指令 (包含新数值和默认阈值参数)。
4.  App 接收 `07 01` 响应确认写入成功。
5.  用户操作完成后，通常建议发送 `FE 00` (End Config) 以保存设置。

## 5. 代码引用

*   **指令生成**: [Commands.kt](app/src/main/java/com/alpha/controller/helper/Commands.kt)
*   **逻辑处理**: [ViewModel.kt](app/src/main/java/com/alpha/controller/viewmodel/ViewModel.kt)
