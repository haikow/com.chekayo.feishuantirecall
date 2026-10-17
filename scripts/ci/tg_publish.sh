#!/usr/bin/env bash
# CI/本地共用: 电报发布话题双包相册公告 + 撤旧置顶 + 新公告置顶。
# 用法: tg_publish.sh <legacy.apk> <api102.apk|-> <公告HTML文件>
#   环境变量: TG_BOT_TOKEN (必须; CI 从 secret 注入, 本地读 ~/.fucklark_tg_token)
#             TG_PROXY     (可选; 本地填 socks5h://127.0.0.1:7897, CI 留空直连)
#             TG_CHAT/TG_THREAD 可覆盖 (默认 官方讨论群/发布话题)
# "-" 表示无 api102 包(单包模式)。
set -euo pipefail
LEGACY="$1"; API102="$2"; NOTES="$3"
[ -f "$LEGACY" ] && [ -f "$NOTES" ] || { echo "usage: $0 <legacy.apk> <api102.apk|-> <notes.html>"; exit 1; }
[ "$API102" = "-" ] || [ -f "$API102" ] || { echo "api102 包不存在: $API102"; exit 1; }

TOK="${TG_BOT_TOKEN:-$(cat "$HOME/.fucklark_tg_token" 2>/dev/null || true)}"
[ -n "$TOK" ] || { echo "TG_BOT_TOKEN 未设置"; exit 1; }
CHAT="${TG_CHAT:--1004312365887}"
THREAD="${TG_THREAD:-274}"
API="https://api.telegram.org/bot$TOK"
if [ -n "${TG_PROXY:-}" ]; then CURL=(curl -sS --ssl-no-revoke -x "$TG_PROXY"); else CURL=(curl -sS); fi
CURL+=(--connect-timeout 20 --retry 5 --retry-delay 3)

# 1. 撤旧置顶
OLD=$("${CURL[@]}" "$API/getChat?chat_id=$CHAT" | python3 -c "import sys,json;print(json.load(sys.stdin)['result'].get('pinned_message',{}).get('message_id',''))" 2>/dev/null || true)
if [ -n "$OLD" ]; then "${CURL[@]}" -X POST "$API/unpinChatMessage" -d "chat_id=$CHAT" -d "message_id=$OLD" >/dev/null; echo "unpinned=$OLD"; fi

# 2. 公告(置顶由 pinChatMessage 完成)
MSG=$("${CURL[@]}" -X POST "$API/sendMessage" \
  --data-urlencode "chat_id=$CHAT" --data-urlencode "message_thread_id=$THREAD" \
  --data-urlencode "parse_mode=HTML" --data-urlencode "disable_web_page_preview=true" \
  --data-urlencode "text@$NOTES" | python3 -c "import sys,json;print(json.load(sys.stdin)['result']['message_id'])")

# 3. 双包相册 / 单包
LSHA="$(sha256sum "$LEGACY" | awk '{print $1}')"
if [ "$API102" != "-" ]; then
  ASHA="$(sha256sum "$API102" | awk '{print $1}')"
  python3 - "$LEGACY" "$API102" "$LSHA" "$ASHA" <<'PY' > /tmp/tg_media_ci.json
import json, os, sys
lg, ap, lsha, asha = sys.argv[1:5]
media = [
 {"type":"document","media":"attach://lg","parse_mode":"HTML",
  "caption":f"📦 <b>legacy 稳定包</b>（传统 LSPosed / XposedBridge API 82，大多数用户）\n{os.path.basename(lg)}\nSHA-256: <code>{lsha}</code>"},
 {"type":"document","media":"attach://ap","parse_mode":"HTML",
  "caption":f"🧪 <b>api102 预览包</b>（仅 libxposed API 102 新框架，如 LSPosed v2.2+ modern；老框架装了不生效）\n{os.path.basename(ap)}\nSHA-256: <code>{asha}</code>"},
]
print(json.dumps(media, ensure_ascii=False))
PY
  "${CURL[@]}" -X POST "$API/sendMediaGroup" -F "chat_id=$CHAT" -F "message_thread_id=$THREAD" \
    -F 'media=</tmp/tg_media_ci.json' \
    -F "lg=@$LEGACY;type=application/octet-stream" -F "ap=@$API102;type=application/octet-stream" >/dev/null
  echo "dual-package album sent"
else
  printf '📦 %s ｜ arm64-v8a ｜ LSPosed\nSHA-256: %s' "$(basename "$LEGACY")" "$LSHA" > /tmp/tg_cap_ci.txt
  "${CURL[@]}" -X POST "$API/sendDocument" -F "chat_id=$CHAT" -F "message_thread_id=$THREAD" \
    -F "caption=</tmp/tg_cap_ci.txt" -F "document=@$LEGACY;type=application/octet-stream" >/dev/null
  echo "single package sent"
fi

# 4. 置顶新公告
"${CURL[@]}" -X POST "$API/pinChatMessage" -d "chat_id=$CHAT" -d "message_id=$MSG" -d "disable_notification=true" >/dev/null
echo "TG_OK msg=$MSG"
