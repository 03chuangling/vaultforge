#!/usr/bin/env bash
# 构建 VaultForge Server 发布包：
#   dist/ 下产出 linux amd64 / arm64 的发布 tarball（含 install.sh / systemd 单元 / README / smoke.sh / LICENSE）
#   以及裸二进制与 SHA256SUMS。
# 用法：./scripts/release.sh（在 server/ 目录下执行）
set -u

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VER="$(sed -n 's/.*const Version = "\(.*\)"/\1/p' internal/config/config.go | head -1)"
if [ -z "$VER" ]; then
  echo "无法从 internal/config/config.go 解析版本号"
  exit 1
fi

echo "==> 发布 VaultForge Server v$VER"
DIST="$ROOT/dist"
rm -rf "$DIST"
mkdir -p "$DIST"

for ARCH in amd64 arm64; do
  NAME="vaultforge-server-v$VER-linux-$ARCH"
  echo "==> 构建 $ARCH"
  GOOS=linux GOARCH="$ARCH" CGO_ENABLED=0 go build -trimpath -ldflags "-s -w" \
    -o "$DIST/vaultforge-server-linux-$ARCH" . || exit 1

  STAGE="$DIST/$NAME"
  mkdir -p "$STAGE"
  cp "$DIST/vaultforge-server-linux-$ARCH" "$STAGE/vaultforge-server"
  cp README.md "$STAGE/README.md"
  cp deploy/vaultforge-server.service deploy/install.sh "$STAGE/"
  cp scripts/smoke.sh "$STAGE/smoke.sh"
  if [ -f "$ROOT/../LICENSE" ]; then
    cp "$ROOT/../LICENSE" "$STAGE/LICENSE"
  fi
  (cd "$DIST" && tar czf "$NAME.tar.gz" "$NAME")
  rm -rf "$STAGE"
done

echo "==> 生成 SHA256SUMS"
(cd "$DIST" && sha256sum vaultforge-server-linux-amd64 vaultforge-server-linux-arm64 ./*.tar.gz > SHA256SUMS)

echo "==> 完成："
ls -lh "$DIST" | grep -v '^total'
cat "$DIST/SHA256SUMS"