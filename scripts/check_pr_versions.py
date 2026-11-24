#!/usr/bin/env python3
"""Fail-closed PR version consistency and monotonic increment validator (G7)."""

import argparse
import json
import re
import sys

MANIFEST = "app/src/main/AndroidManifest.xml"
JAVA = "app/src/main/java/com/chekayo/feishuantirecall/AntiRecall.java"
PROP = "app/src/main/resources/META-INF/xposed/module.prop"

CHANNELS = {
    "main": {
        "files": (MANIFEST, JAVA, "version.json"),
        "version_re": re.compile(r"^\d+(?:\.\d+)+$"),
        "tag_re": re.compile(r"^(\d+)-[^-]+$"),
        "label": "legacy (main)",
    },
    "libxposed-api-102-migration": {
        "files": (MANIFEST, JAVA, "version-api102.json", PROP),
        "version_re": re.compile(r"^\d+(?:\.\d+)+-api102$"),
        "tag_re": re.compile(r"^(\d+)-[^-]+-api102$"),
        "label": "modern (api102)",
    },
}


def _added_lines(patch):
    lines = []
    for raw in patch.splitlines():
        if raw.startswith("+") and not raw.startswith("+++"):
            lines.append(raw[1:])
    return lines


def _strip_c_comments(text, strip_hash=False):
    """Strip // and /* ... */ (and optionally #) comments outside double-quoted strings."""
    out = []
    i = 0
    n = len(text)
    in_str = False
    while i < n:
        ch = text[i]
        if in_str:
            out.append(ch)
            if ch == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if ch == '"':
                in_str = False
            i += 1
            continue
        if ch == '"':
            in_str = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if ch == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                if text[i] == "\n":
                    out.append("\n")
                i += 1
            i = i + 2 if i + 1 < n else n
            continue
        if strip_hash and ch == "#":
            while i < n and text[i] != "\n":
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def _strip_xml_comments(text):
    cleaned = re.sub(r"<!--.*?-->", "", text, flags=re.DOTALL)
    # Fail closed on unclosed XML comments.
    if "<!--" in cleaned:
        cleaned = cleaned.split("<!--", 1)[0]
    return cleaned


def _single_match(pattern, text, path, field):
    matches = pattern.findall(text)
    if len(matches) == 0:
        raise ValueError(f"{path} 缺少有效的 {field} 声明")
    if len(matches) > 1:
        raise ValueError(f"{path} 存在重复的 {field} 声明（共 {len(matches)} 处）")
    return matches[0]


def _parse_manifest(patch, path):
    body = _strip_xml_comments("\n".join(_added_lines(patch)))
    code = int(_single_match(re.compile(r'\bandroid:versionCode\s*=\s*"(\d+)"'), body, path, "android:versionCode"))
    name = _single_match(re.compile(r'\bandroid:versionName\s*=\s*"([^"]+)"'), body, path, "android:versionName")
    return code, name


def _parse_java(patch, path):
    body = _strip_c_comments("\n".join(_added_lines(patch)))
    code = int(_single_match(
        re.compile(r"(?:^|\s)(?:(?:public|private|protected)\s+)?static\s+final\s+int\s+MODULE_VERSION_CODE\s*=\s*(\d+)\s*;"),
        body, path, "MODULE_VERSION_CODE",
    ))
    name = _single_match(
        re.compile(r'(?:^|\s)(?:(?:public|private|protected)\s+)?static\s+final\s+String\s+MODULE_VERSION\s*=\s*"([^"]+)"\s*;'),
        body, path, "MODULE_VERSION",
    )
    return code, name


def _parse_json(patch, path):
    body = _strip_c_comments("\n".join(_added_lines(patch)), strip_hash=True)
    code = int(_single_match(
        re.compile(r'^\s*"versionCode"\s*:\s*(\d+)\s*[,}]?\s*$', re.MULTILINE),
        body, path, "versionCode",
    ))
    name = _single_match(
        re.compile(r'^\s*"versionName"\s*:\s*"([^"]+)"\s*[,}]?\s*$', re.MULTILINE),
        body, path, "versionName",
    )
    return code, name


def _parse_prop(patch, path):
    active = []
    for line in _added_lines(patch):
        stripped = line.lstrip()
        if not stripped or stripped.startswith(("#", "!", "//", "/*")):
            continue
        active.append(line)
    body = "\n".join(active)
    code = int(_single_match(
        re.compile(r"^\s*versionCode\s*=\s*(\d+)\s*$", re.MULTILINE),
        body, path, "versionCode",
    ))
    name = _single_match(
        re.compile(r"^\s*version\s*=\s*([^\s#]+)\s*$", re.MULTILINE),
        body, path, "version",
    )
    return code, name


def _parse_file(path, patch):
    if path == MANIFEST:
        return _parse_manifest(patch, path)
    if path == JAVA:
        return _parse_java(patch, path)
    if path in ("version.json", "version-api102.json"):
        return _parse_json(patch, path)
    if path == PROP:
        return _parse_prop(patch, path)
    raise ValueError(f"未知版本文件: {path}")


def check_versions(files, base_branch, release_tags):
    if base_branch not in CHANNELS:
        raise ValueError(f"不支持的目标分支/发布线: {base_branch!r}")
    if not isinstance(files, list):
        raise ValueError("PR 文件列表格式无效")
    if not isinstance(release_tags, list):
        raise ValueError("Release tag 列表格式无效")

    channel = CHANNELS[base_branch]
    required = channel["files"]
    by_name = {}
    for entry in files:
        if not isinstance(entry, dict) or not isinstance(entry.get("filename"), str):
            raise ValueError("PR 文件条目格式无效")
        fname = entry["filename"]
        if fname in required:
            if fname in by_name:
                raise ValueError(f"PR 文件列表包含重复条目: {fname}")
            by_name[fname] = entry

    parsed = {}
    for path in required:
        if path not in by_name:
            raise ValueError(f"缺少必需的版本文件改动: {path}")
        patch = by_name[path].get("patch")
        if not isinstance(patch, str) or not patch.strip():
            raise ValueError(f"{path} 缺少 patch 内容（可能被截断），拒绝放行")
        parsed[path] = _parse_file(path, patch)

    codes = {code for code, _ in parsed.values()}
    names = {name for _, name in parsed.values()}
    if len(codes) != 1 or len(names) != 1:
        detail = ", ".join(f"{p}=({c}, {n})" for p, (c, n) in parsed.items())
        raise ValueError(f"各处版本号不一致: {detail}")

    version_code = next(iter(codes))
    version_name = next(iter(names))

    if not channel["version_re"].fullmatch(version_name):
        raise ValueError(f"{channel['label']} 发布线 versionName 格式/后缀不合法: {version_name!r}")

    released_codes = []
    for tag in release_tags:
        if not isinstance(tag, str):
            continue
        m = channel["tag_re"].fullmatch(tag.strip())
        if m:
            released_codes.append(int(m.group(1)))

    if not released_codes:
        raise ValueError(f"未找到 {channel['label']} 发布线的已发布 Release，无法校验递增")

    max_released = max(released_codes)
    if version_code <= max_released:
        raise ValueError(
            f"versionCode ({version_code}) 必须大于 {channel['label']} 当前最高已发布版本 ({max_released})"
        )

    return f"{version_name} / {version_code}，{len(required)} 处一致（>{max_released}）"


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            try:
                stream.reconfigure(encoding="utf-8")
            except Exception:
                pass
    parser = argparse.ArgumentParser(description="Validate PR version consistency and increment.")
    parser.add_argument("--base-branch", required=True)
    parser.add_argument("--release-tags", required=True)
    args = parser.parse_args(argv)
    try:
        files = json.loads(sys.stdin.read())
        release_tags = json.loads(args.release_tags)
        result = check_versions(files, args.base_branch, release_tags)
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        return 1
    print(result)
    return 0


if __name__ == "__main__":
    sys.exit(main())
