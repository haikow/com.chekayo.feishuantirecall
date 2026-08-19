#!/usr/bin/env python3
# attach_retry.py <script.js> — retry attach to lark main via florida:8899 until a script signal appears.
import frida, sys, time, datetime, subprocess
DEVICE = "YOUR_DEVICE_SERIAL"
jsfile = sys.argv[1] if len(sys.argv) > 1 else 'probe.js'
HOLD = int(sys.argv[2]) if len(sys.argv) > 2 else 8
def ts(): return datetime.datetime.now().strftime('%H:%M:%S')
def emit(s): print('[%s] %s' % (ts(), s), flush=True)
def on_message(m, data):
    if m.get('type') == 'send': emit('MSG ' + str(m.get('payload')))
    else: emit('ERR ' + str(m))
def one():
    subprocess.run(['adb','-s',DEVICE,'forward','tcp:8899','tcp:8899'],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    d = frida.get_device_manager().add_remote_device("127.0.0.1:8899")
    out = subprocess.check_output(['adb','-s',DEVICE,'shell','pgrep','-f','com.ss.android.lark$']).decode().split()
    pid = int(out[0])
    s = d.attach(pid)
    sc = s.create_script(open(jsfile, encoding='utf-8').read())
    sc.on('message', on_message); sc.load()
    emit('ATTACHED+loaded on pid %d; holding %ds' % (pid, HOLD))
    return s
for i in range(1, 21):
    try:
        s = one()
        t0 = time.time()
        while time.time() - t0 < HOLD:
            time.sleep(1)
        emit('hold complete on attempt %d' % i)
        # keep alive for interactive testing
        while True: time.sleep(1)
    except Exception as e:
        emit('attempt %d failed: %s' % (i, str(e)[:70]))
        time.sleep(1.2)
emit('FAILED after 20 attempts')
