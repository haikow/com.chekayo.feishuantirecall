#!/usr/bin/env python3
# spawn_drive.py v3 — 简单 spawn + 自动重试直到落进【主进程】(hunt.js 报告 hooked=liblark 在场).
# spawn-gating 绕过反调试(挂起在 fork 点注入); 落进 sandboxed/非主进程则杀掉重试.
# 用法: python3 spawn_drive.py <script.js>
import frida, sys, time, datetime

jsfile = sys.argv[1] if len(sys.argv) > 1 else 'hunt.js'
PKG = "com.ss.android.lark"
log = open('spawn_drive.log', 'w', encoding='utf-8', buffering=1)
def ts(): return datetime.datetime.now().strftime('%H:%M:%S')
def emit(s):
    line = '[%s] %s' % (ts(), s); print(line, flush=True); log.write(line + '\n')

dev = frida.get_device_manager().add_remote_device("127.0.0.1:8899")
code = open(jsfile, encoding='utf-8').read()

state = {'hooked': False, 'sess': None, 'pid': None}
def on_message(m, data):
    if m.get('type') == 'send':
        p = m.get('payload')
        emit('MSG ' + str(p))
        if isinstance(p, dict) and p.get('ev') == 'hooked':
            state['hooked'] = True
    else:
        emit('ERR ' + str(m))

for attempt in range(1, 26):
    pid = dev.spawn([PKG])
    emit('attempt %d: spawned pid=%d' % (attempt, pid))
    sess = dev.attach(pid)
    sc = sess.create_script(code)
    sc.on('message', on_message)
    sc.load()
    dev.resume(pid)
    state['pid'] = pid; state['sess'] = sess
    # wait up to 9s for liblark hook
    ok = False
    for _ in range(18):
        time.sleep(0.5)
        if state['hooked']: ok = True; break
    if ok:
        emit('>>> HOOKED main process pid=%d, holding session <<<' % pid)
        break
    emit('attempt %d: no liblark (wrong proc), killing pid=%d & retry' % (attempt, pid))
    try: sc.unload()
    except: pass
    try: sess.detach()
    except: pass
    try: dev.kill(pid)
    except: pass
    state['hooked'] = False
    time.sleep(1)

if not state['hooked']:
    emit('FAILED to land in main after retries'); sys.exit(1)
while True:
    time.sleep(1)
