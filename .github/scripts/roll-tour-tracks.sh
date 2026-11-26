#!/bin/bash
adb install -r roll/build/outputs/apk/debug/roll-debug.apk
adb push a.mp4 b.mp4 c.jpg /data/local/tmp/
adb shell run-as com.davacada.roll mkdir -p files/selftest
for f in a.mp4 b.mp4 c.jpg; do adb shell run-as com.davacada.roll cp /data/local/tmp/$f files/selftest/$f; done
adb logcat -c
adb shell am start -n com.davacada.roll/.RollActivity --es selftest tour-tracks
n=0
for i in $(seq 1 300); do
  sleep 1
  adb logcat -d -s ROLLTEST:I > rt.txt
  c=$(grep -c "shot " rt.txt || true)
  while [ $n -lt $c ]; do adb exec-out screencap -p > tour$n.png; n=$((n+1)); done
  if grep -q "tour done\|failed:" rt.txt; then break; fi
done
adb shell input keyevent 4; sleep 2
adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1; sleep 6
adb exec-out screencap -p > tourland.png
cat rt.txt
for f in tour*.png; do ffmpeg -loglevel error -i $f -vf "scale='if(gt(iw,ih),900,-2)':'if(gt(iw,ih),-2,900)'" -y s_$f; echo "BEGIN-$f"; base64 -w0 s_$f; echo; echo "END-$f"; done
grep -n -A20 "FATAL EXCEPTION" <(adb logcat -d) | head -40 || true
