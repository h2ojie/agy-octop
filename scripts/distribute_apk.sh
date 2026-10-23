#!/bin/bash
# distribute_apk.sh - 仅分发安装包至家庭 NAS 与 Google Drive（公网不提供）

set -e

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
if [ ! -f "$APK_PATH" ]; then
    echo "Error: $APK_PATH not found. Please run ./gradlew assembleDebug first."
    exit 1
fi

VERSION=$(grep "versionName =" app/build.gradle.kts | head -n 1 | awk -F '"' '{print $2}')
if [ -z "$VERSION" ]; then
    VERSION="latest"
fi

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Starting distribution for Octop v$VERSION..."

# 1. 本地工作区备份
LOCAL_TARGET="octop-v${VERSION}.apk"
cp "$APK_PATH" "$LOCAL_TARGET"
echo "  [OK] Local workspace: $LOCAL_TARGET ($(ls -lh "$LOCAL_TARGET" | awk '{print $5}'))"

# 2. 家庭 NAS 共享盘 (/Volumes/tools/apk)
NAS_DIR="/Volumes/tools/apk"
if [ -d "$NAS_DIR" ]; then
    cp "$LOCAL_TARGET" "$NAS_DIR/octop-v${VERSION}.apk"
    cp "$LOCAL_TARGET" "$NAS_DIR/octop-v1.0.0.apk"
    echo "  [OK] Synced to NAS: $NAS_DIR/octop-v${VERSION}.apk"
else
    echo "  [WARN] NAS path $NAS_DIR is not mounted. Skipping NAS sync."
fi

# 3. Google Drive (CloudStorage/.../octop-apk)
GDRIVE_DIR="$HOME/Library/CloudStorage/GoogleDrive-jieiceone@gmail.com/我的云端硬盘/octop-apk"
if [ -d "$GDRIVE_DIR" ]; then
    cp "$LOCAL_TARGET" "$GDRIVE_DIR/octop-v${VERSION}.apk"
    echo "  [OK] Synced to Google Drive: $GDRIVE_DIR/octop-v${VERSION}.apk"
else
    echo "  [WARN] Google Drive path $GDRIVE_DIR not found. Skipping Google Drive sync."
fi

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Distribution completed! (Public server distribution disabled)"
