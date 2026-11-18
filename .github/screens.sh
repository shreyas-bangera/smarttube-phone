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
shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "==IMG $1=="; convert "$OUT/$1.png" -resize 540x -quality 82 jpg:- | base64 -w0; echo; echo "==END=="; }

adb shell am start -n "$PKG/$SPLASH"
sleep 45

# 1. The Music page (second chip on Home)
tap 'text="Music"' || tap 'text="Música"'
sleep 20
shot music_1_page
adb shell input swipe 540 1900 540 900 600
sleep 5
shot music_2_shelves

# 2. A playlist (its row shows the number of tracks)
tap 'text="[0-9.,]+\+? tracks"' || tap 'text=".*[0-9]+ (videos|tracks)"'
sleep 15
shot music_3_playlist

# 3. Play all: the music player
tap 'text="Play all"'
sleep 30
shot music_4_player
sleep 20
shot music_5_player_later

# 4. The lyrics in place of the artwork
if tap 'content-desc="Lyrics"'; then
  sleep 4
  shot music_6_lyrics
  adb shell input keyevent KEYCODE_BACK
  sleep 2
fi

# 5. Swipe the artwork: the next song
XY=$(center 'content-desc="Artwork, swipe for the next song"')
if [ -n "$XY" ]; then
  set -- $XY
  adb shell input swipe $(( $1 + 300 )) $2 $(( $1 - 350 )) $2 250
  sleep 1
  shot music_7_swipe
  sleep 15
fi

# 6. Small: the music bar at the bottom of the app
tap 'content-desc="Minimize"'
sleep 6
shot music_8_bar

adb logcat -d | grep -E "AndroidRuntime|FATAL|PhoneLyrics" | tail -40
