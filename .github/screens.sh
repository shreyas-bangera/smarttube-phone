#!/usr/bin/env bash
# Emulator test of playback in feeds (run 1): Home with the video in view playing in its thumbnail, after scrolling
# down and back up, Subscriptions, a preview opened in the player, and back from the home screen. Screenshots in the
# log (base64 JPEG between ==IMG name== and ==END==), and the preview's own log lines (PhoneFeedPreview).
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
adb logcat -c
adb shell am start -n "$PKG/$SPLASH"
sleep 45
adb shell input keyevent KEYCODE_BACK   # a What's new card, if any
sleep 2
adb shell am start -n "$PKG/$SPLASH"
sleep 4
# Feed previews on (the emulator's network may not count as Wi-Fi): Settings > Customize > Playback in feeds > Always
adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs; echo \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\" standalone=\\\"yes\\\" ?><map><int name=\\\"feed_playback\\\" value=\\\"0\\\" /></map>\" > shared_prefs/phone_tweaks.xml'" || true
adb shell am force-stop $PKG
adb shell am start -n "$PKG/$SPLASH"
sleep 30
shot f1_home
sleep 6
shot f2_home_playing
# Down a few cards (slow), the next one takes over
adb shell input swipe 540 1700 540 1000 600
sleep 5
shot f3_scrolled
# Fast scrolling both ways
adb shell input swipe 540 1900 540 400 150
adb shell input swipe 540 1900 540 400 150
sleep 1
adb shell input swipe 540 500 540 1900 150
sleep 5
shot f4_fast_back
# Subscriptions, then Home again
tap 'text="Subscriptions"' || tap 'content-desc="Subscriptions"'
sleep 6
shot f5_subscriptions
tap 'text="Home"' || tap 'content-desc="Home"'
sleep 8
shot f6_home_again
# Home screen and back
adb shell input keyevent KEYCODE_HOME
sleep 4
adb shell am start -n "$PKG/$SPLASH"
sleep 8
shot f7_back_from_home
# Open the preview playing (tap its card's picture)
adb shell input tap 540 600
sleep 12
shot f8_opened

timeout 20 adb logcat -d | grep -E "PhoneFeedPreview|AndroidRuntime|FATAL" | tail -60
