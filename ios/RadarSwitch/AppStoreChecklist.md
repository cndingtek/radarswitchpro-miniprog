# App Store 提交准备清单（RadarSwitch iOS）

## 元数据与版本
- 版本号（CFBundleShortVersionString）：1.2.0
- 构建号（CFBundleVersion）：120
- 应用名称（显示名）：RadarSwitch
- 本地化：中文（简体）、英文
- 隐私描述：蓝牙权限用途已添加（Info.plist）

## 构建与签名
1. 使用 Release 配置归档
2. 配置 Apple Developer 账号的签名证书与 Provisioning Profile
3. 目标 Bundle Identifier：com.dingtek.radarswitch
4. 提交至 App Store Connect（使用 Xcode Organizer 或者 Transporter）

## 资源与素材（已准备）
- AppIcon：[已完成] 所有尺寸已基于 1024x1024 源图自动生成并分配。
- 截图：请参考 `AppStoreAssets/Screenshots/README.md` 指南进行截图。
- 介绍与关键词：草稿已生成至 `AppStoreAssets/Metadata/` 目录（含中英文）。

## 测试与质量
- 真机测试：扫描、连接、参数读写、日志显示、语言切换
- 崩溃与日志：Release 关闭多余调试输出
- 兼容性：iOS 17+，主流程可用

## 提交步骤
1. 打开 Xcode，选择 Product → Archive
2. 在 Organizer 选择 Distribute App → App Store Connect → Upload
3. 在 App Store Connect 创建版本，填写元数据并提交审核

## 备注
- 当前构建脚本支持手动打包 IPA；App Store 上传需有效签名
- 资源告警（AppIcon 未分配）不影响编译，但建议提交前优化
