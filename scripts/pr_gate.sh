#!/usr/bin/env bash
# PR 自动审查门禁 —— 机械式安全检查 + 版本一致性检查, 结果评论到 PR
#
# 用法: scripts/pr_gate.sh <PR编号> [--no-comment]
# 退出码: 0=通过(可自动合入)  1=不通过(需人工审查)  2=工具/网络错误
#
# 检查项(全部来自人工审查经验):
#   G1 签名自校验 EXPECTED_SIG 不得被改动
#   G2 新增密钥/证书文件(.jks/.keystore/.pk8/.pem/.x509) → 拦
#   G3 native/jni 改动(.c/.cpp/.h/.so/jni/) → 人工审查
#   G4 发布/构建脚本改动(build.ps1/build.sh/make_release.sh 等) → 人工审查
#      (构建脚本在签名机本地跑, 被篡改可窃取私钥)
#   G5 新增网络/执行/动态加载代码: 按「新增-删除」计数, 净新增才拦(纯搬移代码不误报)
#   G6 新增 URL 必须在白名单内(镜像/自家仓库/t.me/文档); .md 文档豁免 URL 检查
#   G7 版本一致性: Manifest / AntiRecall / 对应发布线 JSON（modern 另含 module.prop）
#      必须一致, 且 versionCode > 同发布线全部非 draft Release（包含 prerelease）
set -u
REPO="haikow/com.chekayo.feishuantirecall"
PR="${1:?用法: $0 <PR编号> [--no-comment]}"
NO_COMMENT=0; [ "${2:-}" = "--no-comment" ] && NO_COMMENT=1

gh_retry() { for i in 1 2 3 4 5 6; do out=$(gh api "$@" 2>/dev/null) && { echo "$out"; return 0; }; sleep $((i*2)); done; return 1; }
gh_run() { for i in 1 2 3 4 5 6; do out=$(gh "$@" 2>/dev/null) && { echo "$out"; return 0; }; sleep $((i*2)); done; return 1; }

# ── 取 PR 元数据 / diff / 文件清单 ──────────────────────────────
META=$(gh_run pr view "$PR" -R "$REPO" --json headRefOid,additions,deletions,changedFiles,author,baseRefName \
       --jq '[.headRefOid,.additions,.deletions,.changedFiles,.author.login,.baseRefName]|@tsv') || { echo "取 PR 元数据失败"; exit 2; }
HEAD_SHA=$(echo "$META" | cut -f1); ADD=$(echo "$META" | cut -f2); DEL=$(echo "$META" | cut -f3)
NFILES=$(echo "$META" | cut -f4); AUTHOR=$(echo "$META" | cut -f5)
BASE_BRANCH=$(echo "$META" | cut -f6)
DIFF=$(gh_run pr diff "$PR" -R "$REPO") || { echo "取 diff 失败"; exit 2; }
FILE_PAGES=$(gh_retry --paginate --slurp "repos/$REPO/pulls/$PR/files?per_page=100") || { echo "取文件清单失败"; exit 2; }
FILES_JSON=$(printf '%s\n' "$FILE_PAGES" | python -c 'import json,sys; print(json.dumps([f for page in json.load(sys.stdin) for f in page]))') \
  || { echo "解析文件清单失败"; exit 2; }

FAIL=0; REPORT="## 🤖 自动审查 · PR #$PR

作者 @$AUTHOR · head \`$HEAD_SHA\` · +$ADD/-$DEL · $NFILES 个文件

"
note_fail() { FAIL=1; REPORT="$REPORT
❌ **$1**
$2
"; }
note_warn() { REPORT="$REPORT
⚠️ $1
"; }
note_ok() { REPORT="$REPORT
✅ $1
"; }

# ── G1 签名自校验 ───────────────────────────────────────────────
if echo "$DIFF" | grep -qE '^[+-].*EXPECTED_SIG'; then
  note_fail "G1 签名自校验" "PR 改动了 \`\$EXPECTED_SIG\` 钉住的证书指纹——绝对不允许, 需作者说明原因并人工核实。"
else
  note_ok "G1 签名自校验未被改动"
fi

# ── G2/G3/G4 文件级检查 ─────────────────────────────────────────
KEYFILES=$(echo "$FILES_JSON" | python -c "
import sys,json
bad=[f['filename'] for f in json.load(sys.stdin)
     if f['filename'].split('?')[0].endswith(('.jks','.keystore','.pk8','.pem','.x509','.p12'))]
print('\n'.join(bad))" 2>/dev/null)
NATIVE=$(echo "$FILES_JSON" | python -c "
import sys,json
bad=[f['filename'] for f in json.load(sys.stdin)
     if f['filename'].startswith('jni/') or f['filename'].split('?')[0].endswith(('.c','.cpp','.cc','.h','.so'))]
print('\n'.join(bad))" 2>/dev/null)
BUILDSL=$(echo "$FILES_JSON" | python -c "
import sys,json
pat=('build.ps1','build.sh','scripts/make_release.sh','scripts/tg_release.sh','scripts/pr_gate.sh','scripts/check_pr_versions.py','.github/workflows/')
bad=[f['filename'] for f in json.load(sys.stdin) if f['filename'].startswith(pat)]
print('\n'.join(bad))" 2>/dev/null)
[ -n "$KEYFILES" ] && note_fail "G2 新增密钥/证书文件" "\`\`\`$(echo $KEYFILES)\`\`\`" || note_ok "G2 无密钥/证书文件"
[ -n "$NATIVE" ] && note_fail "G3 native/jni 改动" "以下 native 相关文件变更, 二进制/钩子代码无法机械审查, 需人工确认:
\`\`\`$(echo $NATIVE)\`\`\`" || note_ok "G3 无 native/jni 改动"
[ -n "$BUILDSL" ] && note_fail "G4 构建/发布脚本被改动" "构建脚本在本机带私钥环境运行, 外部 PR 改动一律人工审查:
\`\`\`$(echo $BUILDSL)\`\`\`" || note_ok "G4 构建/发布脚本未改动"

# ── G5 危险 API 净新增计数(新增-删除, 纯搬移为 0) ────────────────
DANGER_PAT='HttpURLConnection|openConnection|URL\(|Socket\(|DatagramSocket|getRuntime\(\)|ProcessBuilder|DexClassLoader|InMemoryDexClassLoader|setExecutable|chmod'
added_code=$(echo "$DIFF" | grep -E '^\+' | grep -v '^+++' | grep -vE '^\+\+\+ .*\.md')
removed_code=$(echo "$DIFF" | grep -E '^-' | grep -v '^---' | grep -vE '^--- .*\.md')
NET_ADD=$(echo "$added_code"   | grep -cE "$DANGER_PAT")
NET_DEL=$(echo "$removed_code" | grep -cE "$DANGER_PAT")
if [ "$NET_ADD" -gt "$NET_DEL" ]; then
  note_fail "G5 净新增网络/执行/动态加载代码" "新增 $NET_ADD 处 / 删除 $NET_DEL 处危险 API 调用, 需人工确认用途:"
  while read -r l; do REPORT="$REPORT> \`$(echo "$l" | cut -c1-120)\`
"; done <<< "$(echo "$added_code" | grep -E "$DANGER_PAT" | head -10)"
else
  note_ok "G5 无净新增网络/执行代码(搬移 $NET_ADD 处不计)"
fi

# ── G6 新增 URL 白名单(.md 豁免) ─────────────────────────────────
URL_WHITELIST='https?://(ghproxy\.net|gh-proxy\.com|cdn\.jsdelivr\.net|fastly\.jsdelivr\.net)/|https?://raw\.githubusercontent\.com/haikow/|https?://github\.com/(haikow|LSPosed|SonderZhong)|https?://t\.me/|https?://ifdian\.net|schemas\.android\.com'
NEW_URLS=$(echo "$added_code" | grep -oE 'https?://[^"'"'"' )<>]+' | sort -u | grep -vE "$URL_WHITELIST" || true)
if [ -n "$NEW_URLS" ]; then
  note_fail "G6 新增非白名单 URL" "代码中出现白名单之外的网络地址, 需人工确认(防数据外发):
\`\`\`
$(echo "$NEW_URLS" | head -10)
\`\`\`"
else
  note_ok "G6 新增 URL 均在白名单(镜像/自家仓库/t.me)"
fi

# ── G7 版本一致性 ────────────────────────────────────────────────
# Version validation reads patch text as data; it never imports code from the PR.
RELEASE_PAGES=$(gh_retry --paginate --slurp "repos/$REPO/releases?per_page=100") || { echo "取发布版本失败"; exit 2; }
RELEASE_TAGS=$(printf '%s\n' "$RELEASE_PAGES" | python -c 'import json,sys; print(json.dumps([r["tag_name"] for page in json.load(sys.stdin) for r in page if not r["draft"]]))') \
  || { echo "解析发布版本失败"; exit 2; }
SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
if VERSION_RESULT=$(printf '%s\n' "$FILES_JSON" | python "$SCRIPT_DIR/check_pr_versions.py" \
    --base-branch "$BASE_BRANCH" --release-tags "$RELEASE_TAGS" 2>&1); then
  note_ok "G7 版本一致且递增: $VERSION_RESULT"
else
  note_fail "G7 版本一致性不满足" "$VERSION_RESULT"
fi

# ── 汇总 ────────────────────────────────────────────────────────
if [ "$FAIL" -eq 0 ]; then
  REPORT="$REPORT

---
🟢 **门禁全绿, 可合入** (自动流程将: 合并 → 本地签名打包 → 双仓库 Release → 电报群同步)"
else
  REPORT="$REPORT

---
🔴 **门禁未通过, 暂不合入** —— ❌ 项需仓库所有者人工审查后自行决定。"
fi

echo "$REPORT"
if [ "$NO_COMMENT" -eq 0 ]; then
  echo "$REPORT" > /tmp/pr_gate_$PR.md
  for i in 1 2 3 4 5; do gh pr comment "$PR" -R "$REPO" -F /tmp/pr_gate_$PR.md >/dev/null 2>&1 && break; sleep $((i*2)); done
fi
exit $FAIL
