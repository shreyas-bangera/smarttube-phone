#!/usr/bin/env bash
# README screenshots from the real app on the emulator: the music player with lyrics and its mood background (a love,
# a sad and a party song), the lyrics panel, and What's new. Each one is printed to the log (base64 JPEG between
# ==IMG name== and ==END==) and kept in ./screens.
set -x
PKG=org.smarttube.phone
SPLASH=com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity
OUT=screens
mkdir -p $OUT

adb install -r -g app.apk
adb shell settings put global hide_error_dialogs 1
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher
# Dark appearance, as in the other screenshots
adb shell cmd uimode night yes

dump() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; adb shell cat /sdcard/ui.xml | tr '>' '\n'; }
# Centre of the first view whose text (or content description) matches, from the UI dump
center() {
  dump | grep -E "$1" | head -1 \
    | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' \
    | awk 'NF==4 { printf "%d %d", ($1+$3)/2, ($2+$4)/2 }'
}
tap() { local xy; xy=$(center "$1"); echo "tap '$1' at: $xy"; if [ -n "$xy" ]; then adb shell input tap $xy; return 0; fi; return 1; }
shot() { adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; sleep 1; adb exec-out screencap -p > "$OUT/$1.png"; echo "==IMG $1=="; convert "$OUT/$1.png" -resize 540x -quality 82 jpg:- | base64 -w0; echo; echo "==END=="; }

adb shell am start -n "$PKG/$SPLASH"
sleep 45

# Music search on, once
tap 'content-desc="Search YouTube"'
sleep 4
tap 'content-desc="Music search"'
sleep 1

# A song from a music search: the player (lyrics line, mood background), then its lyrics
song() {
  local query=$1 name=$2
  adb shell input text "$query"
  adb shell input keyevent 66
  sleep 15
  adb shell input tap 540 700
  sleep 45
  shot "${name}_player"
  if tap 'content-desc="Lyrics"'; then
    sleep 5
    shot "${name}_lyrics"
    adb shell input keyevent KEYCODE_BACK
    sleep 2
  fi
  # Back to the search box for the next one
  adb shell input keyevent KEYCODE_BACK
  sleep 3
  tap 'content-desc="Search YouTube"' || true
  sleep 3
  tap 'resource-id="[^"]*search[^"]*" .*text="[^"]+"' > /dev/null || true
  adb shell input keyevent KEYCODE_MOVE_END
  for i in $(seq 1 60); do adb shell input keyevent 67; done
}

song "tum%shi%sho%sarijit%ssingh" love
song "channa%smereya" sad
song "london%sthumakda" party

# What's new: You > Settings > What's new
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell input keyevent KEYCODE_BACK
sleep 2
adb shell am start -n "$PKG/$SPLASH"
sleep 8
tap 'text="You"' || tap 'content-desc="You"'
sleep 5
tap 'text="Settings"'
sleep 4
for i in 1 2 3; do tap "text=\"What's new\"" && break; adb shell input swipe 540 1800 540 900 500; sleep 2; done
sleep 4
shot whats_new

adb logcat -d | grep -E "AndroidRuntime|FATAL|PhoneLyrics" | tail -80
