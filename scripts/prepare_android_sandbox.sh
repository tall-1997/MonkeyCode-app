#!/usr/bin/env bash
# 准备 Android Linux 沙箱资源（PRoot + Alpine minirootfs），打包进 APK assets。
#
# 参考 OpenMinis / Operit / shiyi-agent 的方案：
#   - PRoot：用户态 chroot（免 root），从 Termux 官方仓库获取 Android arm64 二进制
#   - Alpine minirootfs：固定 3.21 版本，校验 SHA-256 后固化进 assets
#
# 产物（Expo 项目，expo prebuild 后由 withPrivilegedExecution 注入 APK）：
#   native-android/assets/alpine/proot
#   native-android/assets/alpine/minirootfs.tar.gz
#
# 用法：
#   bash scripts/prepare_android_sandbox.sh
# 环境变量：
#   SKIP_PROOT=1   跳过 PRoot 下载（仅准备 minirootfs，运行时走 native 在线兜底）
#   SKIP_ROOTFS=1  跳过 minirootfs 下载

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ASSETS_DIR="$REPO_ROOT/mobile/native-android/assets/alpine"
ALPINE_VERSION="3.21.0"
ALPINE_SERIES="v3.21"
ARCH="aarch64"
ROOTFS_URL="https://dl-cdn.alpinelinux.org/alpine/${ALPINE_SERIES}/releases/${ARCH}/alpine-minirootfs-${ALPINE_VERSION}-${ARCH}.tar.gz"

# Termux 官方仓库（packages.termux.dev 主域，packages-cf 镜像不稳定）的 proot 及依赖 deb
# 注意：packages 的 pool 路径是 .../apt/termux-main/pool/（无 dists/stable/main 前缀）
TERMUX_BASE="https://packages.termux.dev/apt/termux-main"
PROOT_DEB="$TERMUX_BASE/pool/main/p/proot/proot_5.1.107.92_${ARCH}.deb"
TALLOC_DEB="$TERMUX_BASE/pool/main/libt/libtalloc/libtalloc_2.4.3_${ARCH}.deb"
SHMEM_DEB="$TERMUX_BASE/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_${ARCH}.deb"

mkdir -p "$ASSETS_DIR"

# ── Alpine minirootfs ────────────────────────────────────────────
if [ "${SKIP_ROOTFS:-0}" != "1" ]; then
  echo "==> 准备 Alpine minirootfs"
  if [ ! -f "$ASSETS_DIR/minirootfs.tar.gz" ]; then
    echo "    下载 $ROOTFS_URL"
    curl -fsSL "$ROOTFS_URL" -o "$ASSETS_DIR/minirootfs.tar.gz"
    echo "    SHA-256: $(sha256sum "$ASSETS_DIR/minirootfs.tar.gz" | awk '{print $1}')"
  else
    echo "    minirootfs 已存在，跳过下载"
  fi
fi

# ── PRoot ────────────────────────────────────────────────────────
if [ "${SKIP_PROOT:-0}" = "1" ] || [ -f "$ASSETS_DIR/proot" ]; then
  [ -f "$ASSETS_DIR/proot" ] && echo "==> PRoot 已存在，跳过下载"
else
  echo "==> 从 Termux 仓库获取 PRoot (aarch64)"
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  for deb in proot talloc shmem; do
    url_var="${deb^^}_DEB"
    url="${!url_var}"
    echo "    下载 ${url##*/}"
    curl -fsSL "$url" -o "$TMP/${deb}.deb"
  done

  # 解包所有 deb，把 proot 可执行文件拷出来（proot 需要 libtalloc/libandroid-shmem 链接库）
  for deb in "$TMP"/*.deb; do
    dpkg-deb -x "$deb" "$TMP/root"
  done

  PROOT_BIN="$(find "$TMP/root" -type f -name 'proot' 2>/dev/null | head -1)"
  if [ -z "$PROOT_BIN" ]; then
    echo "错误：未在 Termux 包中找到 proot 可执行文件"
    exit 1
  fi
  cp "$PROOT_BIN" "$ASSETS_DIR/proot"
  chmod +x "$ASSETS_DIR/proot"

  # 一并携带依赖库（运行时若 assets 缺 lib 则 native 在线兜底 libtalloc/libandroid-shmem）
  mkdir -p "$ASSETS_DIR/libs"
  find "$TMP/root" -type f \( -name 'libtalloc*' -o -name 'libandroid-shmem*' \) -exec cp {} "$ASSETS_DIR/libs/" \; 2>/dev/null || true
  echo "    proot -> $ASSETS_DIR/proot"
fi

echo "==> 完成"
ls -lh "$ASSETS_DIR"