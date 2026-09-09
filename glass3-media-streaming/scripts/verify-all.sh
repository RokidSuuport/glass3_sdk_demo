#!/bin/sh
set -eu

repo_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"

"$repo_dir/scripts/verify-project-layout.sh"
"$repo_dir/scripts/verify-customer-docs.sh"
"$repo_dir/android/gradlew" -p "$repo_dir/android" \
  :glass3-media-capture:testDebugUnitTest \
  :webrtc-transport:testDebugUnitTest \
  :glass3-media-streaming:testDebugUnitTest \
  :glass-stream-sender:testDebugUnitTest \
  lintDebug \
  :glass-stream-sender:assembleDebug \
  :glass-stream-sender:assembleRelease \
  :glass3-media-capture:assembleRelease \
  :webrtc-transport:assembleRelease \
  :glass3-media-streaming:assembleRelease
"$repo_dir/scripts/verify-aar-consumer.sh"
npm --prefix "$repo_dir/web-stream-receiver" test

echo "全量自动验证已通过。"
