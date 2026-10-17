#!/usr/bin/env bash
# 生成电报群公告(HTML) 到 stdout。
# 用法: gen_tg_notes.sh <tag>
set -euo pipefail
TAG="$1"
VC="${TAG%%-*}"; VN="${TAG#*-}"
LINE=legacy; case "$TAG" in *-api102) LINE=api102;; esac

python3 - "$VN" "$LINE" "$TAG" <<'PY'
import json, sys
vn, line, tag = sys.argv[1:4]
chg = json.load(open('version.json', encoding='utf-8'))['changelog']
items = '\n'.join('· ' + l.lstrip('· ') for l in chg.split('\n') if l.strip())
print(f"🎯 <b>FeishuKit · 飞书增强模块 v{vn}</b>\n")
print(items + '\n')
if line == 'legacy':
    print("📦 <b>下载按你的框架选</b>（同签名，装错可覆盖安装）：")
    print("· 传统 LSPosed（大多数用户）→ 下方 <b>legacy 稳定包</b>")
    print("· libxposed API 102 新框架 → 下方 <b>api102 预览包</b>\n")
else:
    print("🧪 本帖为 <b>api102 预览包</b>（仅新框架）；老 LSPosed 用户请继续用置顶稳定版\n")
print("适用：国内版飞书 / 国际版 Lark / 飞书二次开发版 ｜ arm64-v8a")
print("安装：启用模块→作用域勾飞书→强停重启飞书")
print(f"GitHub：https://github.com/haikow/com.chekayo.feishuantirecall/releases/tag/{tag}")
PY
