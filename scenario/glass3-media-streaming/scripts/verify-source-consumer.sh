#!/bin/sh
set -eu

repo_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
consumer_dir="$(mktemp -d "${TMPDIR:-/tmp}/glass3-source-consumer.XXXXXX")"
echo "独立源码接入验证工程：$consumer_dir"
rsync -a --exclude build --exclude .gradle --exclude .kotlin \
  "$repo_dir/verification/source-consumer/" "$consumer_dir/"
for component in glass3-media-capture webrtc-transport glass3-media-streaming; do
  mkdir -p "$consumer_dir/components/$component"
  rsync -a --exclude build --exclude .gradle --exclude .kotlin \
    "$repo_dir/android/$component/" "$consumer_dir/components/$component/"
done
for language in kotlin java; do
  mkdir -p "$consumer_dir/capture-app/src/main/$language" "$consumer_dir/streaming-app/src/main/$language"
  cp "$repo_dir/docs/code/$language/MediaCaptureActivity."* "$consumer_dir/capture-app/src/main/$language/"
  cp "$repo_dir/docs/code/$language/StreamingActivity."* "$consumer_dir/streaming-app/src/main/$language/"
done
if [ -d "$repo_dir/docs/code/shared" ]; then
  cp "$repo_dir/docs/code/shared/"*.java "$consumer_dir/capture-app/src/main/java/"
fi
"$repo_dir/android/gradlew" -p "$consumer_dir" \
  :capture-app:verifyCaptureIsolation :capture-app:testDebugUnitTest \
  :capture-app:assembleDebug :streaming-app:assembleDebug "$@"

echo "两种入口的独立源码工程均已编译，采集应用依赖隔离检查通过。"
