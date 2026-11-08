#!/usr/bin/env bash
# 用目标 APK 的 DEX 校验 anchors.json（纯 Python，不依赖 dexsql/Android SDK）。
#
# 用法:
#   bash tools/check-anchors.sh <base.apk> [--version <versionCode>]
#   TARGET_APK=<base.apk> bash tools/check-anchors.sh
#
# 未提供 APK 时打印 SKIP 并以 0 退出，便于在 preflight/CI 里安全地做成可选项。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

if [ "$#" -gt 0 ] && [ "${1#-}" = "$1" ]; then
    APK="$1"
    shift
else
    APK="${TARGET_APK:-}"
fi
if [ -z "$APK" ]; then
    echo "SKIP: 未提供目标 APK（第一个参数或 TARGET_APK）"
    exit 0
fi

CORE=""
for candidate in "${XPOSED_CORE_PATH:-}" "$REPO_ROOT/../xposed-core" "$REPO_ROOT/../../xposed-core"; do
    if [ -n "$candidate" ] && [ -f "$candidate/tools/check_anchors.py" ]; then
        CORE="$candidate"
        break
    fi
done
[ -n "$CORE" ] || {
    echo "ERROR: 找不到 xposed-core/tools/check_anchors.py（可用 XPOSED_CORE_PATH 指定）" >&2
    exit 1
}

PYTHON_BIN="$(command -v python3 || command -v python || true)"
[ -n "$PYTHON_BIN" ] || {
    echo "ERROR: 未找到 python3/python" >&2
    exit 1
}

ANCHORS="$REPO_ROOT/app/src/main/resources/anchors.json"
[ -f "$ANCHORS" ] || {
    echo "ERROR: 缺少 $ANCHORS" >&2
    exit 1
}

exec "$PYTHON_BIN" -I "$CORE/tools/check_anchors.py" \
    --anchors "$ANCHORS" --apk "$APK" "$@"
