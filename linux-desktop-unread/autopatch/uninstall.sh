#!/usr/bin/env bash
# 卸载"飞书 asar 自动重打"。用法: sudo bash uninstall.sh
set -e
[ "$(id -u)" = 0 ] || { echo "请用 sudo 运行"; exit 1; }
systemctl disable --now feishu-autopatch.path feishu-autopatch.timer 2>/dev/null || true
rm -f /etc/systemd/system/feishu-autopatch.{service,path,timer}
systemctl daemon-reload
rm -rf /usr/local/lib/feishu-patch
echo "✅ 已卸载(飞书 asar 上已打的补丁不受影响, 如需还原用 .bak 覆盖)"
