# RadarSwitch 更新日志

## v1.1.1 — 2025-11-15

变更摘要：
- 品牌更名为 `RadarSwitch Pro`（原 `RadarLink Pro`）。
- 包名更改：`com.dingtek.radarlinkpro` → `com.dingtek.radarswitchpro`。
- 应用 ID/命名空间更新：`radarlinkpro.dingtek.com` → `radarswitchpro.dingtek.com`。
- 应用显示名称更新为 `RadarSwitch Pro`，统一日志 TAG 为 `RadarSwitchPro`。
- 签名别名更新为 `radarswitchpro`（需保证 keystore 中已创建该别名）。
- README/CHANGELOG 的仓库发布链接更新为 `cndingtek/radarswitch-pro`。
 - 修复：MagicOS 7.1 未自动请求“精确位置”权限导致扫描无结果；运行时权限新增 `ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION` 并在仅授权“附近设备”后补弹“精确位置”。
 - Manifest：新增 `ACCESS_COARSE_LOCATION`；为 `BLUETOOTH_SCAN` 增加 `usesPermissionFlags="neverForLocation"` 提示。

构建与验证：
- 已完成 `assembleRelease` 与 `bundleRelease` 构建；产物位于 `app/build3/outputs/apk/bundle/...`。
- 安装验证：通过 ADB 成功安装并启动包名 `radarswitchpro.dingtek.com`。

## v1.0.0 — 2025-10-28

变更摘要：
- 修复：最小距离在等待状态不刷新，改为以设备响应驱动更新；无法计算时兜底为 `0.0`。
- 特性：在“恢复默认”后，按 `Range3/MR3` 与 `Range1/MR1` 自动计算最大/最小距离；未收到 `Range3/MR3` 时最大距离保持 `6.0`。
- 配置：默认日志上限改为 `20`（设置页与日志组件统一）。
- 配置：只读模式默认选中“只读”。
- 维护：增量读取改为“最后 N 行”，兼容原始日志列表被裁剪的情况。
- 维护：更新 `.gitignore`，忽略 `app/build2/`、`app/build3/`、`app/keystore/`，避免误提交构建产物与签名文件。

下载与验证：
- 本地验证下载（仅在本地开发环境可用）：`http://localhost:8001/app/build3/outputs/apk/release/app-release.apk`
- 若已安装 Debug 版，需先卸载再安装 Release 版以避免签名冲突。
- 验证建议：进入“恢复默认”页面，点击按钮后保持停留，观察最大/最小距离的更新；若未收到 `Range1`，最小距离应兜底为 `0.0`。

已知提示：
- 构建期间出现 `textFieldColors` 和 `quadraticBezierTo` 的弃用警告，不影响功能；后续版本将逐步替换对应 API。

标签：
- 已创建并推送标签：`v1.0.0`
- 仓库标签/发布页（创建后可见）：`https://github.com/cndingtek/radarswitch-pro/releases/tag/v1.0.0`
