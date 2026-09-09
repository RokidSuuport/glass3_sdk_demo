#!/bin/sh
set -eu

repo_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
version="1.0.0"
dist_root="$repo_dir/dist"
release_dir="$dist_root/glass3-media-streaming-$version"
expected_release_dir="$repo_dir/dist/glass3-media-streaming-1.0.0"

if [ "$release_dir" != "$expected_release_dir" ]; then
  echo "拒绝清理非预期目录：$release_dir" >&2
  exit 1
fi

mkdir -p "$dist_root"
if [ -e "$release_dir" ]; then
  rm -rf -- "$release_dir"
fi

"$repo_dir/scripts/verify-all.sh"

mkdir -p \
  "$release_dir/apk" \
  "$release_dir/maven" \
  "$release_dir/web-stream-receiver" \
  "$release_dir/scripts" \
  "$release_dir/docs/code"

cp "$repo_dir/android/glass-stream-sender/build/outputs/apk/debug/glass-stream-sender-debug.apk" \
  "$release_dir/apk/glass-stream-sender-debug.apk"
cp "$repo_dir/android/glass-stream-sender/build/outputs/apk/release/glass-stream-sender-release-unsigned.apk" \
  "$release_dir/apk/glass-stream-sender-release-unsigned.apk"
cp -R "$repo_dir/android/build/customer-repository/com" "$release_dir/maven/"

cp "$repo_dir/web-stream-receiver/package.json" "$release_dir/web-stream-receiver/"
cp "$repo_dir/web-stream-receiver/package-lock.json" "$release_dir/web-stream-receiver/"
cp -R "$repo_dir/web-stream-receiver/public" "$release_dir/web-stream-receiver/"
cp -R "$repo_dir/web-stream-receiver/src" "$release_dir/web-stream-receiver/"

for customer_script in \
  start-web-stream-receiver.sh \
  install-glass-stream-sender.sh \
  glass-logcat.sh; do
  cp "$repo_dir/scripts/$customer_script" "$release_dir/scripts/"
done
cp "$repo_dir/README.md" "$release_dir/"
for guide in \
  getting-started.md \
  webrtc-streaming.md \
  media-capture-integration.md \
  configuration.md \
  troubleshooting.md \
  production-deployment.md \
  api-reference.md \
  release-checklist.md; do
  cp "$repo_dir/docs/$guide" "$release_dir/docs/"
done
cp -R "$repo_dir/docs/code/java" "$release_dir/docs/code/"
cp -R "$repo_dir/docs/code/kotlin" "$release_dir/docs/code/"

(
  cd "$release_dir"
  find . -type f ! -name SHA256SUMS | LC_ALL=C sort | while IFS= read -r file; do
    shasum -a 256 "$file"
  done > SHA256SUMS
)

if find "$release_dir" -type d \( -name node_modules -o -name .gradle -o -name build \) | grep -q .; then
  echo "交付包中出现禁止的缓存或构建目录。" >&2
  exit 1
fi

if find "$release_dir" -type f \( \
  -name '*.jks' -o -name '*.keystore' -o -name '*.pem' -o -name '*.key' \
\) | grep -q .; then
  echo "交付包中出现签名或密钥文件。" >&2
  exit 1
fi

if rg -n --hidden --glob '!SHA256SUMS' \
  '(storePassword|keyPassword|BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|192\.168\.[0-9]+\.[0-9]+)' \
  "$release_dir"; then
  echo "交付包中出现口令、私钥标记或固定局域网地址。" >&2
  exit 1
fi

echo "客户交付包已生成：$release_dir"
