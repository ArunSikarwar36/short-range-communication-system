#!/usr/bin/env bash
# Builds the two binary dependencies of the iOS app (macOS + Xcode only):
#   ios/build/SrcsOpus.xcframework   libopus 1.5.2 + Swift-friendly wrapper (device + simulator)
#   core/build/XCFrameworks/release/SrcsCore.xcframework   the shared Kotlin core
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/ios/build"
mkdir -p "$OUT"

for sdk in iphoneos iphonesimulator; do
  dir="$OUT/opus-$sdk"
  cmake -S "$ROOT/ios/opus" -B "$dir" \
    -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$sdk" -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=16.0 -DCMAKE_BUILD_TYPE=Release > /dev/null
  cmake --build "$dir" --config Release --target srcs_opus opus -j 4 > /dev/null
  libtool -static -o "$dir/libsrcs_opus_all.a" "$dir/libsrcs_opus.a" "$dir/opus/libopus.a"
done
rm -rf "$OUT/SrcsOpus.xcframework"
xcodebuild -create-xcframework \
  -library "$OUT/opus-iphoneos/libsrcs_opus_all.a" \
  -library "$OUT/opus-iphonesimulator/libsrcs_opus_all.a" \
  -output "$OUT/SrcsOpus.xcframework"

(cd "$ROOT" && SRCS_CORE_ONLY=1 ./gradlew :core:assembleSrcsCoreReleaseXCFramework --stacktrace)
echo "dependencies ready"
