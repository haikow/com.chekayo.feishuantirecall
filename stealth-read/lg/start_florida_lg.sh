#!/bin/bash
# start_florida_lg.sh — bring up stealth frida (17.15.3) on the LG G8 and forward :8899.
# LG G8 Android 11 linker needs frida>=17.15.x (17.6.2 aborts "Unsupported Android linker").
# Stealth = external names destrung + gum-js-loop thread renamed (see ~/florida-mod/patch_stealth.py).
DEV=YOUR_DEVICE_SERIAL
BIN=/data/local/tmp/dmtkd5
adb -s $DEV shell "su -c 'setenforce 0'"
adb -s $DEV shell "su -c 'pkill -9 -f dmtkd5; pkill -9 -f helper'" 2>/dev/null
# push if missing
adb -s $DEV shell "su -c '[ -f $BIN ]'" || adb -s $DEV push ~/florida-mod/dmtkd5-17.15.3-stealth $BIN
adb -s $DEV shell "su -c 'chmod 755 $BIN'"
adb -s $DEV shell "su -c 'setsid $BIN -l 0.0.0.0:8899 >/dev/null 2>&1 &'"
sleep 2
adb -s $DEV forward tcp:8899 tcp:8899
echo -n "server pid: "; adb -s $DEV shell "su -c 'pgrep -f \"dmtkd5 -l\" | head -1'"
echo -n "stealth (ps grep frida, want empty): "; adb -s $DEV shell "su -c 'ps -A | grep -i frida | grep -v grep'"
echo "client: /tmp/frida17153/bin/python  (frida 17.15.3)"
echo "attach: cd ~/xposed-antirecall/stealth-read/lg && /tmp/frida17153/bin/python attach_lg.py <script.js>"
