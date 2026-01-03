#!/bin/bash

# RadarSwitch iOS 构建脚本
# 用于自动化构建iOS应用

set -e

echo "🚀 开始构建 RadarSwitch iOS 应用..."

# 检查Xcode是否安装
if ! command -v xcodebuild &> /dev/null; then
    echo "❌ 错误: Xcode 未安装或命令行工具未配置"
    echo "请安装Xcode并在终端运行: xcode-select --install"
    exit 1
fi

# 项目路径
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_NAME="RadarSwitch"
SCHEME="RadarSwitch"

# 构建配置
CONFIGURATION="Release"
DESTINATION="generic/platform=iOS"

# 清理之前的构建
echo "🧹 清理之前的构建..."
cd "$PROJECT_DIR"
xcodebuild clean \
    -project "${PROJECT_NAME}.xcodeproj" \
    -scheme "${SCHEME}" \
    -configuration "${CONFIGURATION}"

# 构建应用
echo "🔨 构建应用..."
xcodebuild build \
    -project "${PROJECT_NAME}.xcodeproj" \
    -scheme "${SCHEME}" \
    -configuration "${CONFIGURATION}" \
    -destination "${DESTINATION}" \
    -archivePath "build/${PROJECT_NAME}.xcarchive" \
    CODE_SIGN_IDENTITY="" \
    CODE_SIGNING_REQUIRED=NO \
    CODE_SIGNING_ALLOWED=NO \
    archive

# 导出IPA
echo "📦 导出IPA..."
if xcodebuild -exportArchive \
    -archivePath "build/${PROJECT_NAME}.xcarchive" \
    -exportPath "build" \
    -exportOptionsPlist "exportOptions.plist"; then
    echo "✅ 导出成功"
else
    echo "⚠️ 标准导出失败 (可能是因为缺少签名证书)"
    echo "🔄 尝试手动打包..."
    
    # 手动打包
    cd "build"
    mkdir -p Payload
    cp -r "${PROJECT_NAME}.xcarchive/Products/Applications/${PROJECT_NAME}.app" Payload/
    zip -r "${PROJECT_NAME}.ipa" Payload
    rm -rf Payload
    cd ..
    
    if [ -f "build/${PROJECT_NAME}.ipa" ]; then
        echo "✅ 手动打包成功"
    else
        echo "❌ 打包失败"
        exit 1
    fi
fi

echo "✅ 构建完成!"
echo "📱 IPA文件路径: build/${PROJECT_NAME}.ipa"

# 显示构建信息
if [ -f "build/${PROJECT_NAME}.ipa" ]; then
    echo "📊 文件大小: $(ls -lh build/${PROJECT_NAME}.ipa | awk '{print $5}')"
    echo "🎯 构建时间: $(date)"
fi