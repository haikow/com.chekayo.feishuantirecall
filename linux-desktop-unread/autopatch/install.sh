#!/usr/bin/env bash
# 一键部署"飞书 asar 自动重打"(systemd 系统级, root 运行)。
# 效果: 飞书自动更新覆盖 asar 后, 监听/定时自动重跑补丁, 无需手动。
# 用法: sudo bash install.sh
set -e
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PARENT="$(dirname "$HERE")"
DST=/usr/local/lib/feishu-patch

[ "$(id -u)" = 0 ] || { echo "请用 sudo 运行"; exit 1; }

echo "1) 安装补丁脚本到 $DST"
mkdir -p "$DST"
cp "$PARENT/patch_feishu.py" "$PARENT/asar.py" "$HERE/autopatch.sh" "$DST/"
chmod +x "$DST/autopatch.sh"

echo "2) 安装 systemd 单元"
cp "$HERE/feishu-autopatch.service" "$HERE/feishu-autopatch.path" "$HERE/feishu-autopatch.timer" /etc/systemd/system/

echo "3) 启用"
systemctl daemon-reload
systemctl enable --now feishu-autopatch.path feishu-autopatch.timer

echo "4) 立即跑一次(确保当前 asar 已打)"
"$DST/autopatch.sh" || true

echo "✅ 完成。查看日志: journalctl -u feishu-autopatch.service -f"
echo "   卸载: sudo bash uninstall.sh"
