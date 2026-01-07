# Model_B（日志解析与参数配置流程说明）

## 概览
- 日志解析与参数配置的核心入口集中在 BLE 管理器的接收回调与命令队列。
- 解析和写入严格区分“只读查询”与“可编辑/保存/恢复”场景，避免互相覆盖。
- 为保证稳定性：查询类响应采用短超时+有限重试策略，解析到响应后立即推进队列。

## 日志解析逻辑
- 接收入口：特征值更新回调内处理整行响应并逐条解析
  - 代码参考：BLE 响应处理 [BLEManager.swift](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L458-L487)、[handleResponse](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L489-L854)
- 状态行
  - 含 ON → 追加“状态：开启”；含 OFF → 追加“状态：关闭”
  - 代码参考：[BLEManager.swift](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L821-L847)
- 监控数据行
  - 规范格式：`code, distcm[, ...]`，其中 code ∈ {0,1,2}
  - 文案规则：
    - 中文：code=1 → “检测到运动，距离Xcm”；code=2 → “检测到微动，距离Xcm”；code=0 → “无目标”
    - 英文：code=1 → “motion detected Xcm”；code=2 → “presence detected Xcm”；code=0 → “no object”
    - 特例：当行仅为 `0` 或距离值 `dist==0` 时，统一输出“无目标 / no object”
  - 代码参考：监控文案 [BLEManager.swift](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L877-L892)，RX:0 处理 [BLEManager.swift](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L821-L829)
- 日志渲染
  - 采用枚举索引作为 ForEach 的 ID，避免相同文本造成重复 ID 警告
  - 代码参考：[DeviceDetailLogsView.swift](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/DeviceDetailLogsView.swift#L53-L64)
- 数据处理开关
  - 仅在“设备详情”页开启数据接收与解析，其它页面关闭以节省资源
  - 代码参考：开关与短路 [BLEManager.swift](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L41-L47)、[BLEManager.swift](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L108-L116)；Tab 切换控制 [ContentView.swift](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/ContentView.swift#L27-L45)

## 参数配置流程
### 只读查询（Locked）
- 握手
  - 发送 AA；收到 STOP 或 AT+ERR 后进入查询
  - 代码参考：[startHandshake](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L160-L165)、STOP/ERR 处理 [BLEManager.swift](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L491-L506)
- 查询队列（均带 CRLF，不发送 TRITH?）
  - AT+R1?、AT+R2?、AT+R3?、AT+ONTH?、AT+HOLD?、AT+STIME=100、AT+FTIME=100
  - 代码参考：[readParameters](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L167-L185)
- 响应解析与推进
  - AT+R1=/R2=/R3=YY（单位：厘米）→ UI 显示米（YY/100）
  - AT+ONTH=YY → 灵敏度=YY
  - AT+HOLD=YY → 延迟时间（秒）= YY/10
  - 解析到任意 AT+XXXX=YYYY 后：取消当前命令超时、清除重试计数、立即发送下一条
  - 代码参考：AT 响应解析分支 [BLEManager.swift](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L574-L602)
- 超时与重试
  - 查询超时=200ms；若未收到响应，则重试本条，最多 3 次
  - 解析到响应后立即取消超时并推进队列
  - 代码参考：发送与超时 [BLEManager.swift](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L280-L299)

### 保存（Save）
- 握手
  - AA；收到 STOP 或 AT+ERR 后发送设置
  - 代码参考：[startHandshake](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L160-L165)
- 设置队列（不发送 TRITH=xx）
  - 距离（米→厘米）：AT+R1=<cm>、AT+R2=<cm>、AT+R3=<cm>
  - 灵敏度：AT+ONTH=<int>
  - 时间基准：AT+STIME=10、AT+FTIME=100
  - 延迟时间：AT+HOLD=<秒×10>
  - 代码参考：[saveParameters](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L191-L233)
- UI 同步（AT+OK）
  - 收到对应 AT+OK 后，直接将已发送的值写入 UI（R1/R2/R3、ONTH、HOLD、STIME/FTIME）
  - 代码参考：AT+OK 分支同步 [BLEManager.swift](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L496-L522)

### 恢复出厂（Restore）
- 入口
  - 发送 AT+INIT；收到 AT+OK 后下发默认集
  - 代码参考：[restoreDefaults](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L325-L329)、INIT OK 分支 [BLEManager.swift](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L507-L518)
- 默认设置（不设置 TRITH）
  - 距离：AT+R1=200、AT+R2=500、AT+R3=1000（对应 2/5/10m）
  - 灵敏度：AT+ONTH=4
  - 时间与延迟：AT+STIME=10、AT+FTIME=100、AT+HOLD=200（20 秒）

## 解析正则与容错
- 允许等号两侧存在空格；数字后可带附加字符；无需行首锚点
- 查询响应采用宽松匹配：`AT+XXXX\s*=\s*(\d+)`，数字后使用词边界
- 代码参考：查询响应正则 [BLEManager.swift](file:///Users/qingshan/Git/radarlinkpro-ios/ios/RadarSwitch/BLEManager.swift#L574-L602)

## 单位与显示约定
- 距离：设备返回厘米，界面显示为米（除以 100）
- 延迟时间（Delay Time）：HOLD 值除以 10 得秒数；界面单位为秒
- “进入延迟”已隐藏，不依赖 TRITH；只读与保存方案分别固定 STIME 为 100 / 10

