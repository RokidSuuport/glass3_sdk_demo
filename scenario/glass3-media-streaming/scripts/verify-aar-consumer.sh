#!/bin/sh
set -eu

repo_dir="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
customer_repo="$repo_dir/android/build/customer-repository"
consumer_dir="$repo_dir/verification/aar-consumer"
version="1.0.0"

"$repo_dir/android/gradlew" -p "$repo_dir/android" \
  :glass3-media-capture:publishReleasePublicationToCustomerRepository \
  :webrtc-transport:publishReleasePublicationToCustomerRepository \
  :glass3-media-streaming:publishReleasePublicationToCustomerRepository

if rg -n "implementation project|api project|includeBuild|projectDir" "$consumer_dir"; then
  echo "AAR consumer must not use source project dependencies" >&2
  exit 1
fi

for artifact in glass3-media-capture webrtc-transport glass3-media-streaming; do
  artifact_dir="$customer_repo/com/rokid/glass/$artifact/$version"
  test -s "$artifact_dir/$artifact-$version.aar"
  test -s "$artifact_dir/$artifact-$version.pom"
done

unexpected_binary=$(find "$customer_repo" -type f \( -name '*.aar' -o -name '*.jar' \) \
  ! -name 'glass3-media-capture-*' \
  ! -name 'webrtc-transport-*' \
  ! -name 'glass3-media-streaming-*' \
  -print -quit)
if [ -n "$unexpected_binary" ]; then
  echo "Customer repository contains an unexpected bundled binary: $unexpected_binary" >&2
  exit 1
fi

capture_pom="$customer_repo/com/rokid/glass/glass3-media-capture/$version/glass3-media-capture-$version.pom"
transport_pom="$customer_repo/com/rokid/glass/webrtc-transport/$version/webrtc-transport-$version.pom"
streaming_pom="$customer_repo/com/rokid/glass/glass3-media-streaming/$version/glass3-media-streaming-$version.pom"

rg -q '<artifactId>glass3.open.sdk</artifactId>' "$capture_pom"
rg -q '<artifactId>glass3-media-capture</artifactId>' "$transport_pom"
rg -q '<artifactId>okhttp</artifactId>' "$transport_pom"
rg -q '<artifactId>android-prefixed</artifactId>' "$transport_pom"
rg -q '<artifactId>glass3-media-capture</artifactId>' "$streaming_pom"
rg -q '<artifactId>webrtc-transport</artifactId>' "$streaming_pom"

"$repo_dir/android/gradlew" -p "$consumer_dir" \
  :app:assembleDebug -PcustomerRepo="$customer_repo"

echo "AAR/POM 发布和独立客户工程编译均已通过。"
