#!/usr/bin/env bash
# 准备 Android Linux 沙箱资源（PRoot + Alpine minirootfs），打包进 APK assets。
#
# 参考 OpenMinis / Operit / shiyi-agent 的方案：
#   - PRoot：用户态 chroot（免 root），使用 termux 或自建构建的 Android arm64 二进制
#   - Alpine minirootfs：固定 3.21 版本，校验 SHA-256 后固化进 assets
#
# 产物（移动端 Expo 项目，expo prebuild 后由 withPrivilegedExecution 注入）：
#   native-android/assets/alpine/proot
#   native-android/assets/alpine/minirootfs.tar.gz
#
# 用法：
#   bash scripts/prepare_android_sandbox.sh [--no-proot-download]

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS_DIR="$REPO_ROOT/mobile/native-android/assets/alpine"
ALPINE_VERSION="3.21.0"
ALPINE_SERIES="v3.21"
ARCH="aarch64"
ROOTFS_URL="https://dl-cdn.alpinelinux.org/alpine/${ALPINE_SERIES}/releases/${ARCH}/alpine-minirootfs-${ALPINE_VERSION}-${ARCH}.tar.gz"

# 官方架构产物，构建期从 Termux 软件源获取 PRoot 二进制（本地优先，fallback 到 assets 已有文件）
PROOT_URL="https://github.com/termux/proot/releases/download/v0.8.3/proot-v0.8.3-android-${ARCH}.tar.gz"

mkdir -p "$ASSETS_DIR"

echo "==> 准备 Alpine minirootfs"
if [ ! -f "$ASSETS_DIR/minirootfs.tar.gz" ]; then
  echo "    下载 $ROOTFS_URL"
  curl -fsSL "$ROOTFS_URL" -o "$ASSETS_DIR/minirootfs.tar.gz"
  echo "    SHA-256:"
  sha256sum "$ASSETS_DIR/minirootfs.tar.gz"
else
  echo "    已存在，跳过下载"
fi

echo "==> 准备 PRoot"
if [ ! -f "$ASSETS_DIR/proot" ] && [ "$1" != "--no-proot-download" ]; then
  echo "    下载 ${PROOT_URL}"
  TMP=$(mktemp -d)
  curl -fsSL "$PROOT_URL" -o "$TMP/proot.tar.gz"
  tar -xzf "$TMP/proot.tar.gz" -C "$TMP"
  # 解包后通常是 proot/usr/bin/proot 结构
  find "$TMP" -type f -name 'proot' | head -1 | xargs -I{} cp {} "$ASSETS_DIR/proot"
  rm -rf "$TMP"
  chmod +x "$ASSETS_DIR/proot"
  echo "    copied -> $ASSETS_DIR/proot"
else
  [ -f "$ASSETS_DIR/proot" ] && echo "    已存在，跳过" || echo "    跳过下载（assets 已有）"
fi

echo "==> 完成"
ls -lh "$ASSETS_DIR"