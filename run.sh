#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
OUT_DIR="$ROOT_DIR/out"
JFX_LIB_DIR="$ROOT_DIR/lib/javafx-sdk-21.0.6/lib"
MYSQL_JAR="$ROOT_DIR/lib/mysql-connector-j-9.2.0/mysql-connector-j-9.2.0.jar"
JBCRYPT_JAR="$ROOT_DIR/lib/jbcrypt-0.4.jar"

if [ ! -d "$OUT_DIR" ]; then
  echo "Build output not found. Compiling..." >&2
  "$ROOT_DIR/compile.sh"
fi

# Run JavaFX app
java \
  --class-path "$OUT_DIR:$MYSQL_JAR:$JBCRYPT_JAR" \
  --module-path "$JFX_LIB_DIR" \
  --add-modules javafx.controls,javafx.fxml \
  Main

