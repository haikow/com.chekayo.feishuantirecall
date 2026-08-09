#!/usr/bin/env python3
"""
飞书 Linux 桌面端补丁：已读 → 对方显示未读（屏蔽已读回执）

用法:
    sudo python3 patch_feishu_unread.py [飞书安装根目录]
    # 默认根目录 /opt/bytedance/feishu

机制:
    飞书桌面端的 JS SDK worker 在你看过消息后，会调用
        <logger>.info("updateMessagesMeRead", {...payload})
    把已读的 messageId 列表上报给服务器。本补丁在该调用前注入
        t.messageIds=[],
    把上报列表清空 —— 服务器收不到你的已读回执，对方界面一直显示"未读"。

    实际加载的是 webcontent/messenger-next.asar（不是遗留的 messenger.asar，
    后者飞书新版根本不加载；用 `sudo lsof | grep asar` 可确认）。

维护:
    飞书每次自动更新会覆盖 asar，更新后重跑本脚本即可。
    脚本以 .bak 为纯净基线、幂等；命中数为 0（版本不兼容）时中止、不改动原文件。

回滚:
    sudo cp <asar>.bak <asar>   然后重启飞书

局限（只堵主入口）:
    引用回复、给消息贴表情、接收对方文件 时仍会上报已读。

关于"防撤回": 已证实在本架构（native/worker 落地撤回，UI 被动显示）下
    无法用 asar/JS 层实现，详见仓库其它逆向文档。
"""
import re
import sys
import shutil
import subprocess
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from asar import Asar

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/opt/bytedance/feishu")
WORK = Path("/tmp/feishu-unread-build")

UNREAD_PAT = re.compile(rb'\w+\.\w+\.info\("updateMessagesMeRead"')
UNREAD_INJECT = b"t.messageIds=[],"


def find_asar():
    # 飞书新版实际加载 messenger-next.asar；messenger.asar 是遗留文件、未被加载
    for name in ("webcontent/messenger-next.asar", "webcontent/messenger.asar"):
        p = next(ROOT.rglob(name), None)
        if p:
            return p
    return None


def main():
    asar = find_asar()
    if not asar:
        sys.exit(f"未找到 messenger-next.asar（{ROOT}）")
    bak = asar.with_suffix(".asar.bak")

    if not bak.exists():
        print(f"备份: {asar} -> {bak}")
        shutil.copy2(asar, bak)
    else:
        print(f"检测到已有备份 {bak}，以它为纯净基线重打补丁")

    if WORK.exists():
        shutil.rmtree(WORK)
    with Asar.open(bak) as a:
        a.extract(WORK)

    n_unread = 0
    for js in WORK.rglob("*.js"):
        try:
            b = js.read_bytes()
        except Exception:
            continue
        m = UNREAD_PAT.search(b)
        if m and b[max(0, m.start() - len(UNREAD_INJECT)):m.start()] != UNREAD_INJECT:
            js.write_bytes(b[:m.start()] + UNREAD_INJECT + b[m.start():])
            n_unread += 1

    print(f"[已读→未读] 注入点: {n_unread}")
    if n_unread == 0:
        shutil.rmtree(WORK, ignore_errors=True)
        sys.exit("❌ 未命中，疑似版本不兼容，已中止（未改动原文件）")

    Asar.pack(WORK, str(asar))
    shutil.rmtree(WORK)
    subprocess.run(["chown", "root:root", str(asar)], check=False)
    asar.chmod(0o644)
    print(f"✅ 已写入 {asar}\n请彻底退出飞书后重启："
          f"\n    sudo pkill -x -9 feishu ; setsid /usr/bin/bytedance-feishu &")


if __name__ == "__main__":
    main()
