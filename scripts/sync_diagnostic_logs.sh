#!/bin/bash
# sync_diagnostic_logs.sh - 将移动端上传的诊断日志自动同步至 Google Drive、NAS 与本地归档目录

GDRIVE_DIR="$HOME/Library/CloudStorage/GoogleDrive-jieiceone@gmail.com/我的云端硬盘/octop-logs"
NAS_DIR="/Volumes/tools/octop-logs"
LOCAL_DIR="$HOME/octop-logs"

mkdir -p "$LOCAL_DIR"
mkdir -p "$GDRIVE_DIR" 2>/dev/null || true
if [ -d "/Volumes/tools" ]; then
    mkdir -p "$NAS_DIR" 2>/dev/null || true
fi

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Scanning for uploaded diagnostic logs in Octop workspace..."

# 扫描并同步所有 octop_logs_*.zip
find "$HOME/.octop" -type f -name "octop_logs_*.zip" 2>/dev/null | while read -r zip_path; do
    filename=$(basename "$zip_path")
    
    # 拷贝到本地归档
    if [ ! -f "$LOCAL_DIR/$filename" ]; then
        cp "$zip_path" "$LOCAL_DIR/$filename"
        echo " -> Copied to $LOCAL_DIR/$filename"
    fi

    # 拷贝到 Google Drive
    if [ -d "$GDRIVE_DIR" ] && [ ! -f "$GDRIVE_DIR/$filename" ]; then
        cp "$zip_path" "$GDRIVE_DIR/$filename"
        echo " -> Synced to Google Drive: $filename"
    fi

    # 拷贝到 NAS
    if [ -d "$NAS_DIR" ] && [ ! -f "$NAS_DIR/$filename" ]; then
        cp "$zip_path" "$NAS_DIR/$filename"
        echo " -> Synced to NAS: $filename"
    fi
done

echo "[$(date '+%Y-%m-%d %H:%M:%S')] Scan and sync completed."
