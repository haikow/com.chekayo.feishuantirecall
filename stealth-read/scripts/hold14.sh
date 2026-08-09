#!/bin/bash
# 重试直到 hunt14 armed, 然后 hold 住会话(florida 每次重起对抗 wedge/flaky attach).
cd ~/xposed-antirecall/stealth-read/scripts
DEV=YOUR_DEVICE_SERIAL
for i in $(seq 1 15); do
  adb -s $DEV shell 'su -c "pkill -9 -f dmtkd3"' 2>/dev/null; sleep 1
  adb -s $DEV shell 'su -c "/data/local/tmp/dmtkd3 -l 0.0.0.0:8899 >/dev/null 2>&1 &"' 2>/dev/null; sleep 2
  adb -s $DEV forward tcp:8899 tcp:8899 >/dev/null 2>&1
  /tmp/frida1762/bin/python attach_drive.py hunt14.js >/tmp/h14hold.log 2>&1 &
  P=$!
  sleep 11
  if grep -q content_drop_armed /tmp/h14hold.log; then
    echo "ARMED on attempt $i (pid $P) — holding"
    wait $P
    exit 0
  fi
  echo "attempt $i failed: $(tail -1 /tmp/h14hold.log | grep -oE 'TransportError.*|TimedOut.*|ServerNot.*' | cut -c1-40)"
  kill -9 $P 2>/dev/null
  pkill -9 -f "bin/python attach_drive" 2>/dev/null
  sleep 1
done
echo "FAILED after 15 attempts"
