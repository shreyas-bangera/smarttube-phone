#!/usr/bin/env bash
# README screenshots from the real app on the emulator: English songs in the music player, with their lyrics and
# mood background (a love, a sad and a party song). Each one is printed to the log (base64 JPEG between ==IMG name==
# and ==END==) and kept in ./screens.
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
# Music search on, once (it stays on)
tap 'content-desc="Search YouTube"'
sleep 4
tap 'content-desc="Music search"'
sleep 1
adb shell am force-stop $PKG

# A song from a music search, in the music player: its lyrics with a line lit (tapping a line takes the song there),
# then the player with that line under the title and the mood background
song() {
  local query=$1 name=$2
  adb shell am start -n "$PKG/$SPLASH"
  sleep 25
  tap 'content-desc="Search YouTube"'
  sleep 4
  adb shell input text "$query"
  adb shell input keyevent 66
  sleep 15
  adb shell input tap 540 700
  sleep 40
  if tap 'content-desc="Lyrics"'; then
    sleep 4
    adb shell input tap 300 1060
    sleep 4
    shot "${name}_lyrics"
    adb shell input keyevent KEYCODE_BACK
    sleep 3
  fi
  shot "${name}_player"
  adb shell am force-stop $PKG
  sleep 2
}

# song "perfect%sed%ssheeran" love (taken)
# song "fix%syou%scoldplay" sad (taken)
# song "party%srock%santhem%slmfao" party (taken)

adb logcat -d | grep -E "AndroidRuntime|FATAL|PhoneLyrics" | tail -80
