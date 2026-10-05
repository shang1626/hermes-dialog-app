#!/bin/bash
# Hermes 对话 App 一键构建。
# 本机没有 gradle wrapper（无 gradlew / gradle/wrapper），用 ~/android-tools 预装工具链。
# 详见 docs/BUILD.md。
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_SDK_ROOT=~/android-tools/sdk
export ANDROID_HOME=~/android-tools/sdk
GRADLE=~/android-tools/gradle-8.9/bin/gradle

cd "$(dirname "$0")" || exit 1

if [ "$1" = "clean" ]; then
  "$GRADLE" clean
fi

"$GRADLE" assembleDebug 2>&1 | tee build.log
rc=${PIPESTATUS[0]}
echo "EXIT=$rc"
if [ "$rc" -eq 0 ]; then
  ls -l app/build/outputs/apk/debug/app-debug.apk
else
  echo "BUILD FAILED"
fi
exit "$rc"