#!/bin/sh
set -eu

repo_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
docs_dir="$repo_dir/docs"
customer_files="$repo_dir/README.md \
$docs_dir/getting-started.md \
$docs_dir/webrtc-streaming.md \
$docs_dir/media-capture-integration.md \
$docs_dir/configuration.md \
$docs_dir/troubleshooting.md \
$docs_dir/production-deployment.md \
$docs_dir/api-reference.md \
$docs_dir/source-integration.md \
$docs_dir/code/kotlin/StreamingActivity.kt \
$docs_dir/code/kotlin/MediaCaptureActivity.kt \
$docs_dir/code/java/StreamingActivity.java \
$docs_dir/code/java/MediaCaptureActivity.java"

for file in $customer_files; do
  test -s "$file"
done

# 两个入口的示例由 verify-source-consumer.sh 实际编译，避免只检查固定文案。
test -s "$docs_dir/code/shared/LatestVideoWorker.java"
test -s "$repo_dir/scripts/verify-source-consumer.sh"

if rg -n -i 'maven|aar|[.]pom([^a-z]|$)|MAVEN_REPOSITORY_PATH' $customer_files; then
  echo 'Customer-facing guidance must describe direct use and source components only.' >&2
  exit 1
fi

for code in \
  PERMISSION_REQUIRED SDK_NOT_READY SDK_DISCONNECTED CAMERA_IN_USE \
  CAMERA_START_TIMEOUT VIDEO_FRAME_TIMEOUT AUDIO_START_FAILED AUDIO_DATA_TIMEOUT \
  SERVER_UNREACHABLE RECEIVER_NOT_READY WEBRTC_NEGOTIATION_FAILED NETWORK_DISCONNECTED; do
  rg -q "$code" "$docs_dir/troubleshooting.md"
done

for required in \
  'start-web-stream-receiver.sh' 'glass-stream-sender-debug.apk' '<PC-IP>' \
  '开启声音' '原始媒体采集' 'NV21' 'PCM' '如果失败'; do
  rg -q "$required" "$docs_dir/getting-started.md"
done

rg -q "docs/code/kotlin" "$repo_dir/verification/aar-consumer/app/build.gradle"
rg -q "docs/code/java" "$repo_dir/verification/aar-consumer/app/build.gradle"

if rg -n \
  '(^|[^0-9.])(192[.]168|10[.][0-9]{1,3}|172[.](1[6-9]|2[0-9]|3[01]))[.][0-9]{1,3}[.][0-9]{1,3}([^0-9.]|$)|storePassword|keyPassword|BEGIN (OPENSSH|RSA) PRIVATE KEY|local[.]properties' \
  $customer_files; then
  echo 'Customer files contain a private-network value, signing secret, private key, or local SDK file.' >&2
  exit 1
fi

if rg -n '[.][.][.]' $customer_files; then
  echo 'Customer files must not hide required code or steps with ellipses.' >&2
  exit 1
fi

echo '客户文档结构、示例完整性和安全检查均已通过。'
