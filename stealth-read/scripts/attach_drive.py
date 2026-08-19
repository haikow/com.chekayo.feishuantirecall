#!/usr/bin/env python3
# attach_drive.py — florida(隐身 server)+ ATTACH 到运行中的 lark 主进程(不用 spawn, 躲过 method-slot bug + husk).
# 前置: florida-server(dmtkd3)在 8899 held-open; adb forward tcp:8899; 客户端 frida==17.6.2.
# 用法: /tmp/frida1762/bin/python attach_drive.py <script.js>
import frida, sys, time, datetime, subprocess

jsfile = sys.argv[1] if len(sys.argv) > 1 else 'hunt.js'
PKG = "com.ss.android.lark"; DEVICE = "YOUR_DEVICE_SERIAL"
log = open('attach_drive.log', 'w', encoding='utf-8', buffering=1)
def ts(): return datetime.datetime.now().strftime('%H:%M:%S')
def emit(s):
    line = '[%s] %s' % (ts(), s); print(line, flush=True); log.write(line + '\n')

def on_message(m, data):
    if m.get('type') == 'send': emit('MSG ' + str(m.get('payload')))
    else: emit('ERR ' + str(m))

d = frida.get_device_manager().add_remote_device("127.0.0.1:8899")
emit('connected, procs=%d' % len(d.enumerate_processes()))
# find main pid (exact com.ss.android.lark)
out = subprocess.check_output(['adb', '-s', DEVICE, 'shell', 'pgrep', '-f', 'com.ss.android.lark$']).decode().split()
pid = int(out[0])
emit('lark main pid=%d, attaching...' % pid)
s = d.attach(pid)
sc = s.create_script(open(jsfile, encoding='utf-8').read())
sc.on('message', on_message)
sc.load()
emit('ATTACHED+loaded %s on main %d; holding...' % (jsfile, pid))
def on_detached(reason, *a): emit('!! DETACHED reason=%s' % reason)
s.on('detached', on_detached)
while True:
    time.sleep(1)
