#!/usr/bin/env bash
# 校验 Gradle Wrapper 的 JAR 与发行包校验和，防止供应链替换。
#
# 用法: bash tools/check-wrapper.sh
# 退出码: 0 通过；非 0 表示 wrapper 被替换、版本漂移或缺少 distributionSha256Sum。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR="$REPO_ROOT/gradle/wrapper/gradle-wrapper.jar"
PROPS="$REPO_ROOT/gradle/wrapper/gradle-wrapper.properties"

EXPECTED_JAR_SHA256="498495120a03b9a6ab5d155f5de3c8f0d986a449153702fb80fc80e134484f17"
EXPECTED_DISTRIBUTION_URL='https\://services.gradle.org/distributions/gradle-8.13-bin.zip'
EXPECTED_DISTRIBUTION_SHA256="20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78"

die() { echo "ERROR: $*" >&2; exit 1; }

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        die "未找到 sha256sum 或 shasum"
    fi
}

[ -f "$JAR" ] || die "缺少 $JAR"
[ -f "$PROPS" ] || die "缺少 $PROPS"

ACTUAL_JAR_SHA256="$(sha256_of "$JAR")"
[ "$ACTUAL_JAR_SHA256" = "$EXPECTED_JAR_SHA256" ] \
    || die "gradle-wrapper.jar 校验和不匹配：期望 $EXPECTED_JAR_SHA256，实际 $ACTUAL_JAR_SHA256"

ACTUAL_URL="$(grep -E '^[[:space:]]*distributionUrl[[:space:]]*=' "$PROPS" \
    | tail -n 1 | cut -d= -f2- | tr -d '\r')"
[ "$ACTUAL_URL" = "$EXPECTED_DISTRIBUTION_URL" ] \
    || die "distributionUrl 不匹配：期望 $EXPECTED_DISTRIBUTION_URL，实际 $ACTUAL_URL"

ACTUAL_DIST_SHA256="$(grep -E '^[[:space:]]*distributionSha256Sum[[:space:]]*=' "$PROPS" \
    | tail -n 1 | cut -d= -f2- | tr -d '\r')"
[ "$ACTUAL_DIST_SHA256" = "$EXPECTED_DISTRIBUTION_SHA256" ] \
    || die "distributionSha256Sum 不匹配：期望 $EXPECTED_DISTRIBUTION_SHA256，实际 ${ACTUAL_DIST_SHA256:-<缺失>}"

echo "==> Gradle wrapper pins OK (8.13, JAR $EXPECTED_JAR_SHA256)"
