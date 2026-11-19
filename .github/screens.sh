#!/usr/bin/env bash
# Emulator test of the music player's Shuffle and Repeat buttons (issue #61), from the real app (run 2: Next with the queue):
# screenshot in the log (base64 JPEG between ==IMG name== and ==END==, also kept in ./screens), and the app's own
# log lines (PhoneMusic) say what the buttons did.
set -x
PKG=org.smarttube.phone
SPLASH=com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity
OUT=screens
mkdir -p $OUT

adb install -r -g app.apk
adb shell settings put global hide_error_dialogs 1
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher
adb shell cmd uimode night yes

dump() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml | tr '>' '\n'; }
center() {
  dump | grep -E "$1" | head -1 \
    | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' \
    | awk 'NF==4 { printf "%d %d", ($1+$3)/2, ($2+$4)/2 }'
}
bounds() { dump | grep -E "$1" | head -1 | sed -E 's/.*bounds="(\[[0-9,]+\]\[[0-9,]+\])".*/\1/'; }
tap() { local xy; xy=$(center "$1"); echo "tap '$1' at: $xy"; if [ -n "$xy" ]; then adb shell input tap $xy; return 0; fi; return 1; }
shot() { adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; sleep 1; adb exec-out screencap -p > "$OUT/$1.png"; echo "==IMG $1=="; convert "$OUT/$1.png" -resize 540x -quality 82 jpg:- | base64 -w0; echo; echo "==END=="; }
log() { echo "---- $1"; adb logcat -d -s PhoneMusic:D PhoneLyrics:D | grep -E "mode:|shuffle|repeat|song:" | tail -8; }

adb logcat -c
adb shell am start -n "$PKG/$SPLASH"
sleep 45
tap 'content-desc="Search YouTube"'
sleep 4
tap 'content-desc="Music search"'
sleep 1
adb shell input text "perfect%sed%ssheeran"
adb shell input keyevent 66
sleep 15
adb shell input tap 540 700
sleep 40
echo "Shuffle button: $(bounds 'content-desc="Shuffle"')   Repeat button: $(bounds 'content-desc="Repeat this song"')"
shot t1_player

# 1. Shuffle on: lit, and the next song picked at random
tap 'content-desc="Shuffle"'
sleep 3
shot t2_shuffle_on
log "after Shuffle"

# 2. Next: the picked song opens
adb shell input tap 750 1936
sleep 30
shot t3_after_next
log "after Next"

# 3. Shuffle off, Repeat on
tap 'content-desc="Shuffle"'
sleep 2
tap 'content-desc="Repeat this song"'
sleep 3
shot t4_repeat_on
log "after Repeat"

# 4. Play, then jump close to the end: with Repeat on the song starts over
adb shell input tap 540 1936
sleep 10
SEEK=$(bounds 'class="android.widget.SeekBar"')
echo "seek bar: $SEEK"
if [ -n "$SEEK" ]; then
  read L T R B < <(echo "$SEEK" | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/')
  adb shell input tap $(( L + (R - L) * 985 / 1000 )) $(( (T + B) / 2 ))
fi
sleep 3
shot t5_near_end
sleep 30
shot t6_after_end
log "after the end"

adb logcat -d | grep -E "AndroidRuntime|FATAL|PhoneMusic|PhonePlayback.*(state|ended)" | tail -60
