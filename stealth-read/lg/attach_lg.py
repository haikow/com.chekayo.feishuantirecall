#!/usr/bin/env python3
# attach_lg.py <script.js> [hold_s] — stealth attach to lark main on LG via dmtkd5:8899 (frida 17.15.3 client).
import frida, sys, time, datetime, subprocess
DEVICE = "YOUR_DEVICE_SERIAL"
jsfile = sys.argv[1] if len(sys.argv) > 1 else 'probe.js'
HOLD = int(sys.argv[2]) if len(sys.argv) > 2 else 3600
def ts(): return datetime.datetime.now().strftime('%H:%M:%S')
def emit(s): print('[%s] %s' % (ts(), s), flush=True)
def on_message(m, data):
    if m.get('type') == 'send': emit('MSG ' + str(m.get('payload')))
    else: emit('ERR ' + str(m))
def lark_pid():
    out = subprocess.check_output(['adb','-s',DEVICE,'shell','pgrep','-f','com.ss.android.lark$']).decode().split()
    return int(out[0]) if out else None
def one():
    subprocess.run(['adb','-s',DEVICE,'forward','tcp:8899','tcp:8899'],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    d = frida.get_device_manager().add_remote_device("127.0.0.1:8899")
    pid = lark_pid()
    if not pid: raise RuntimeError("lark not running")
    s = d.attach(pid)
    sc = s.create_script(open(jsfile, encoding='utf-8').read())
    sc.on('message', on_message); sc.load()
    def on_det(reason,*a): emit('!! DETACHED %s' % reason)
    s.on('detached', on_det)
    emit('ATTACHED+loaded on lark pid=%d' % pid)
    return s
for i in range(1, 26):
    try:
        s = one()
        t0=time.time()
        while time.time()-t0 < HOLD:
            time.sleep(1)
        emit('hold done'); break
    except Exception as e:
        emit('attempt %d: %s' % (i, str(e)[:70])); time.sleep(1.0)
else:
    emit('FAILED 25 attempts')
