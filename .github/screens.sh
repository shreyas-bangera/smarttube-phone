#!/usr/bin/env bash
# README screenshots of the music features, from the real app on the emulator: the Music page, a playlist, the music
# player, its lyrics and the music bar. Each one is printed to the log (base64 JPEG between ==IMG name== and ==END==)
# and kept in ./screens.
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

# 1. Search with the music switch on
tap 'content-desc="Search YouTube"'
sleep 4
tap 'content-desc="Music search"'
sleep 1
adb shell input text "shape%sof%syou%sed%ssheeran"
adb shell input keyevent 66
sleep 15
shot search_1_music

# 2. A result: the music player
tap 'text=".*Shape of You.*"' || adb shell input tap 540 700
sleep 30
shot search_2_player

# 3. Video: the video player, with Listen under the video
tap 'text="Video"'
sleep 8
shot player_listen

# 4. Home, the Music page and its search button
adb shell input keyevent KEYCODE_BACK
sleep 3
adb shell input keyevent KEYCODE_BACK
sleep 3
adb shell input keyevent KEYCODE_BACK
sleep 3
adb shell am start -n "$PKG/$SPLASH"
sleep 8
tap 'text="Music"'
sleep 15
adb shell input swipe 540 1700 540 1150 500
sleep 3
shot music_search_button

# 5. A chart: its playlist page (its card shows the number of videos)
adb shell input swipe 540 1900 540 900 600
sleep 4
tap 'text="[0-9.,]+ videos"' || tap 'text=".*[0-9]+ videos"'
sleep 15
shot playlist_1
adb shell input swipe 540 1800 540 1000 500
sleep 3
shot playlist_2

adb logcat -d | grep -E "AndroidRuntime|FATAL|PhoneLyrics" | tail -40
