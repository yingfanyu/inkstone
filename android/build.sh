#!/bin/bash
# 重新构建打过补丁的 Inkstone APK
# 用法: bash build.sh [输出文件名]
set -e

OUT_NAME="${1:-Inkstone-nas.apk}"
ROOT="/c/Users/Administrator/WorkBuddy/2026-11-12-11-06-29/apk-build"
TOOL="C:/Users/Administrator/.workbuddy/skills/android-app-builder/toolchain"
JDK="$TOOL/jdk-17.0.20.1+1"
BT="$TOOL/sdk/build-tools/34.0.0"
AJ="$TOOL/sdk/platforms/android-34/android.jar"
DEC="/tmp/dec"
BUILD="/tmp/build"
APKTOOL="C:/Users/ADMINI~1/AppData/Local/Temp/apktool.jar"

echo "== 1/6 javac =="
rm -rf "$ROOT/out" && mkdir -p "$ROOT/out"
"$JDK/bin/javac.exe" -nowarn -source 1.8 -target 1.8 -encoding UTF-8 \
  -classpath "$AJ" -d "$ROOT/out" "$ROOT"/src/com/inkstone/app/*.java

echo "== 2/6 d8 =="
rm -rf "$ROOT/dex" && mkdir -p "$ROOT/dex"
"$JDK/bin/java.exe" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api 26 --lib "$AJ" --output "$ROOT/dex" $(find "$ROOT/out" -name "*.class" | tr '\n' ' ')

echo "== 3/6 apktool build =="
rm -rf "$BUILD" && mkdir -p "$BUILD"
"$JDK/bin/java.exe" -jar "$APKTOOL" b "$DEC" -o "$BUILD/unsigned.apk" >/dev/null

echo "== 4/6 追加 classes4.dex =="
cp "$ROOT/dex/classes.dex" "$BUILD/classes4.dex"
( cd "$BUILD" && python -c "
import zipfile
with zipfile.ZipFile('unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
    z.write('classes4.dex','classes4.dex')
" )

echo "== 5/6 zipalign =="
"$BT/zipalign.exe" -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk" 2>/dev/null

echo "== 6/6 签名 =="
"$JDK/bin/java.exe" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$ROOT/inkstone.jks" --ks-pass pass:inkstone2026 --key-pass pass:inkstone2026 \
  --out "$ROOT/$OUT_NAME" "$BUILD/aligned.apk"

"$JDK/bin/java.exe" -jar "$BT/lib/apksigner.jar" verify --print-certs "$ROOT/$OUT_NAME" | head -4
echo "== 完成: $ROOT/$OUT_NAME =="
ls -lh "$ROOT/$OUT_NAME"
