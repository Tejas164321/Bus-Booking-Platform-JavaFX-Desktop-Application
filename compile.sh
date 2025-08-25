#!/usr/bin/env bash
set -euo pipefail

# Directories
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$ROOT_DIR/src"
OUT_DIR="$ROOT_DIR/out"
JFX_LIB_DIR="$ROOT_DIR/lib/javafx-sdk-21.0.6/lib"
MYSQL_JAR="$ROOT_DIR/lib/mysql-connector-j-9.2.0/mysql-connector-j-9.2.0.jar"
JBCRYPT_JAR="$ROOT_DIR/lib/jbcrypt-0.4.jar"

mkdir -p "$OUT_DIR"

# Compile
javac \
  --class-path "$MYSQL_JAR:$JBCRYPT_JAR" \
  --module-path "$JFX_LIB_DIR" \
  --add-modules javafx.controls,javafx.fxml \
  -d "$OUT_DIR" \
  $(find "$SRC_DIR" -name "*.java")

# Copy non-Java resources (FXML, images, styles) preserving relative paths (no rsync)
(
  cd "$SRC_DIR"
  find . -type f \( -name "*.fxml" -o -name "*.css" -o -name "*.png" -o -name "*.jpg" -o -name "*.jpeg" -o -name "*.gif" \) | while IFS= read -r rel; do
    dest_dir="$OUT_DIR/$(dirname "$rel")"
    mkdir -p "$dest_dir"
    cp "$rel" "$dest_dir/"
  done
)

echo "Compilation successful. Classes in $OUT_DIR"