#!/bin/bash
# Hermes 对话 App 一键构建（跨机器自动探测工具链）。
#
# 本工程没有 gradle wrapper（无 gradlew / gradle/wrapper）。本脚本会依次探测
# JDK / Android SDK / Gradle 的位置，探测结果打印在开头；缺哪样会明确报出来。
#
# 用法：
#   ./build.sh            # 编 debug 包
#   ./build.sh clean      # 先 clean 再编
#   ./build.sh release    # 编 release 包（开 R8，体积小一半，推荐装机用）
#   ./build.sh clean release
#
# 探测顺序（每一项都能用环境变量强行覆盖）：
#   JAVA_HOME        : 环境变量 → sdkman → /usr/lib/jvm/* → which javac
#   ANDROID_SDK_ROOT : 环境变量 → local.properties 的 sdk.dir → 常见安装目录
#   GRADLE           : $GRADLE → PATH 里的 gradle → 常见目录 → ./gradlew（若有）

set -u
cd "$(dirname "$0")" || exit 1

die() { echo "✗ $*" >&2; exit 1; }

# ---------- JDK ----------
if [ -z "${JAVA_HOME:-}" ]; then
  for c in "$HOME/.sdkman/candidates/java/current" \
           /usr/lib/jvm/java-21-openjdk-amd64 \
           /usr/lib/jvm/java-17-openjdk-amd64 \
           /Library/Java/JavaVirtualMachines/*/Contents/Home; do
    [ -x "$c/bin/javac" ] && JAVA_HOME="$c" && break
  done
fi
if [ -z "${JAVA_HOME:-}" ] && command -v javac >/dev/null 2>&1; then
  JAVA_HOME=$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")
fi
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ] \
  || die "找不到 JDK（需要 17 或更高）。装一个 JDK，或 export JAVA_HOME=/path/to/jdk 后重跑。"
export JAVA_HOME

# ---------- Android SDK ----------
if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -f local.properties ]; then
  sdkline=$(grep -E '^sdk\.dir=' local.properties | head -1 | cut -d= -f2-)
  [ -n "$sdkline" ] && ANDROID_SDK_ROOT="$sdkline"
fi
if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
  for c in "$HOME/Android/Sdk" "$HOME/Library/Android/sdk" \
           $HOME/android-tools/sdk /opt/android-sdk /usr/lib/android-sdk; do
    [ -d "$c" ] && ANDROID_SDK_ROOT="$c" && break
  done
fi
[ -n "${ANDROID_SDK_ROOT:-}" ] && [ -d "$ANDROID_SDK_ROOT" ] \
  || die "找不到 Android SDK。装好 SDK（platforms;android-34 + build-tools;34.0.0），在 local.properties 写 sdk.dir=/path/to/sdk，或 export ANDROID_SDK_ROOT=/path/to/sdk 后重跑。"
export ANDROID_SDK_ROOT
export ANDROID_HOME="$ANDROID_SDK_ROOT"

# ---------- Gradle ----------
GRADLE="${GRADLE:-}"
if [ -z "$GRADLE" ] && command -v gradle >/dev/null 2>&1; then GRADLE=$(command -v gradle); fi
if [ -z "$GRADLE" ]; then
  for c in "$HOME/.sdkman/candidates/gradle/current/bin/gradle" \
           $HOME/android-tools/gradle-8.9/bin/gradle \
           /opt/gradle/bin/gradle; do
    [ -x "$c" ] && GRADLE="$c" && break
  done
fi
if [ -z "$GRADLE" ] && [ -x ./gradlew ]; then GRADLE=./gradlew; fi
[ -n "$GRADLE" ] \
  || die "找不到 Gradle。装 Gradle 8.9 并加进 PATH，或 export GRADLE=/path/to/gradle 后重跑。"

# ---------- 解析参数 ----------
CLEAN=0
TASK=assembleDebug
for a in "$@"; do
  case "$a" in
    clean)   CLEAN=1 ;;
    release) TASK=assembleRelease ;;
    debug)   TASK=assembleDebug ;;
  esac
done

echo "JDK    = $JAVA_HOME"
echo "SDK    = $ANDROID_SDK_ROOT"
echo "GRADLE = $GRADLE"
echo "TASK   = $TASK"
echo
echo ""

[ "$CLEAN" = "1" ] && "$GRADLE" clean

"$GRADLE" "$TASK" 2>&1 | tee build.log
rc=${PIPESTATUS[0]}
echo "EXIT=$rc"
if [ "$rc" -eq 0 ]; then
  if [ "$TASK" = "assembleRelease" ]; then
    ls -l app/build/outputs/apk/release/app-release.apk
  else
    ls -l app/build/outputs/apk/debug/app-debug.apk
  fi
else
  echo "BUILD FAILED"
fi
exit "$rc"
