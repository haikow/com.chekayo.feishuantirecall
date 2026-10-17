#!/usr/bin/env bash
# 生成 GitHub Release 说明(markdown) 到 stdout。
# 用法: gen_notes.sh <tag> <dist目录>
set -euo pipefail
TAG="$1"; DIST="$2"
VC="${TAG%%-*}"; VN="${TAG#*-}"
LINE=legacy; case "$TAG" in *-api102) LINE=api102;; esac

python3 - "$VC" "$VN" "$LINE" "$DIST" <<'PY'
import json, hashlib, os, sys
vc, vn, line, dist = sys.argv[1:5]
def sha(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f: h.update(f.read())
    return h.hexdigest()
def apk(name):
    for f in sorted(os.listdir(dist)):
        if f.startswith('feishukit-') and f.endswith('.apk') and name in f:
            return os.path.join(dist, f)
    return None
chg = json.load(open('version.json', encoding='utf-8'))['changelog']
items = '\n'.join('- ' + l.lstrip('· ') for l in chg.split('\n') if l.strip())
L, A = apk('-legacy'), apk('-api102')
if line == 'legacy' and not L: L = apk(vn)  # 无 -legacy 后缀的旧命名兜底

print(f"### v{vn}\n")
if line == 'legacy' and L and A:
    print(f"""| 包 | 适用框架 | 下载 |
|---|---|---|
| **`{os.path.basename(L)}`**（大多数用户） | 传统 LSPosed / XposedBridge API 82 | 本页附件 |
| **`{os.path.basename(A)}`**（预览） | 仅 libxposed API 102 新框架 | 本页附件 |

- 两个包**功能一致**、包名相同、同一签名——装错可互相覆盖安装，数据不丢；**不确定就装 legacy**
""")
elif line == 'api102':
    print("> ⚠️ 预览版：仅供支持 libxposed API 102 的新框架；老 LSPosed 用户请用稳定版 Release。\n")
print(f"### 更新内容\n\n{items}\n")
print("""arm64-v8a · LSPosed（legacy: Xposed API 82+ / api102: libxposed 102）· 支持国内版飞书 / 国际版 Lark / 飞书二次开发版
💬 讨论群（Telegram）：https://t.me/fucklark
""")
print("```")
for f, label in ((L, 'legacy'), (A, 'api102')):
    if f: print(f"{label:6s}: {os.path.basename(f):42s} SHA-256 = {sha(f)}")
print("```")
PY
