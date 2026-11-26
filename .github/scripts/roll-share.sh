#!/bin/bash
# Shares a video to Roll the way the gallery would, then picks "New project".
adb install -r roll/build/outputs/apk/debug/roll-debug.apk
adb push a.mp4 /sdcard/Movies/share-test.mp4
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Movies/share-test.mp4
sleep 4
ID=$(adb shell content query --uri content://media/external/video/media --projection _id:_display_name | grep share-test | sed 's/.*_id=\([0-9]*\).*/\1/' | head -1)
echo "media id: $ID"
adb logcat -c
adb shell am start -a android.intent.action.SEND -t video/mp4 --eu android.intent.extra.STREAM content://media/external/video/media/$ID --grant-read-uri-permission -n com.davacada.roll/.RollActivity
sleep 8
adb exec-out screencap -p > share0.png
adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml ui.xml >/dev/null
B=$(grep -o 'text="New project"[^>]*bounds="[^"]*"' ui.xml | grep -o 'bounds="[^"]*"' | head -1)
echo "button $B"
read X1 Y1 X2 Y2 <<< $(echo $B | grep -o '[0-9]\+' | tr '\n' ' ')
[ -n "$X1" ] && adb shell input tap $(( (X1+X2)/2 )) $(( (Y1+Y2)/2 ))
sleep 10
adb exec-out screencap -p > share1.png
for f in share*.png; do ffmpeg -loglevel error -i $f -vf "scale=-2:900" -y s_$f; echo "BEGIN-$f"; base64 -w0 s_$f; echo; echo "END-$f"; done
grep -n -A20 "FATAL EXCEPTION" <(adb logcat -d) | head -40 || true
