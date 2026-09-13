#!/usr/bin/env bash
# VaultForge Server 一键安装脚本（systemd，需 root）。
#
# 用法：
#   1) 上传发布包：scp vaultforge-server-v0.3.0-linux-amd64.tar.gz root@<server>:/tmp/
#   2) 解压并安装：
#      cd /tmp && tar xzf vaultforge-server-*.tar.gz
#      cd vaultforge-server-*/ && sudo ./install.sh
set -u

if [ "$(id -u)" != "0" ]; then
  echo "请用 root 运行：sudo ./install.sh"
  exit 1
fi

SRC="$(cd "$(dirname "$0")" && pwd)"
DEST=/opt/vaultforge

if [ ! -x "$SRC/vaultforge-server" ]; then
  echo "未找到二进制文件：$SRC/vaultforge-server"
  exit 1
fi

echo "==> 安装到 $DEST"
mkdir -p "$DEST/data"
install -m 755 "$SRC/vaultforge-server" "$DEST/vaultforge-server"
install -m 644 "$SRC/vaultforge-server.service" /etc/systemd/system/vaultforge-server.service

systemctl daemon-reload
systemctl enable vaultforge-server >/dev/null 2>&1 || true
systemctl restart vaultforge-server

sleep 1
echo "==> 服务状态："
systemctl status vaultforge-server --no-pager 2>/dev/null | head -10 || true
echo
echo "健康检查：curl http://127.0.0.1:8787/healthz"
echo "首次使用：注册账号（第一个账号会自动继承旧数据），例如："
echo "  curl -X POST http://127.0.0.1:8787/api/v1/auth/register -H 'Content-Type: application/json' -d '{\"username\":\"admin\",\"password\":\"your-pass\"}'"
echo "说明：服务默认仅监听 127.0.0.1，公网访问请配置 nginx/caddy TLS 反代。"