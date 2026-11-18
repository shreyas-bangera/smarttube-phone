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

# 1. The Music page (second chip on Home)
tap 'text="Music"' || tap 'text="Música"'
sleep 20
shot music_1_page
adb shell input swipe 540 1900 540 900 600
sleep 5
shot music_2_shelves

# 2. Play (in the Music header): the music player
tap 'text="Play"'
sleep 35
shot music_3_player
sleep 25
shot music_4_player_later

# 3. The lyrics in place of the artwork
if tap 'content-desc="Lyrics"'; then
  sleep 4
  shot music_5_lyrics
  adb shell input keyevent KEYCODE_BACK
  sleep 2
fi

# 4. Swipe the artwork: the next song
XY=$(center 'content-desc="Artwork, swipe for the next song"')
if [ -n "$XY" ]; then
  set -- $XY
  adb shell input swipe $(( $1 + 300 )) $2 $(( $1 - 350 )) $2 250
  sleep 1
  shot music_6_swipe
  sleep 20
  shot music_7_next_song
fi

# 5. Small: the music bar at the bottom of the app
tap 'content-desc="Minimize"'
sleep 6
shot music_8_bar

# 6. A chart: its playlist page
adb shell input swipe 540 1900 540 1000 600
sleep 4
XY=$(center 'text="Top charts"')
if [ -n "$XY" ]; then
  set -- $XY
  adb shell input tap 540 $(( $2 + 450 ))
  sleep 15
  shot music_9_playlist
  adb shell input swipe 540 1700 540 1100 500
  sleep 3
  shot music_10_playlist_songs
fi

adb logcat -d | grep -E "AndroidRuntime|FATAL|PhoneLyrics" | tail -40
