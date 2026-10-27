#!/bin/sh
set -eu

repo_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
apk="$repo_dir/android/glass-stream-sender/build/outputs/apk/debug/glass-stream-sender-debug.apk"
packaged_apk="$repo_dir/apk/glass-stream-sender-debug.apk"

# 源码工程从 Android 构建目录安装；客户交付包则直接使用随包 APK，
# 因此客户无需安装 Android Studio 或 Gradle 也能完成体验。
if [ ! -s "$apk" ] && [ -s "$packaged_apk" ]; then
  apk="$packaged_apk"
fi

device_count="$(adb devices | awk '$2 == "device" { count += 1 } END { print count + 0 }')"
if [ "$device_count" -ne 1 ]; then
  echo "需要且只能连接一台状态为 device 的 Glass3，当前数量：$device_count" >&2
  adb devices >&2
  exit 1
fi

if [ ! -s "$apk" ]; then
  if [ ! -x "$repo_dir/android/gradlew" ]; then
    echo "未找到可安装 APK，也没有源码构建环境。请重新下载完整交付包。" >&2
    exit 1
  fi
  "$repo_dir/android/gradlew" -p "$repo_dir/android" :glass-stream-sender:assembleDebug
fi

adb install -r "$apk"
adb shell am start -n \
  com.rokid.glass.mediastream.sender/com.rokid.glass.mediastream.sender.HomeActivity

echo "Glass3 Media Streaming 已安装并启动。"
