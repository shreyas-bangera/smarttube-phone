#!/usr/bin/env bash
# README screenshots of the music player (run 7): the Music page, the player with its living background, lyrics,
# Up next, Cover Flow (portrait and landscape), a few songs' scenes and the music bar. Each is a screenshot in the
# log (base64 JPEG between ==IMG name== and ==END==, also kept in ./screens). The player never stops moving, so
# after it opens the taps go to fixed places (no waiting for the screen to settle) and every step has a time limit.
set -x
PKG=org.smarttube.phone
SPLASH=com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity
OUT=screens
mkdir -p $OUT

adb install -r -g app.apk
adb shell settings put global hide_error_dialogs 1
adb shell pm disable-user --user 0 com.google.android.apps.nexuslauncher
adb shell cmd uimode night yes
# A clean status bar
adb shell settings put global sysui_demo_allowed 1
adb shell am broadcast -a com.android.systemui.demo -e command enter
adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941
adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false
adb shell am broadcast -a com.android.systemui.demo -e command network -e wifi show -e level 4
adb shell am broadcast -a com.android.systemui.demo -e command notifications -e visible false

dump() { timeout 25 adb shell uiautomator dump /sdcard/ui.xml > /dev/null 2>&1; timeout 10 adb shell cat /sdcard/ui.xml | tr '>' '\n'; }
center() {
  dump | grep -E "$1" | head -1 \
    | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' \
    | awk 'NF==4 { printf "%d %d", ($1+$3)/2, ($2+$4)/2 }'
}
tap() { local xy; xy=$(center "$1"); echo "tap '$1' at: $xy"; if [ -n "$xy" ]; then adb shell input tap $xy; return 0; fi; return 1; }
shot() {
  sleep 1
  timeout 30 adb exec-out screencap -p > "$OUT/$1.png"
  echo "==IMG $1=="; convert "$OUT/$1.png" -resize 720x -quality 86 jpg:- | base64 -w0; echo; echo "==END=="
}
portrait() { adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 0; }
landscape() { adb shell settings put system accelerometer_rotation 0; adb shell settings put system user_rotation 1; }

portrait
adb shell am start -n "$PKG/$SPLASH"
sleep 45
adb shell input keyevent KEYCODE_BACK   # a What's new card, if any
sleep 2
adb shell am start -n "$PKG/$SPLASH"
sleep 6

# The Music page
tap 'text="Music"'
sleep 12
shot 14_music

# A song from a music search
tap 'content-desc="Search YouTube"'
sleep 4
tap 'content-desc="Music search"'
sleep 1
adb shell input text "perfect%sed%ssheeran"
adb shell input keyevent 66
sleep 15
adb shell input tap 540 700
sleep 40
adb shell input tap 540 1864   # play (the emulator may start it paused)
sleep 8
shot 15_music_player

# Lyrics: tap the line being sung
adb shell input tap 300 1400
sleep 4
shot 16_music_lyrics
adb shell input keyevent KEYCODE_BACK
sleep 3

# Up next
adb shell input tap 540 2045
sleep 3
shot 28_up_next
adb shell input keyevent KEYCODE_BACK
sleep 3

# Cover Flow in portrait: a long press on the artwork
adb shell input swipe 540 750 540 750 1300
sleep 3
adb shell input swipe 760 1100 420 1100 300
sleep 3
shot 29_cover_flow
adb shell input keyevent KEYCODE_BACK
sleep 3

# Cover Flow in landscape: the player itself
landscape
sleep 8
shot 30_cover_flow_landscape
adb shell input swipe 1500 480 1150 480 300
sleep 3
shot 31_cover_flow_landscape_swiped
portrait
sleep 8

# Other songs, other scenes behind them: Next
for n in 1 2 3; do
  adb shell input tap 750 1864
  sleep 25
  shot 3$((n+1))_scene_$n
done

# The music bar: the arrow at the top left
adb shell input tap 100 210
sleep 5
shot 17_music_bar

timeout 20 adb logcat -d | grep -E "AndroidRuntime|FATAL" | tail -30
