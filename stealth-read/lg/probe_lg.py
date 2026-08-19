#!/usr/bin/env python3
# probe_lg.py — attach to lark main on LG via florida:8899, confirm liblark + sqlite hookable.
import frida, sys, time, datetime, subprocess
DEVICE = "YOUR_DEVICE_SERIAL"
def ts(): return datetime.datetime.now().strftime('%H:%M:%S')
def emit(s): print('[%s] %s' % (ts(), s), flush=True)
def on_message(m, data):
    if m.get('type') == 'send': emit('MSG ' + str(m.get('payload')))
    else: emit('ERR ' + str(m))
d = frida.get_device_manager().add_remote_device("127.0.0.1:8899")
emit('connected, procs=%d' % len(d.enumerate_processes()))
out = subprocess.check_output(['adb','-s',DEVICE,'shell','pgrep','-f','com.ss.android.lark$']).decode().split()
pid = int(out[0]); emit('lark main pid=%d, attaching...' % pid)
s = d.attach(pid); emit('ATTACHED')
js = '''
function whenLark(cb){var m=Process.findModuleByName('liblark.so');if(m){cb(m);return}var t=setInterval(function(){var mm=Process.findModuleByName('liblark.so');if(mm){clearInterval(t);cb(mm)}},50)}
whenLark(function(l){
  send({ev:'liblark', base:l.base.toString(), size:l.size});
  var sc=Process.findModuleByName('libsqlcipher.so');
  send({ev:'sqlcipher', found: !!sc});
  if(sc){var step=null;try{step=sc.getExportByName('sqlite3_step')}catch(e){}
    send({ev:'sqlite3_step', addr: step?step.toString():null});
  }
});
'''
sc = s.create_script(js); sc.on('message', on_message); sc.load()
emit('loaded probe; holding 8s')
time.sleep(8)
emit('done')
