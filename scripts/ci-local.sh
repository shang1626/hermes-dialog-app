#!/usr/bin/env bash
# 本机 CI 门禁：与 .github/workflows/android-ci.yml 同判据，
# 让「推 GitHub 之前」就能先在本机证明没改坏。零 token，纯脚本。
#
#   bash scripts/ci-local.sh          # 单测 + release 编译（硬门禁）+ lint（软门禁）
#   bash scripts/ci-local.sh quick    # 只跑单测（快）
set -uo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO"

export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-~/android-tools/sdk}"
export ANDROID_HOME="${ANDROID_SDK_ROOT}"
GRADLE="${GRADLE:-~/android-tools/gradle-8.9/bin/gradle}"

if [ ! -x "$GRADLE" ]; then
  echo "[ci-local] gradle 不可执行: $GRADLE" >&2
  exit 2
fi

MODE="${1:-full}"
fail=0

step() { echo; echo "=== $1 ==="; }

step "单测 (test)"
"$GRADLE" test --no-daemon --stacktrace || fail=1

if [ "$MODE" != "quick" ] && [ "$fail" -eq 0 ]; then
  step "release 编译 (assembleRelease，硬门禁)"
  "$GRADLE" assembleRelease --no-daemon --stacktrace || fail=1

  step "lint (软门禁，只报不阻断)"
  "$GRADLE" lint --no-daemon || echo "[ci-local] lint 有问题，但软门禁不阻断"
fi

echo
if [ "$fail" -eq 0 ]; then
  echo "[ci-local] PASS"
else
  echo "[ci-local] FAIL" >&2
fi
exit "$fail"
