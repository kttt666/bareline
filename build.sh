#!/usr/bin/env bash
# ============================================================
# Pocket —— 极简链接浏览器
#
# 纯 Android SDK 命令行构建，不依赖 Gradle / Android Studio：
#   aapt2 → javac → d8 → jar → zipalign → apksigner
#
# 用法：
#   bash build.sh                 # 用默认版本号构建
#   POCKET_VERSION=1.2.0 bash build.sh
#
# 可用环境变量：
#   ANDROID_SDK_ROOT    Android SDK 路径（找不到时会自动探测常见位置）
#   JAVA_HOME           JDK 路径（默认用 Android Studio 自带 jbr）
#   POCKET_BUILD_DIR    中间产物目录，**不能含空格**
#   POCKET_VERSION      版本名，默认 1.0.0
#   POCKET_VERSION_CODE 版本号，默认 1
#   POCKET_KS_PASS      签名密钥口令，默认 pocket-local
#   POCKET_BUILD_TOOLS  指定 build-tools 版本，默认取最新
#   POCKET_PLATFORM     指定 platform，默认取最新
# ============================================================
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SRC="$ROOT/src"
RES="$ROOT/res"
LIBS="$ROOT/libs"
MANIFEST="$ROOT/AndroidManifest.xml"
DIST="$ROOT/dist"

VERSION="${POCKET_VERSION:-1.0.0}"
VERSION_CODE="${POCKET_VERSION_CODE:-1}"
KS_PASS="${POCKET_KS_PASS:-pocket-local}"
PKG="com.littlesauce.pocket"
APK_NAME="pocket-${VERSION}.apk"

info() { printf '\033[36m[*]\033[0m %s\n' "$*"; }
ok()   { printf '\033[32m[+]\033[0m %s\n' "$*"; }
die()  { printf '\033[31m[!]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------- 平台判定 ----------
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) IS_WIN=1 ;;
  *)                    IS_WIN=0 ;;
esac

# 转成 Windows 风格路径（C:/foo）。
# Windows 版 javac/d8 不认 MSYS 的 /c/... 和 /tmp/... 虚拟路径。
win() {
  if [ "$IS_WIN" = 1 ] && command -v cygpath >/dev/null 2>&1; then
    cygpath -m "$1"
  else
    printf '%s' "$1"
  fi
}

# 找可执行文件：Windows 上优先 .exe / .bat
tool() {
  local dir="$1" name="$2" ext
  for ext in ".exe" ".bat" ""; do
    if [ -f "$dir/$name$ext" ]; then printf '%s' "$dir/$name$ext"; return 0; fi
  done
  return 1
}

# ---------- 定位 SDK ----------
find_sdk() {
  local c
  for c in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" \
           "$HOME/AppData/Local/Android/Sdk" \
           "$HOME/Android/Sdk" \
           "/c/Users/${USER:-$(whoami)}/AppData/Local/Android/Sdk" \
           "/usr/local/lib/android/sdk" \
           "/opt/android-sdk" \
           "/opt/homebrew/share/android-commandlinetools"; do
    [ -n "$c" ] && [ -d "$c/platforms" ] && { printf '%s' "$c"; return 0; }
  done
  return 1
}

# ---------- 定位 JDK ----------
find_jdk() {
  local c
  if [ -n "${JAVA_HOME:-}" ] && [ -x "$(tool "$JAVA_HOME/bin" javac || true)" ]; then
    printf '%s' "$JAVA_HOME"; return 0
  fi
  for c in "$HOME/AppData/Local/Android/Android Studio/jbr" \
           "/c/Program Files/Android/Android Studio/jbr" \
           "/d/Program Files/Android/Android Studio/jbr" \
           "/e/Program Files/Android/Android Studio/jbr" \
           "/opt/android-studio/jbr" \
           "/Applications/Android Studio.app/Contents/jbr/Contents/Home"; do
    [ -x "$(tool "$c/bin" javac || true)" ] && { printf '%s' "$c"; return 0; }
  done
  # 退回 PATH 里的 javac
  if command -v javac >/dev/null 2>&1; then
    dirname "$(dirname "$(readlink -f "$(command -v javac)")")"
    return 0
  fi
  return 1
}

SDK="$(find_sdk || true)"
[ -n "$SDK" ] || die "找不到 Android SDK，请设置 ANDROID_SDK_ROOT"
SDK="$(cd "$SDK" && pwd)"

BT_VER="${POCKET_BUILD_TOOLS:-$(ls "$SDK/build-tools" 2>/dev/null | sort -V | tail -1)}"
[ -n "$BT_VER" ] || die "SDK 里没有 build-tools"
BT="$SDK/build-tools/$BT_VER"

PLATFORM="${POCKET_PLATFORM:-$(ls "$SDK/platforms" 2>/dev/null | sort -V | tail -1)}"
[ -n "$PLATFORM" ] || die "SDK 里没有 platforms"
ANDROID_JAR="$SDK/platforms/$PLATFORM/android.jar"

JBR="$(find_jdk || true)"
[ -n "$JBR" ] || die "找不到 JDK，请设置 JAVA_HOME"
JAVA="$JBR/bin/java"
JAVAC="$JBR/bin/javac"
KEYTOOL="$JBR/bin/keytool"
JAR="$JBR/bin/jar"
[ "$IS_WIN" = 1 ] && { JAVA="$JAVA.exe"; JAVAC="$JAVAC.exe"; KEYTOOL="$KEYTOOL.exe"; JAR="$JAR.exe"; }

# d8 / apksigner 是 java 启动脚本，需要 JAVA_HOME 和 PATH 里能找到 java。
# Windows 上这些 .bat 只认反斜杠路径，所以要转一下。
if [ "$IS_WIN" = 1 ]; then
  export JAVA_HOME="$(cygpath -w "$JBR" 2>/dev/null || win "$JBR" | sed 's|/|\\|g')"
else
  export JAVA_HOME="$JBR"
fi
case ":$PATH:" in
  *":$JBR/bin:"*) ;;
  *) export PATH="$JBR/bin:$PATH" ;;
esac

AAPT2="$(tool "$BT" aapt2)"     || die "缺少 aapt2"
D8="$(tool "$BT" d8)"           || die "缺少 d8"
ZIPALIGN="$(tool "$BT" zipalign)" || die "缺少 zipalign"
APKSIGNER="$(tool "$BT" apksigner)" || die "缺少 apksigner"

# ---------- 中间目录 ----------
# javac / d8 的 @argfile 按空格切分参数、引号无效，
# 所以中间产物必须放在**不含空格**的路径下。
if [ -n "${POCKET_BUILD_DIR:-}" ]; then
  BUILD="$POCKET_BUILD_DIR"
elif [ "$IS_WIN" = 1 ]; then
  BUILD="/c/pocket-build"
else
  BUILD="/tmp/pocket-build"
fi

mkdir -p "$BUILD" 2>/dev/null || die "无法创建构建目录：$BUILD"

# 解析成真实绝对路径。Windows 上 /tmp 这类 MSYS 虚拟路径必须转成 C:/...，
# 否则传给 javac/d8 时它们找不到文件。
if [ "$IS_WIN" = 1 ]; then
  BUILD_W="$(cd "$BUILD" && pwd -W 2>/dev/null)"
  if [ -n "$BUILD_W" ]; then BUILD="$BUILD_W"; else BUILD="$(cd "$BUILD" && pwd)"; fi
else
  BUILD="$(cd "$BUILD" && pwd)"
fi
BUILD="$(printf '%s' "$BUILD" | tr -d '\r\n')"

case "$BUILD" in
  *" "*) die "构建目录不能含空格：$BUILD（用 POCKET_BUILD_DIR 换一个）" ;;
esac

GEN="$BUILD/gen"
CLASSES="$BUILD/classes"
DEXDIR="$BUILD/dex"
UNSIGNED="$BUILD/app-unsigned.apk"
ALIGNED="$BUILD/app-aligned.apk"
SIGNED="$BUILD/app-release.apk"
KS="$DIST/pocket.keystore"

echo "=========================================="
echo " Pocket ${VERSION}  (versionCode ${VERSION_CODE})"
echo "=========================================="
info "SDK        $SDK"
info "build-tools $BT_VER"
info "platform   $PLATFORM"
info "JDK        $JBR"
info "中间目录   $BUILD"

for f in "$JAVA" "$JAVAC" "$KEYTOOL" "$JAR" "$AAPT2" "$D8" "$ZIPALIGN" "$APKSIGNER" "$ANDROID_JAR"; do
  [ -f "$f" ] || die "缺少：$f"
done
ok "工具链检查通过"

# ---------- 清理 ----------
echo
info "[1/6] 清理"
rm -rf "$BUILD"
mkdir -p "$GEN" "$CLASSES" "$DEXDIR" "$DIST"
cp -r "$SRC" "$BUILD/src"

# ---------- 资源 ----------
echo
info "[2/6] 编译资源"
"$AAPT2" compile --dir "$RES" -o "$BUILD/res.zip" --no-crunch

echo
info "[3/6] 链接资源"
"$AAPT2" link \
  -o "$UNSIGNED" \
  -I "$ANDROID_JAR" \
  --manifest "$MANIFEST" \
  --java "$GEN" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION" \
  "$BUILD/res.zip"

# ---------- Java ----------
echo
info "[4/6] 编译 Java"
cd "$BUILD"

find "$BUILD/src" "$GEN" -name "*.java" | while read -r p; do win "$p"; done > "$BUILD/sources.txt"
info "    Java 文件: $(wc -l < "$BUILD/sources.txt")"

ZXING="$LIBS/zxing-core.jar"
[ -f "$ZXING" ] || die "缺少 $ZXING（见 README「第三方依赖」）"

if ! "$JAVAC" \
      -source 11 -target 11 \
      -classpath "$(win "$ANDROID_JAR");$(win "$ZXING")" \
      -d "$(win "$CLASSES")" \
      -encoding UTF-8 \
      -nowarn \
      "@sources.txt" > "$BUILD/javac.log" 2>&1; then
  tail -40 "$BUILD/javac.log"
  die "javac 编译失败"
fi

CLS_COUNT=$(find "$CLASSES" -name "*.class" | wc -l | tr -d ' ')
[ "$CLS_COUNT" -gt 0 ] || die "没有生成任何 class"
ok "生成 class: $CLS_COUNT"

# ---------- dex ----------
echo
info "[5/6] 编译 dex"
find "$CLASSES" -name "*.class" | while read -r p; do win "$p"; done > "$BUILD/classes-d8.txt"

if ! "$D8" \
      --lib "$(win "$ANDROID_JAR")" \
      --min-api 26 \
      --output "$(win "$DEXDIR")" \
      "@classes-d8.txt" \
      "$(win "$ZXING")" > "$BUILD/d8.log" 2>&1; then
  tail -20 "$BUILD/d8.log"
  die "d8 编译失败"
fi
[ -f "$DEXDIR/classes.dex" ] || die "d8 未生成 classes.dex"
ok "classes.dex $(du -h "$DEXDIR/classes.dex" | cut -f1)"

# ---------- 打包 ----------
echo
info "[6/6] 打包 + 签名"
cp "$UNSIGNED" "$BUILD/work.apk"
( cd "$DEXDIR" && "$JAR" uf "$(win "$BUILD/work.apk")" classes.dex )
mv "$BUILD/work.apk" "$UNSIGNED"

"$ZIPALIGN" -f -p 4 "$UNSIGNED" "$ALIGNED"

# 首次构建自动生成签名密钥。
# 这个文件要自己保管好：换钥之后旧版本无法覆盖升级，必须先卸载。
if [ ! -f "$KS" ]; then
  info "    生成签名密钥 $KS"
  "$KEYTOOL" -genkeypair \
    -keystore "$(win "$KS")" \
    -alias pocket \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KS_PASS" -keypass "$KS_PASS" \
    -dname "CN=Pocket, O=Pocket" >/dev/null 2>&1
  ok "    密钥已生成（口令：$KS_PASS）"
fi

"$APKSIGNER" sign \
  --ks "$(win "$KS")" \
  --ks-key-alias pocket \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  --out "$SIGNED" \
  "$ALIGNED"

cp "$SIGNED" "$DIST/$APK_NAME"

echo
info "验证签名"
"$APKSIGNER" verify --print-certs "$DIST/$APK_NAME" 2>/dev/null | grep -E "Signer #1 certificate (DN|SHA-256)" || true

echo
echo "=========================================="
ok "构建完成：dist/$APK_NAME"
echo "=========================================="
ls -lh "$DIST/$APK_NAME"
