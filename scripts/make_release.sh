#!/usr/bin/env bash
# 发版助手：给已构建的 APK 生成【版本化文件名 + SHA-256 + 证书指纹】的 release 正文。
# 用法：
#   scripts/make_release.sh                      # 用默认 APK，自动读版本号
#   scripts/make_release.sh path/to/xxx.apk      # 指定 APK
# 产物（写到 build/release/）：
#   fucklark-v<版本>-vc<code>-arm64-release.apk  # 版本化重命名副本（上传用）
#   RELEASE_NOTES.md                             # release 正文（贴到 GitHub Release）
#   同时打印 gh release create 命令，可直接复制执行。
set -euo pipefail

PROJ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJ"

APK="${1:-feishu-antirecall.apk}"
[[ -f "$APK" ]] || { echo "找不到 APK：$APK（先跑 ./build.sh）" >&2; exit 1; }

MF="app/src/main/AndroidManifest.xml"
VN="$(grep -oE 'android:versionName="[^"]+"' "$MF" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
VC="$(grep -oE 'android:versionCode="[^"]+"'  "$MF" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
[[ -n "$VN" && -n "$VC" ]] || { echo "读不到版本号" >&2; exit 1; }

# 定位 apksigner（复用 build.sh 的逻辑：取最高版 build-tools）
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BT="$(ls -d "$SDK"/build-tools/*/ 2>/dev/null | sort -V | tail -1)"
APKSIGNER="${BT}apksigner"

OUTDIR="build/release"
mkdir -p "$OUTDIR"
NEWNAME="fucklark-v${VN}-vc${VC}-arm64-release.apk"
cp -f "$APK" "$OUTDIR/$NEWNAME"

APK_SHA="$(sha256sum "$OUTDIR/$NEWNAME" | awk '{print $1}')"
APK_SIZE="$(stat -c%s "$OUTDIR/$NEWNAME")"
APK_SIZE_H="$(numfmt --to=iec --suffix=B "$APK_SIZE" 2>/dev/null || echo "${APK_SIZE}B")"

# 证书指纹 + 签名方案
CERT_SHA="$( "$APKSIGNER" verify --print-certs "$OUTDIR/$NEWNAME" 2>/dev/null \
             | grep -iE 'SHA-256 digest' | head -1 | sed -E 's/.*: *//')"
VERIFY="$( "$APKSIGNER" verify -v "$OUTDIR/$NEWNAME" 2>/dev/null )"
SCHEMES=""
for s in v1 v2 v3; do
    if echo "$VERIFY" | grep -iqE "using ${s} scheme.*: true"; then
        SCHEMES="${SCHEMES:+$SCHEMES / }${s}"
    fi
done

NOTES="$OUTDIR/RELEASE_NOTES.md"
cat > "$NOTES" <<EOF
# fuck lark v${VN}

- versionName：\`${VN}\`
- versionCode：\`${VC}\`
- applicationId：\`com.chekayo.feishuantirecall\`
- 架构：arm64-v8a
- 目标：国内版飞书 \`com.ss.android.lark\` / 国际版 Lark \`com.larksuite.suite\` / 飞书二次开发版（自动适配）

## 发布资产

| 文件 | 大小 | SHA-256 |
| --- | ---: | --- |
| \`${NEWNAME}\` | ${APK_SIZE} bytes | \`${APK_SHA}\` |

## 签名

- Release 签名证书 SHA-256：\`${CERT_SHA}\`
- 签名方案：${SCHEMES:-v1 / v2 / v3}
- 该证书为作者唯一正式证书，模块内置签名自校验（证书不符即判定被重打包，禁用核心功能）。**请仅从本仓库 Release 下载**，第三方重签名版本会失效。

## 校验方法

下载后核对 APK 摘要是否一致：
\`\`\`
sha256sum ${NEWNAME}
# 应输出：${APK_SHA}
\`\`\`

## 主要变更

<!-- 在此填写本版更新点（可从 README 更新日志复制对应版本条目） -->

EOF

echo "======================================================================"
echo "  版本         : v${VN} (vc${VC})"
echo "  版本化 APK   : $OUTDIR/$NEWNAME  (${APK_SIZE_H})"
echo "  APK SHA-256  : $APK_SHA"
echo "  证书 SHA-256 : $CERT_SHA"
echo "  签名方案     : ${SCHEMES:-v1 / v2 / v3}"
echo "  Release 正文 : $NOTES  （已填好，记得补『主要变更』）"
echo "======================================================================"
echo
echo "填好『主要变更』后，可直接发布（Xposed tag 格式：<vc>-<版本名>）："
echo
echo "  gh release create ${VC}-${VN} \\"
echo "    -R haikow/com.chekayo.feishuantirecall \\"
echo "    -t 'fuck lark v${VN}' \\"
echo "    -F $NOTES \\"
echo "    $OUTDIR/$NEWNAME"
echo
echo "  # 官方仓同理，把 -R 换成 Xposed-Modules-Repo/com.chekayo.feishuantirecall"
