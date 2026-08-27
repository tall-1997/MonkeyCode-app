#!/usr/bin/env bash
# ohmyagent Android ARM64 交叉编译脚本
#
# 用途：将 ohmyagent 引擎编译为 Android arm64-v8a 二进制，供 MonkeyCode 手机端
# 特权模式下的本地引擎使用。需要 agent 源码可用（仓库根 agent/ submodule 或独立 clone）。
#
# 用法：
#   export OHMYAGENT_SRC=/path/to/ohmyagent   # 缺省用仓库根 agent/ 目录
#   bash ./build-mobile-engine.sh
#
# 产物：out/mobile/ohmyagent-android-arm64

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OHMYAGENT_SRC="${OHMYAGENT_SRC:-$REPO_ROOT/agent}"
OUT_DIR="$REPO_ROOT/mobile/out/mobile"

if [ ! -f "$OHMYAGENT_SRC/go.mod" ]; then
  echo "错误：未找到 ohmyagent 源码（$OHMYAGENT_SRC 无 go.mod）"
  echo "请确认 agent submodule 已初始化（git submodule update --init），或用 OHMYAGENT_SRC 指定路径。"
  exit 1
fi

mkdir -p "$OUT_DIR"
cd "$OHMYAGENT_SRC"

echo "==> 交叉编译 ohmyagent → Android arm64-v8a"
CGO_ENABLED=0 GOOS=android GOARCH=arm64 go build \
  -trimpath \
  -ldflags="-s -w" \
  -o "$OUT_DIR/ohmyagent-android-arm64" \
  .

echo "==> 完成：$OUT_DIR/ohmyagent-android-arm64"
ls -lh "$OUT_DIR/ohmyagent-android-arm64"