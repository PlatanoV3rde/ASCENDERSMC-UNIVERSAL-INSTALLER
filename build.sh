#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

APP_NAME="ASCENDERSMC-UNIVERSAL-INSTALLER"
BUILD_DIR=".build"
RELEASE_DIR="release"

rm -rf "$BUILD_DIR" out "$RELEASE_DIR"
mkdir -p "$BUILD_DIR/classes" "$RELEASE_DIR"

find src -name '*.java' -print0 | xargs -0 javac --release 21 -encoding UTF-8 -d "$BUILD_DIR/classes"
cp installer.properties "$BUILD_DIR/classes/installer.properties"
cp -r assets "$BUILD_DIR/classes/assets"
jar --create --file "$RELEASE_DIR/$APP_NAME.jar" --main-class com.ascendersmc.installer.App -C "$BUILD_DIR/classes" .

if command -v sha256sum >/dev/null 2>&1; then
  (cd "$RELEASE_DIR" && sha256sum "$APP_NAME.jar" > SHA256SUMS.txt)
elif command -v shasum >/dev/null 2>&1; then
  (cd "$RELEASE_DIR" && shasum -a 256 "$APP_NAME.jar" > SHA256SUMS.txt)
fi

rm -rf "$BUILD_DIR"
echo "[OK] $RELEASE_DIR/$APP_NAME.jar"
[ -f "$RELEASE_DIR/SHA256SUMS.txt" ] && echo "[OK] $RELEASE_DIR/SHA256SUMS.txt"
