#!/usr/bin/env bash
# 检测飞书 asar 是否被更新覆盖(补丁标记消失), 是则自动重打。以 root 运行。
set -u
ASAR=/opt/bytedance/feishu/webcontent/messenger-next.asar
DIR=/usr/local/lib/feishu-patch
sleep 2   # 等飞书把 asar 写完(避免读到半成品)
[ -f "$ASAR" ] || { echo "$(date '+%F %T') asar 不存在, 跳过"; exit 0; }
if grep -q 'recalledMessageCacheList' "$ASAR" && grep -qF 't.messageIds=[],' "$ASAR"; then
  echo "$(date '+%F %T') 补丁在位, 无需处理"; exit 0
fi
echo "$(date '+%F %T') 检测到 asar 缺补丁(疑似飞书更新), 自动重打..."
python3 "$DIR/patch_feishu.py" && echo "$(date '+%F %T') 重打完成; 飞书下次启动生效" || echo "$(date '+%F %T') 重打失败(版本不兼容?), 未改动原文件"
