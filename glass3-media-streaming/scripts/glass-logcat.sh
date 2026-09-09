#!/bin/sh
set -eu

echo "正在显示 Glass3 采集、推流、CameraShare、音频服务和崩溃日志；按 Ctrl+C 停止。"
exec adb logcat -v threadtime \
  GlassMediaStream:D \
  CameraShareHelper:D \
  MediaServerImpl:D \
  audio.primary.neo:D \
  audio.service:D \
  AndroidRuntime:E \
  '*:S'
