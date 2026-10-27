#!/bin/sh
set -eu

repo_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

# 客户可以重命名工程目录；以下真实模块和应用配置才决定工程身份。
test -d "$repo_dir/android/glass3-media-capture"
test -d "$repo_dir/android/webrtc-transport"
test -d "$repo_dir/android/glass3-media-streaming"
test -d "$repo_dir/android/glass-stream-sender"
test -d "$repo_dir/web-stream-receiver"
test ! -e "$repo_dir/glasses-app"
test ! -e "$repo_dir/pc-receiver"

sender_source_dir="$repo_dir/android/glass-stream-sender/src"
for source_set in main test; do
  test -d "$sender_source_dir/$source_set/java/com/rokid/glass/mediastream/sender"
  test ! -e "$sender_source_dir/$source_set/java/com/rokid/glass/webrtctest"
done

grep -Fq "rootProject.name = 'glass3-media-streaming'" "$repo_dir/android/settings.gradle"
for module in glass3-media-capture webrtc-transport glass3-media-streaming glass-stream-sender; do
  grep -Fq "include ':$module'" "$repo_dir/android/settings.gradle"
done

sender_build="$repo_dir/android/glass-stream-sender/build.gradle"
grep -Fq "namespace 'com.rokid.glass.mediastream.sender'" "$sender_build"
grep -Fq "applicationId 'com.rokid.glass.mediastream.sender'" "$sender_build"
grep -Fq "implementation project(':glass3-media-capture')" "$sender_build"
grep -Fq "implementation project(':glass3-media-streaming')" "$sender_build"

for direct_dependency in \
  'com.google.android.material' \
  'com.squareup.okhttp3' \
  'com.rokid.security' \
  'io.github.webrtc-sdk'; do
  if grep -Fq "implementation '$direct_dependency" "$sender_build"; then
    exit 1
  fi
done

sender_main="$repo_dir/android/glass-stream-sender/src/main"
sender_package="$sender_main/java/com/rokid/glass/mediastream/sender"
for production_file in \
  "$sender_package/GlassMediaApplication.kt" \
  "$sender_package/HomeActivity.kt" \
  "$sender_package/streaming/StreamingActivity.kt" \
  "$sender_package/capture/MediaCaptureActivity.kt" \
  "$sender_main/res/layout/activity_home.xml" \
  "$sender_main/res/layout/activity_streaming.xml" \
  "$sender_main/res/layout/activity_media_capture.xml"; do
  test -f "$production_file"
done

for legacy_path in \
  "$sender_package/GlassWebRtcApplication.kt" \
  "$sender_package/MainActivity.kt" \
  "$sender_package/camera" \
  "$sender_package/sdk" \
  "$sender_package/signaling" \
  "$sender_package/stream" \
  "$sender_package/webrtc" \
  "$sender_main/res/layout/activity_main.xml"; do
  test ! -e "$legacy_path"
done

sender_manifest="$sender_main/AndroidManifest.xml"
grep -Fq 'GlassMediaApplication' "$sender_manifest"
grep -Fq 'HomeActivity' "$sender_manifest"
grep -Fq 'StreamingActivity' "$sender_manifest"
grep -Fq 'MediaCaptureActivity' "$sender_manifest"
grep -Fq 'Theme.GlassMediaStreaming' "$sender_manifest"

if grep -ERq \
  'com[.]rokid[.]security|GlassSdk|CameraShare|IMediaServer|livekit[.]org[.]webrtc|PeerConnection|android[.]media[.]AudioRecord|AudioRecord[(]|okhttp3' \
  "$sender_package"; then
  exit 1
fi

if grep -ERq \
  'webrtctest|Glass WebRTC Test|LAN TEST|ws://(192[.]168[.]|10[.]|172[.](1[6-9]|2[0-9]|3[01])[.])' \
  "$sender_main/java" "$sender_main/res"; then
  exit 1
fi

for package_file in package.json package-lock.json; do
  grep -Fq '"name": "glass3-media-receiver"' "$repo_dir/web-stream-receiver/$package_file"
done

sender_strings="$repo_dir/android/glass-stream-sender/src/main/res/values/strings.xml"
grep -Fq '<string name="app_name">Glass3 Media Streaming</string>' "$sender_strings"
grep -Fq '>音视频传输</string>' "$sender_strings"
grep -Fq '>原始媒体采集</string>' "$sender_strings"

grep -Fq '# Glass3 音视频采集与浏览器传输方案' "$repo_dir/README.md"
if grep -Eq 'glasses-app|pc-receiver|start-pc-receiver\.sh' "$repo_dir/README.md"; then
  exit 1
fi
