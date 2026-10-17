#!/usr/bin/env bash
# CI/本地共用: 在指定工作树里构建并校验签名证书指纹。
# 用法: build_and_verify.sh <工作目录> <期望证书SHA256> <输出apk路径>
#   环境变量: KEYSTORE=私钥文件路径(CI 由 KEYSTORE_BASE64 解码而来; 本地为 debug.keystore)
#             ANDROID_HOME 必须已设置(build-tools/platforms 已装)
# 退出码: 0=构建且验签通过; 1=构建失败; 2=验签失败(证书指纹不匹配)
set -euo pipefail
WORKDIR="$(cd "$1" && pwd)"
EXPECT_SIG="$2"
OUT_APK="$3"

cd "$WORKDIR"
if [ -z "${KEYSTORE:-}" ]; then
  if [ -f debug.keystore ]; then export KEYSTORE="$WORKDIR/debug.keystore"; fi
fi
./build.sh >/tmp/ci_build.log 2>&1 || { tail -40 /tmp/ci_build.log; echo "BUILD_FAILED"; exit 1; }
tail -6 /tmp/ci_build.log

CERT="$("$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs feishu-antirecall.apk 2>/dev/null \
  | grep -oE '[0-9a-f]{64}' | head -1)"
if [ "$CERT" != "$EXPECT_SIG" ]; then
  echo "SIGNATURE_MISMATCH got=$CERT want=$EXPECT_SIG — 私钥或代码异常, 拒绝发布"
  exit 2
fi
echo "SIGNATURE_OK $CERT"
mkdir -p "$(dirname "$OUT_APK")"
cp feishu-antirecall.apk "$OUT_APK"
