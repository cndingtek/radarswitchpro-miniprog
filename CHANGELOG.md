# RadarSwitch 更新日志

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
- 仓库标签/发布页（创建后可见）：`https://github.com/cndingtek/radarlink/releases/tag/v1.0.0`