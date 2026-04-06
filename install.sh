#!/usr/bin/env bash
set -e

REPO="vulcanshen/nvim-java-debugger"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LIBS_DIR="$SCRIPT_DIR/adapter/libs"
JAR_PATH="$LIBS_DIR/nvim-java-debugger-all.jar"
VERSION_FILE="$LIBS_DIR/.jar-version"

mkdir -p "$LIBS_DIR"

# 取得 GitHub Release 最新版本
get_latest_version() {
  curl -sf "https://api.github.com/repos/$REPO/releases/latest" \
    | grep '"tag_name"' \
    | sed -E 's/.*"tag_name": *"([^"]+)".*/\1/'
}

# 下載 JAR
download_jar() {
  local version="$1"
  local url="https://github.com/$REPO/releases/download/$version/nvim-java-debugger-all.jar"
  echo "Downloading nvim-java-debugger $version ..."
  curl -sfL "$url" -o "$JAR_PATH"
  echo "$version" > "$VERSION_FILE"
  echo "Done."
}

# 主流程
latest=$(get_latest_version)

if [ -z "$latest" ]; then
  if [ -f "$JAR_PATH" ]; then
    echo "nvim-java-debugger: cannot check for updates (no network?), using existing JAR."
    exit 0
  else
    echo "nvim-java-debugger: cannot download JAR (no network?). Please check your connection." >&2
    exit 1
  fi
fi

if [ -f "$VERSION_FILE" ] && [ -f "$JAR_PATH" ]; then
  current=$(cat "$VERSION_FILE")
  if [ "$current" = "$latest" ]; then
    echo "nvim-java-debugger: up to date ($current)."
    exit 0
  fi
fi

download_jar "$latest"
