#!/usr/bin/env python3
"""Validate added version declarations in PR file patches; never execute PR code."""
import argparse
import json
import re
import sys


def check_versions(files, base_branch, release_tags):
    if base_branch not in ("main", "libxposed-api-102-migration"):
        raise ValueError("目标分支不属于已知发布线，需人工确认版本规则")
    modern = base_branch == "libxposed-api-102-migration"
    suffix = "-api102" if modern else ""
    metadata = "version-api102.json" if modern else "version.json"
    patterns = {
        "app/src/main/AndroidManifest.xml": (
            r'^\s*android:versionCode="([0-9]+)"',
            r'^\s*android:versionName="([^"]+)"'),
        "app/src/main/java/com/chekayo/feishuantirecall/AntiRecall.java": (
            r'^\s*static final int MODULE_VERSION_CODE = ([0-9]+);',
            r'^\s*static final String MODULE_VERSION = "([^"]+)";'),
        metadata: (r'^\s*"versionCode": ([0-9]+),?\s*$',
                   r'^\s*"versionName": "([^"]+)",?\s*$'),
    }
    if modern:
        patterns["app/src/main/resources/META-INF/xposed/module.prop"] = (
            r'^versionCode=([0-9]+)\s*$', r'^version=([^\r\n]+)\s*$')
    patches = {f["filename"]: f.get("patch", "") for f in files}
    values = []
    for path, (code_pattern, name_pattern) in patterns.items():
        # Count declarations only in their own production file, not comments,
        # documentation, fixtures, or unrelated JSON files.
        added = "\n".join(line[1:] for line in patches.get(path, "").splitlines()
                          if line.startswith("+") and not line.startswith("+++"))
        codes = re.findall(code_pattern, added, re.MULTILINE)
        names = re.findall(name_pattern, added, re.MULTILINE)
        if len(codes) != 1 or len(names) != 1:
            raise ValueError(f"{path} 必须各有一处新增 versionCode 和 versionName 声明")
        if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+" + re.escape(suffix), names[0]):
            raise ValueError(f"{path} 的版本名不符合当前发布线后缀 {suffix or '(无后缀)'}")
        values.append((int(codes[0]), names[0]))
    if len(set(values)) != 1:
        raise ValueError("Manifest、代码常量、更新元数据及模块属性的版本不一致")
    version_code, version_name = values[0]
    tag_pattern = r"([0-9]+)-[0-9]+\.[0-9]+\.[0-9]+" + re.escape(suffix)
    published = [int(m.group(1)) for tag in release_tags
                 for m in [re.fullmatch(tag_pattern, tag)] if m]
    if not published:
        raise ValueError("没有找到当前发布线的已发布版本，需人工确认，禁止按零放行")
    latest = max(published)
    if version_code <= latest:
        raise ValueError(f"versionCode {version_code} 必须大于同发布线已发布的 {latest}")
    return f"v{version_name}（versionCode {version_code}，{len(values)} 处一致）> 同线 release vc{latest}；更新元数据 {metadata}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-branch", required=True)
    parser.add_argument("--release-tags", required=True, help="Non-draft release tags as JSON")
    args = parser.parse_args()
    try:
        print(check_versions(json.load(sys.stdin), args.base_branch,
                             json.loads(args.release_tags)))
    except (ValueError, KeyError, TypeError) as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
