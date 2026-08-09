import frida, sys, time, json, datetime

pid = int(sys.argv[1])
jsfile = sys.argv[2] if len(sys.argv) > 2 else 'read_obs.js'
log = open('read_obs.log', 'w', encoding='utf-8', buffering=1)

def ts():
    return datetime.datetime.now().strftime('%H:%M:%S')

def on_message(message, data):
    p = message.get('payload')
    if isinstance(p, dict):
        ev = p.get('ev', '?')
        if ev == 'hit':
            line = '[%s] ★HIT ssl_write(%s) n=%d' % (ts(), p.get('mod'), p.get('n'))
            print(line, flush=True); log.write(line + '\n')
            pv = '   preview: ' + p.get('preview', '')
            print(pv, flush=True); log.write(pv + '\n')
            for f in p.get('bt', []):
                print('     ' + f, flush=True); log.write('     ' + f + '\n')
        elif ev == 'beat':
            line = '[%s] beat %s' % (ts(), json.dumps(p.get('stats'), ensure_ascii=False))
            print(line, flush=True); log.write(line + '\n')
        else:
            line = '[%s] %s' % (ts(), json.dumps(p, ensure_ascii=False))
            print(line, flush=True); log.write(line + '\n')
    else:
        line = '[%s] msg %s' % (ts(), message)
        print(line, flush=True); log.write(line + '\n')

dev = frida.get_usb_device(timeout=10)
session = dev.attach(pid)
code = open(jsfile, encoding='utf-8').read()
script = session.create_script(code)
script.on('message', on_message)
script.load()
log.write('attached pid=%d, hooks loaded; waiting for read trigger on phone...\n' % pid)
print('attached pid=%d; waiting for read trigger...' % pid, flush=True)

while True:
    time.sleep(1)
