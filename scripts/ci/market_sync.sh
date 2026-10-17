#!/usr/bin/env bash
# CI/本地共用: Xposed-Modules-Repo 市场仓库同步——README 变更日志插段 + 稳定版 Release(单legacy包) + api102 prerelease。
# 用法: market_sync.sh <tag> <vn> <legacy.apk|-> <api102.apk|-> <release_notes.md> <changelog首行摘要>
#   环境变量: GH_TOKEN 必须是能写 Xposed-Modules-Repo/com.chekayo.feishuantirecall 的 PAT (CI: MARKET_PAT)
# 市场仓库铁律: 任何 Release 绝不多包(更新器行为不可控)——稳定版只挂 legacy 包。
set -euo pipefail
TAG="$1"; VN="$2"; LEGACY="$3"; API102="$4"; NOTES="$5"; SUMMARY="$6"
MARKET="Xposed-Modules-Repo/com.chekayo.feishuantirecall"
gh_retry() { for i in 1 2 3 4 5; do out=$(gh api "$@" 2>/dev/null) && { echo "$out"; return 0; }; sleep $((i*2)); done; echo "GH_FAILED" >&2; return 1; }

# 1. README 变更日志插段
gh_retry "repos/$MARKET/contents/README.md" > /tmp/market_readme.json
python3 - "$VN" "$SUMMARY" <<'PY' > /tmp/market_put.json
import base64, json, sys
vn, summary = sys.argv[1], sys.argv[2]
meta = json.load(open('/tmp/market_readme.json'))
text = base64.b64decode(meta['content']).decode('utf-8')
entry = f"### v{vn}\n\n- {summary}\n\n"
marker = "## 更新日志\n\n"
assert marker in text and f"### v{vn}\n" not in text, 'marker/already-present'
text = text.replace(marker, marker + entry, 1)
json.dump({'message': f'docs: 变更日志 v{vn}',
           'content': base64.b64encode(text.encode('utf-8')).decode('ascii'),
           'sha': meta['sha']}, open('/tmp/market_put.json', 'w', encoding='utf-8'))
print('payload ok')
PY
gh_retry -X PUT "repos/$MARKET/contents/README.md" --input /tmp/market_put.json >/dev/null && echo "market README updated"

# 2. 稳定版 Release(单 legacy 包)
if [ "$LEGACY" != "-" ]; then
  gh release create "$TAG" -R "$MARKET" -t "v${VN} · ${SUMMARY}（legacy 稳定版；api102 见预览版）" -F "$NOTES" 2>/dev/null || echo "stable release exists"
  for i in 1 2 3 4 5; do gh release upload "$TAG" -R "$MARKET" --clobber "$LEGACY" 2>/dev/null && { echo "market stable uploaded"; break; }; sleep $((i*2)); done
fi

# 3. api102 prerelease
if [ "$API102" != "-" ]; then
  gh release create "$TAG" -R "$MARKET" -t "v${VN} · 预览版（仅 libxposed API 102 新框架）" -F "$NOTES" --prerelease 2>/dev/null || echo "prerelease exists"
  for i in 1 2 3 4 5; do gh release upload "$TAG" -R "$MARKET" --clobber "$API102" 2>/dev/null && { echo "market prerelease uploaded"; break; }; sleep $((i*2)); done
fi
