#!/usr/bin/env bash
# ============================================================
# 拉取第三方依赖
#
# 目前只有一个：ZXing core（二维码解码）
# 二进制 jar 不进版本库，构建前跑一次这个脚本。
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIBS="$ROOT/libs"
ZXING_VER="3.5.3"
ZXING_JAR="$LIBS/zxing-core.jar"
URL="https://repo1.maven.org/maven2/com/google/zxing/core/${ZXING_VER}/core-${ZXING_VER}.jar"

mkdir -p "$LIBS"

if [ -f "$ZXING_JAR" ]; then
  echo "[+] 已存在：libs/zxing-core.jar"
  exit 0
fi

echo "[*] 下载 ZXing core ${ZXING_VER} ..."
if command -v curl >/dev/null 2>&1; then
  curl -fL --retry 3 -o "$ZXING_JAR" "$URL"
elif command -v wget >/dev/null 2>&1; then
  wget -O "$ZXING_JAR" "$URL"
else
  echo "[!] 需要 curl 或 wget" >&2
  exit 1
fi

SIZE=$(wc -c < "$ZXING_JAR" | tr -d ' ')
[ "$SIZE" -gt 100000 ] || { echo "[!] 下载不完整（$SIZE 字节）" >&2; exit 1; }

echo "[+] 完成：libs/zxing-core.jar（$SIZE 字节）"
