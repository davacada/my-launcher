#!/bin/bash
set -x
adb install -r roll/build/outputs/apk/debug/roll-debug.apk
adb push a.mp4 b.mp4 c.jpg /data/local/tmp/
adb shell run-as com.davacada.roll mkdir -p files/selftest
for f in a.mp4 b.mp4 c.jpg; do adb shell run-as com.davacada.roll cp /data/local/tmp/$f files/selftest/$f; done
adb logcat -c
adb shell am start -n com.davacada.roll/.RollActivity --es selftest 1
set +x
n=0
for i in $(seq 1 900); do
  sleep 1
  adb logcat -d -s ROLLTEST:I > rt.txt
  c=$(grep -c "shot " rt.txt || true)
  while [ $n -lt $c ]; do sleep 1.2; adb exec-out screencap -p > shot$n.png; n=$((n+1)); done
  if grep -q "export ok\|export failed\|failed:" rt.txt; then break; fi
done
adb exec-out screencap -p > shotend.png
# sideways: back to the editor, turn the phone
adb shell input keyevent 4; sleep 2
adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1; sleep 6
adb exec-out screencap -p > shotland.png
adb shell settings put system user_rotation 0; sleep 3
cat rt.txt
adb logcat -d > logcat.txt || true
P=$(grep -o "export ok [^ ]*" rt.txt | awk '{print $3}')
if [ -n "$P" ]; then adb shell run-as com.davacada.roll cat "$P" > out.mp4; fi
ls -la out.mp4 || true
for f in shot*.png; do echo "BEGIN-$f"; base64 -w0 $f; echo; echo "END-$f"; done
if [ -s out.mp4 ]; then
  ffprobe -v error -show_entries stream=codec_type,width,height,duration,sample_rate,channels -of compact out.mp4
  ffmpeg -v error -i out.mp4 -af volumedetect -f null - 2>&1 | grep -i "mean_volume\|max_volume" || true
  for t in 1.5 3.5 4.0 4.5 6.0 7.2 8.5 12.0; do ffmpeg -loglevel error -ss $t -i out.mp4 -frames:v 1 -vf scale=240:-2 ex_$t.png; done
  ffmpeg -loglevel error -i ex_1.5.png -i ex_3.5.png -i ex_4.0.png -i ex_4.5.png -i ex_6.0.png -i ex_7.2.png -i ex_8.5.png -i ex_12.0.png -filter_complex hstack=inputs=8 sheet.png
  echo "BEGIN-sheet"; base64 -w0 sheet.png; echo; echo "END-sheet"
fi
grep -n -A30 "FATAL EXCEPTION" logcat.txt | head -60 || true
grep -n "ROLLTEST\|ExportException\|VideoFrameProcessing\|GlUtil\|shader" logcat.txt | head -80 || true
