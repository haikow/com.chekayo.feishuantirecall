#!/usr/bin/env python3
"""
飞书 Linux 桌面端补丁：已读→对方未读(但你回复后才把可视消息标已读, 复刻吾乐吧) + 防撤回(渲染门控 + 内容缓存)
用法: sudo python3 patch_feishu.py [飞书安装根目录]   (默认 /opt/bytedance/feishu)
说明: 每次飞书自动更新后重跑本脚本即可。会先备份为 messenger-next.asar.bak。

防撤回双保险(参考 Windows 吾乐吧补丁的缓存法 + 安卓的原理):
  ① 渲染门控: 撤回气泡占位置死(if(!1))。
  ② 内容缓存(核心): 桌面撤回会抹掉原文, 只留"xxx撤回了一条消息"系统提示。
     故在 upsertPreviews(会话预览更新)里, 撤回发生【前】就把 {id, 内容} 缓存进
     localStorage.recalledMessageCacheList; 撤回系统提示渲染时按 id 取回, 拼到提示后面。
"""
import sys, re, shutil, subprocess
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
from asar import Asar

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path("/opt/bytedance/feishu")
WORK = Path("/tmp/feishu-patch-build")

# ---- 补丁 1: 已读→对方未读, 但【回复才已读】(复刻吾乐吧行为) ----
# 已读上报都汇到单一 sender(updateMessagesMeRead)。原来无条件 t.messageIds=[] => 全程未读。
# 改成门控: 仅在"刚发送"的 2.5s 窗口内放行, 否则清空。=> 纯浏览未读, 回复后可视消息已读。
# 注意: sender 在 SDK realm; 用 globalThis(worker 无 window, 用 window 会崩页面); 窗口须开在
# 与 sender 同 realm 的发送函数(sendMessage/sendMessageV2)里, onSendMessageSuccess 在 renderer
# realm globalThis 不共享(实测传不过去)。
UNREAD_PAT = re.compile(rb'\w+\.\w+\.info\("updateMessagesMeRead"')
UNREAD_INJECT = b"t.messageIds=(globalThis.__flReadWin&&Date.now()<globalThis.__flReadWin?t.messageIds:[]),"
# 发送时开放已读窗口(多 realm / 多形态)
SEND_PATS = [
    (re.compile(rb'(onSendMessageSuccess=e=>\{)(?!globalThis\.__flReadWin)'),
     rb'\g<1>globalThis.__flReadWin=Date.now()+2500;'),
    (re.compile(rb'(sendMessageV2:function\(e\)\{)(?!globalThis\.__flReadWin)'),
     rb'\g<1>globalThis.__flReadWin=Date.now()+2500;'),
    (re.compile(rb'(sendMessage:function\(e,t\)\{)(?!globalThis\.__flReadWin)'),
     rb'\g<1>globalThis.__flReadWin=Date.now()+2500;'),
    (re.compile(rb'sendMessage:([A-Z]),resendMessage'),
     rb'sendMessage:function(){globalThis.__flReadWin=Date.now()+2500;return \g<1>.apply(this,arguments)},resendMessage'),
    (re.compile(rb'sendMessageV2:([A-Z])\}\}'),
     rb'sendMessageV2:function(){globalThis.__flReadWin=Date.now()+2500;return \g<1>.apply(this,arguments)}}}'),
]

# ---- 补丁 2: 防撤回·渲染门控(撤回气泡占位置死, 可能已被新版结构取代, 命中与否都不阻断) ----
RECALL_MAIN = re.compile(rb'if\([a-z_$]&&![a-z_$]&&!\(0,[a-z_$]\.[a-z_$]\)\([A-Za-z_$]\)\)return this\.renderRecalledMessage\(\)')
RECALL_THREAD = re.compile(rb'([a-z]\.recallType)!==([a-z]\.dls\.NOT_RECALL)(\?\(0,[a-z]\.jsx\)\("span",\{className:"recall")')

# ---- 补丁 3: 防撤回·内容缓存(核心) ----
# 3a. upsertPreviews 里缓存预览内容到 localStorage(撤回前)。捕获预览循环变量名。
CACHE_ANCHOR = re.compile(
    rb'(upsertPreviews:\([a-z],[a-z]\)=>\{let\{previews:[a-z],traceId:[a-z]=""\}=[a-z]\.payload;[a-z]\.forEach\(([a-z])=>\{)')

def cache_block(pv: bytes) -> bytes:
    v = pv.decode()
    js = (
        'if(%s&&%s.localizedDigestMessage&&%s.localizedDigestMessage.length>0){try{'
        'var __wid=%s.lastVisibleMessageId,__we=%s.digest&&%s.digest.elements?%s.digest.elements:null;'
        'if(__wid&&__we){var __wm={id:__wid,type:%s.lastMessageType,content:{richText:{elements:{}}}};'
        'for(var __i=0;__i<__we.length;__i++){var __el=__we[__i],__pr={};'
        'if(__el.emoji){__pr.emotion={key:__el.emoji.emojiKey}}else if(__el.text){__pr.text=__el.text}'
        '__wm.content.richText.elements[__i]={tag:__el.tag,property:__pr}}'
        'var __S=localStorage.getItem("recalledMessageCacheList"),__L=__S&&__S!=="null"?JSON.parse(__S):[];'
        'if(!__L.find(function(x){return x.id==__wid})){__L.push(__wm);if(__L.length>3000){__L=__L.slice(-3000)}'
        'try{localStorage.setItem("recalledMessageCacheList",JSON.stringify(__L))}catch(e){localStorage.removeItem("recalledMessageCacheList")}}}}catch(e){}}'
        % ((v,)*8)
    )
    return js.encode()

# 3b. 撤回系统提示渲染: content:__Text("ews",{name:"<name />"}) 后拼上取回的原文。捕获消息变量名(E)。
RECALL_HINT = re.compile(
    rb'(&&([A-Za-z0-9_$]+)\.isReeditable,\(0,[a-z]\.jsxs\)\("div",\{className:"system-text",'
    rb'children:\[[a-z]\?__Text\("\w+"\):\(0,[a-z]\.jsx\)\([a-z]\.[A-Za-z],'
    rb'\{content:__Text\("\w+",\{name:"<name />"\}\))')

# 3c. 全局取回函数(注入到撤回提示所在文件头部, 只注一次)。
RECALL_HELPER = (
    b'window.__flRecallText=function(id){try{var S=localStorage.getItem("recalledMessageCacheList");'
    b'if(!S||S==="null")return"";var L=JSON.parse(S),h=L.find(function(x){return x.id==id});'
    b'if(!h||!h.content||!h.content.richText)return"";var els=h.content.richText.elements||{},out=[];'
    b'for(var k in els){var el=els[k],p=el.property||{};'
    b'if(p.text&&p.text.content){out.push(p.text.content)}else if(p.emotion){out.push("[\xe8\xa1\xa8\xe6\x83\x85]")}'
    b'else if(p.image){out.push("[\xe5\x9b\xbe\xe7\x89\x87]")}}'
    b'var s=out.join(" ");return s?": "+s:""}catch(e){return""}};'
)

def find_asar():
    for name in ("webcontent/messenger-next.asar", "webcontent/messenger.asar"):
        p = next(ROOT.rglob(name), None)
        if p:
            return p
    return None

def main():
    asar = find_asar()
    if not asar:
        sys.exit(f"未找到 messenger.asar（{ROOT}）")
    bak = asar.with_suffix(".asar.bak")

    # 纯净基线判定(关键, 兼容"飞书更新覆盖了 asar"的场景):
    #   - 若当前 asar 已含补丁标记 -> 它是被我们打过的, .bak 才是对应的干净基线 -> 用 .bak。
    #   - 若当前 asar 无标记 -> 它是干净版(首次 或 飞书刚更新覆盖) -> 它就是新基线, 刷新 .bak。
    PATCH_MARK = b'recalledMessageCacheList'
    patched = PATCH_MARK in asar.read_bytes()
    if patched:
        if not bak.exists():
            sys.exit(f"❌ 当前 asar 已被打补丁但缺少 .bak 干净基线，无法安全重打。\n"
                     f"   请先卸载重装飞书得到干净 asar，或恢复一个干净的 {bak}。")
        src = bak
        print(f"当前 asar 已含补丁，以 .bak 为纯净基线重打")
    else:
        print(f"当前 asar 为干净版（首次或飞书更新后），刷新纯净基线: {asar} -> {bak}")
        shutil.copy2(asar, bak)
        src = bak

    if WORK.exists():
        shutil.rmtree(WORK)
    with Asar.open(src) as a:
        a.extract(WORK)

    n_unread = n_send = n_gate = n_cache_w = n_cache_r = 0
    for js in WORK.rglob("*.js"):
        try:
            b = js.read_bytes()
        except Exception:
            continue
        orig = b

        # 2) 渲染门控(命中即打, 不命中不报错)
        b, c1 = RECALL_MAIN.subn(rb'if(!1)return this.renderRecalledMessage()', b)
        b, c2 = RECALL_THREAD.subn(rb'\g<1>!==\g<2>&&!1\g<3>', b)
        n_gate += c1 + c2

        # 3a) upsertPreviews 缓存写入
        def _ins_cache(m):
            return m.group(1) + cache_block(m.group(2))
        if CACHE_ANCHOR.search(b) and b'recalledMessageCacheList' not in b[:0] and b'__wm.content.richText' not in b:
            b, cw = CACHE_ANCHOR.subn(_ins_cache, b)
            n_cache_w += cw

        # 3b) 撤回提示拼接原文 + 3c) 注入取回函数到文件头
        if RECALL_HINT.search(b):
            b, cr = RECALL_HINT.subn(rb'\g<1>+window.__flRecallText(\g<2>.id)', b)
            n_cache_r += cr
            if cr and b'window.__flRecallText=' not in orig:
                b = RECALL_HELPER + b

        # 1) 已读门控(回复才放行, 首个命中点前插入, 幂等)
        m = UNREAD_PAT.search(b)
        if m and b[max(0, m.start()-len(UNREAD_INJECT)):m.start()] != UNREAD_INJECT:
            b = b[:m.start()] + UNREAD_INJECT + b[m.start():]
            n_unread += 1

        # 1b) 发送开窗(多 realm / 多形态)
        for pat, rep in SEND_PATS:
            b, cs = pat.subn(rep, b)
            n_send += cs

        if b != orig:
            js.write_bytes(b)

    print(f"[已读门控·回复放行] 注入点: {n_unread}")
    print(f"[发送开窗]          注入点: {n_send}")
    print(f"[防撤回·门控]       中和:   {n_gate}  (新版可能为0, 不影响)")
    print(f"[防撤回·缓存写]     注入:   {n_cache_w}")
    print(f"[防撤回·缓存读]     拼接:   {n_cache_r}")

    # 核心是缓存法(写+读都要命中); 已读门控 + 发送开窗也必须命中
    if n_unread == 0 or n_send == 0 or n_cache_w == 0 or n_cache_r == 0:
        sys.exit("❌ 关键补丁点未命中，疑似版本不兼容，已中止（未改动原文件）")

    Asar.pack(WORK, str(asar))
    shutil.rmtree(WORK)
    subprocess.run(["chown", "root:root", str(asar)], check=False)
    asar.chmod(0o644)
    print(f"✅ 已写入 {asar}\n请彻底退出飞书后重启：pkill -9 -x feishu ; /opt/bytedance/feishu/feishu &")

if __name__ == "__main__":
    main()
