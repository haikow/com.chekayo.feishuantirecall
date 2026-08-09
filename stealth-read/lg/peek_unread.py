#!/usr/bin/env python3
# peek_unread.py — 悄悄看飞书未读消息(不点开聊天=不触发已读). 从进程内已解密的 messages 库直接读.
# 依赖: LG 上 florida 隐身 server(dmtkd5:8899)在跑 + adb forward tcp:8899. 见 start_florida_lg.sh.
# 用法: /tmp/frida17153/bin/python peek_unread.py
import frida, sys, subprocess, time, datetime

DEVICE = "YOUR_DEVICE_SERIAL"

def decode_text(hexs):
    """从 content protobuf 抠正文: 收集所有 0a<len><utf8> 段, 取最长的可打印串."""
    try: b = bytes.fromhex(hexs)
    except Exception: return None
    cands = []
    i = 0
    while i < len(b):
        if b[i] == 0x0a and i+1 < len(b):
            ln = b[i+1]
            if i+2+ln <= len(b):
                seg = b[i+2:i+2+ln]
                try:
                    t = seg.decode('utf-8')
                    if t and all(ord(c) >= 0x20 or c in '\n\t' for c in t):
                        cands.append(t)
                except Exception: pass
        i += 1
    if not cands: return None
    cands.sort(key=len)
    best = cands[-1]
    return best if len(best) >= 1 and best not in ('1',) else (best if len(best) > 1 else None)

def ts(u):
    try: return datetime.datetime.fromtimestamp(int(u)).strftime('%m-%d %H:%M')
    except Exception: return str(u)

def attempt(jssrc, result, hold=10):
    """一次 attach 尝试; 拿到 DUMP 即算成功, 顺带尽量等 NAMES."""
    subprocess.run(['adb','-s',DEVICE,'forward','tcp:8899','tcp:8899'],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    d = frida.get_device_manager().add_remote_device("127.0.0.1:8899")
    pid = int(subprocess.check_output(['adb','-s',DEVICE,'shell','pgrep','-f','com.ss.android.lark$']).decode().split()[0])
    s = d.attach(pid)
    def on_msg(m, data):
        if m.get('type') != 'send': return
        p = m['payload']; ev = p.get('ev')
        if ev == 'DUMP': result['dump'] = p
        elif ev == 'NAMES': result.setdefault('names',{}).update(p.get('names',{}))
    sc = s.create_script(jssrc); sc.on('message', on_msg); sc.load()
    t0 = time.time(); tdump = None
    while time.time()-t0 < hold:
        if 'dump' in result and tdump is None: tdump = time.time()
        if 'dump' in result and 'names' in result: break
        if tdump and time.time()-tdump > 8: break   # 拿到内容后, 名字最多等 5s
        time.sleep(0.3)
    try: s.detach()
    except Exception: pass
    return 'dump' in result

def main():
    jssrc = open('peek_unread.js', encoding='utf-8').read()
    result = {}
    for i in range(15):
        try:
            if attempt(jssrc, result): break
        except Exception as e:
            time.sleep(0.8)
    if 'dump' not in result:
        print("没抓到(重试多次仍失败). 确认飞书在运行 + florida server 在跑(start_florida_lg.sh)."); return
    dp = result['dump']
    rows = dp['rows']; cn = dp.get('chatNames',{}); sn = result.get('names',{})
    # 组装: 按 chat 分组
    groups = {}
    for r in rows:
        txt = decode_text(r['chex'])
        if txt is None:
            txt = '[非文本消息 type=%s]' % r['type']
        groups.setdefault(r['chat'], []).append((r['ctime'], sn.get(r['from'], r['from']), txt))
    print("\n===== 飞书未读消息(未触发已读) — 共 %d 条 =====" % len(rows))
    for chat, msgs in groups.items():
        title = cn.get(chat, '聊天'+chat)
        print("\n【%s】" % title)
        for ct, who, txt in sorted(msgs):
            print("  %s  %s: %s" % (ts(ct), who, txt))
    print()

if __name__ == '__main__':
    main()
