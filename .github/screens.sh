#!/usr/bin/env bash
# Emulator test of the music player's lights and Up next (run 4: beams, ribbons, bokeh and grain), from the real app: the player (lights around the artwork),
# the Up next bar, the queue panel, a song picked from it, and the panel swiped down. Each step is a screenshot in the
# log (base64 JPEG between ==IMG name== and ==END==, also kept in ./screens).
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
# Home: the top bar tucks away while scrolling down
adb shell input swipe 540 1800 540 900 400
sleep 2
shot h1_home_scrolled
adb shell input swipe 540 900 540 1300 300
sleep 2
shot h2_home_back_up
tap 'content-desc="Search YouTube"'
sleep 4
tap 'content-desc="Music search"'
sleep 1
adb shell input text "perfect%sed%ssheeran"
adb shell input keyevent 66
sleep 15
adb shell input tap 540 700
sleep 40
shot m1_player
# Play (the emulator starts it paused): the lights move
adb shell input tap 540 1936
sleep 6
shot m2_player_later
echo "Up next bar: $(bounds 'text="Up next"')"
tap 'text="Up next"' || tap 'text="UP NEXT"' || { adb shell input swipe 540 1800 540 1200 300; sleep 2; tap 'text="Up next"'; }
sleep 3
shot m3_queue
# The third row: the second song after this one
ROW=$(dump | grep -E 'class="android.widget.LinearLayout"' | grep -E 'clickable="true"' | sed -n 4p | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' | awk 'NF==4 { printf "%d %d", ($1+$3)/2, ($2+$4)/2 }')
echo "row: $ROW"
[ -n "$ROW" ] && adb shell input tap $ROW
sleep 30
shot m4_picked
tap 'text="Up next"' || tap 'text="UP NEXT"'
sleep 3
shot m5_queue_again
adb shell input swipe 540 900 540 2200 250
sleep 2
shot m6_swiped_down

adb logcat -d | grep -E "AndroidRuntime|FATAL|PhoneMusic|PhoneLyrics" | tail -60
