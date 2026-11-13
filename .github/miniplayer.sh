#!/usr/bin/env bash
# Mini player check: play from Home, drag the video down, the card lands bottom right with the video playing; open
# it again, Back into the card, move it, swipe it down to close it. Frames around each step are printed to the log (base64 JPEG between
# ==IMG name== and ==END== lines) along with the activity stack and crashes. Files also in ./smoke.
set -x
PKG=org.smarttube.phone
SPLASH=com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity
OUT=smoke
mkdir -p $OUT

adb install -r -g app.apk > $OUT/install.txt 2>&1
cat $OUT/install.txt
adb logcat -c
adb shell settings put global hide_error_dialogs 1
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher

tasks() { adb shell dumpsys activity activities | grep -E "^ +\* Task\{|^ +\* ActivityRecord\{|topResumedActivity" > "$OUT/tasks_$1.txt"; echo "== tasks $1"; cat "$OUT/tasks_$1.txt"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
# Centre of the view with this content description, from the UI dump
center_of() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep "content-desc=\"$1\"" | head -1 \
    | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' \
    | awk '{ printf "%d %d", ($1+$3)/2, ($2+$4)/2 }'
}
record() { adb shell screenrecord --bit-rate 8000000 --time-limit "$2" "/sdcard/$1.mp4" & }
pull_record() { sleep 1; adb pull "/sdcard/$1.mp4" "$OUT/$1.mp4" > /dev/null 2>&1; }

adb shell am start -n "$PKG/$SPLASH"
sleep 40

# 1. Play from Home
adb shell input tap 540 1000
sleep 20
shot 1_player
tasks 1_player

# 2. Drag the video down: it lands on the card, Home in front, the player still alive behind it
record 2_minimize 8
sleep 2
adb shell input swipe 540 350 540 1400 450
sleep 5
pull_record 2_minimize
shot 2_card
tasks 2_card
sleep 4
shot 2b_card_later

# 3. Tap the card: the player opens again
XY=$(center_of "Open the player")
echo "card at: $XY"
record 3_expand 6
sleep 2
if [ -n "$XY" ]; then adb shell input tap $XY; else adb shell input tap 800 1850; fi
sleep 4
pull_record 3_expand
shot 3_expanded
tasks 3_expanded

# 4. Back: into the card again
record 4_back 6
sleep 2
adb shell input keyevent KEYCODE_BACK
sleep 4
pull_record 4_back
shot 4_card_again
tasks 4_card_again

# 5. Drag the card to the left, then swipe it down off the screen: the video closes
XY=$(center_of "Open the player")
if [ -n "$XY" ]; then
  set -- $XY
  adb shell input swipe $1 $2 200 1200 400
  sleep 2
  shot 5_moved
fi
XY=$(center_of "Open the player")
echo "card before the swipe down: $XY"
if [ -n "$XY" ]; then
  set -- $XY
  record 6_swipe_down 5
  sleep 1
  adb shell input swipe $1 $2 $1 2350 250
  sleep 3
  pull_record 6_swipe_down
fi
shot 6_closed
tasks 6_closed
XY=$(center_of "Open the player")
echo "card after the swipe down: ${XY:-gone}"

# 7. You > Settings: the grouped settings list, top and bottom
center_of_text() {
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
  adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep " text=\"$1\"" | head -1 \
    | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' \
    | awk '{ printf "%d %d", ($1+$3)/2, ($2+$4)/2 }'
}
XY=$(center_of_text "You")
if [ -n "$XY" ]; then adb shell input tap $XY; else adb shell input tap 945 2280; fi
sleep 6
shot 7_you
# Settings: the gear at the top of the You page
XY=$(center_of "Settings")
echo "settings button at: $XY"
if [ -n "$XY" ]; then
  adb shell input tap $XY
  sleep 4
  shot 8_settings
  adb shell input swipe 540 1800 540 700 400
  sleep 2
  shot 9_settings_more
fi

# 7b. The Shorts tab: straight into a Short (the feed was loaded ahead of time), laid out like the YouTube app
XY=$(center_of_text "Home")
if [ -n "$XY" ]; then adb shell input tap $XY; fi
sleep 3
XY=$(center_of_text "Shorts")
echo "shorts tab at: $XY"
if [ -n "$XY" ]; then
  adb shell input tap $XY
  sleep 2
  shot 15_shorts_2s
  sleep 6
  shot 15b_shorts_8s
  tasks 15_shorts
  # Swipe up for the next Short: the title should change
  title() {
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1
    adb shell cat /sdcard/ui.xml | tr '>' '\n' | grep 'phone_shorts_title' | head -1 | sed -E 's/.* text="([^"]*)".*/\1/'
  }
  echo "short before swipe: $(title)"
  adb shell input swipe 540 1500 540 500 250
  sleep 4
  echo "short after swipe: $(title)"
  shot 15c_shorts_swiped
  adb shell input swipe 540 1500 540 500 250
  sleep 4
  echo "short after 2nd swipe: $(title)"
  XY=$(center_of_text "Home")
  echo "home tab on shorts at: $XY"
  if [ -n "$XY" ]; then adb shell input tap $XY; else adb shell input keyevent KEYCODE_BACK; fi
  sleep 3
  shot 16_after_shorts
fi

# 8. The Notifications tab: its own screen (signed out here: the hint to sign in).
# From Home: on Settings a row is called Notifications too, and the tab would not be the first match.
XY=$(center_of_text "Home")
if [ -n "$XY" ]; then adb shell input tap $XY; fi
sleep 3
XY=$(center_of_text "Notifications")
echo "notifications tab at: $XY"
if [ -n "$XY" ]; then
  adb shell input tap $XY
  sleep 4
  shot 10_notifications
  tasks 10_notifications
  adb shell input keyevent KEYCODE_BACK
  sleep 2
  shot 11_back_from_notifications
fi

# 9. Leave the app with the mini player playing: the mini player's video floats in Android's window.
# Last, because the app is in the background afterwards. Videos don't play here (YouTube blocks the emulator), so
# the card's play button is tapped to put the player into "playing" first.
# The launcher is switched off during the test; a home screen started fresh by Home doesn't let an app float
# (Android only allows it when switching to a task that already exists), so start it once now and come back.
adb shell pm enable com.google.android.apps.nexuslauncher > /dev/null 2>&1
sleep 3
adb shell input keyevent KEYCODE_HOME
sleep 8
adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n "$PKG/$SPLASH"
sleep 5
top() { adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity"; }
for i in 1 2 3 4; do
  top | grep -q PhoneBrowseActivity && break
  adb shell input keyevent KEYCODE_BACK; sleep 2
done
echo "top before video: $(top)"
XY=$(center_of_text "Home")
if [ -n "$XY" ]; then adb shell input tap $XY; fi
sleep 3
for Y in 1000 800 1200; do
  adb shell input tap 540 $Y
  sleep 15
  top | grep -q PhonePlaybackActivity && break
  adb shell input keyevent KEYCODE_BACK; sleep 3
done
echo "top with video: $(top)"
adb shell input swipe 540 350 540 1400 450
sleep 6
echo "top with card: $(top)"
XY=$(center_of "Play or pause")
echo "card play button at: $XY"
if [ -n "$XY" ]; then adb shell input tap $XY; fi
sleep 1
echo "== browse record before leaving"
adb shell dumpsys activity activities | grep -A60 "ActivityRecord{.*PhoneBrowseActivity" | grep -iE "ActivityRecord|state=|resizeMode|pictureInPicture|supportsEnterPip|pip|windowingMode|mActivityType|isPip" | head -30
adb shell dumpsys package org.smarttube.phone | grep -iE "PhoneBrowseActivity|PhonePlaybackActivity" | head -6
adb shell cmd appops get org.smarttube.phone PICTURE_IN_PICTURE
adb shell input keyevent KEYCODE_HOME
sleep 6
shot 17_left_app
echo "== pinned"
adb shell dumpsys activity activities | grep -iE "pinned|PIP" | head -12
echo "== pip log"
adb logcat -d | grep -E "PhonePip|host pip" | tail -15
# Back into the app from the floating window (as its expand button does): the video goes on in the card
adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -f 0x10200000 -n "$PKG/$SPLASH"
sleep 6
shot 18_back_in_app
echo "top after coming back: $(top)"
echo "card after coming back: $(center_of "Play or pause")"
adb logcat -d | grep -E "PhonePip" | tail -8
echo "== system pip log"
adb logcat -d | grep -iE "pip|picture|autoenter|pinned" | grep -v PhonePip | tail -40

adb logcat -d -b crash > $OUT/crash.txt
adb logcat -d | grep -E "ActivityTaskManager: (START|Displayed)|wm_finish|wm_destroy" | grep -i smarttube > $OUT/activity_log.txt
adb logcat -d | grep -iE "PhoneShorts|PhoneMiniPlayer|exoplayer|MediaCodec.*(error|Exception)|surface.*abandon|PlaybackFragment|AndroidRuntime" | grep -v SabrStream | tail -120 > $OUT/player_log.txt

echo "== crash"; cat $OUT/crash.txt
echo "== activities"; cat $OUT/activity_log.txt
echo "== player"; cat $OUT/player_log.txt

# Pictures into the log (small JPEGs), and frame strips from the recordings (10 per second)
img() { echo "==IMG $2=="; convert "$1" -resize "$3" -quality 70 jpg:- | base64 -w0; echo; echo "==END=="; }
for f in $OUT/*.png; do img "$f" "$(basename "$f" .png)" 270x; done
for v in 2_minimize 3_expand 4_back 6_swipe_down; do
  if [ -s "$OUT/$v.mp4" ]; then
    mkdir -p "$OUT/$v"
    ffmpeg -v error -i "$OUT/$v.mp4" -vf "fps=10,scale=180:-1" "$OUT/$v/f%03d.png"
    montage "$OUT/$v"/f*.png -tile 10x -geometry +1+1 "$OUT/${v}_strip.jpg"
    img "$OUT/${v}_strip.jpg" "${v}_strip" 1800x
  fi
done
exit 0
