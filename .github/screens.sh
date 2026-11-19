#!/usr/bin/env bash
# Emulator test of Customize > Tab bar (run 5: the spring in small steps), from the real app: Home with each tab bar style (Classic, Floating, Liquid
# glass), the drop-down, and a tab switch (the pill or lens moving). Each step is a screenshot in the log (base64 JPEG
# between ==IMG name== and ==END==, also kept in ./screens).
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
tap() { local xy; xy=$(center "$1"); echo "tap '$1' at: $xy"; if [ -n "$xy" ]; then adb shell input tap $xy; return 0; fi; return 1; }
shot() { adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1; sleep 1; adb exec-out screencap -p > "$OUT/$1.png"; echo "==IMG $1=="; convert "$OUT/$1.png" -resize 540x -quality 82 jpg:- | base64 -w0; echo; echo "==END=="; }

# Settings > Customize > Tab bar > the style; back to Home
style() {
  tap 'text="You"' || tap 'content-desc="You"'
  sleep 4
  tap 'content-desc="Settings"' || tap 'text="Settings"'
  sleep 5
  for i in 1 2; do tap 'text="Customize"' && break; adb shell input swipe 540 1600 540 900 300; sleep 2; done
  sleep 4
  tap 'text="Tab bar"'
  sleep 2
  [ "$2" = menu ] && shot tabs_menu
  tap "text=\"$1\""
  sleep 2
  adb shell input keyevent KEYCODE_BACK
  sleep 2
  adb shell input keyevent KEYCODE_BACK
  sleep 2
  tap 'text="Home"' || tap 'content-desc="Home"'
  sleep 8
}

adb shell am start -n "$PKG/$SPLASH"
sleep 45
shot tabs_classic

style "Floating" menu
shot tabs_floating
tap 'text="Subscriptions"'
sleep 3
shot tabs_floating_subs

style "Liquid glass"
shot tabs_glass
# Slide a finger along the bar: the lens follows it, lifted and magnifying
HY=$(center 'text="Home"' | awk '{print $2}'); HX=$(center 'text="Home"' | awk '{print $1}')
SX=$(center 'text="Subscriptions"' | awk '{print $1}')
echo "bar at y=$HY, Home x=$HX, Subscriptions x=$SX"
if [ -n "$HY" ]; then
  adb shell input motionevent DOWN $HX $HY
  for x in $(seq $HX 40 $SX); do adb shell input motionevent MOVE $x $HY; done
  sleep 0.6
  shot tabs_glass_dragging
  adb shell input motionevent UP $SX $HY
  sleep 2
  shot tabs_glass_after_drag
fi
tap 'text="You"'
sleep 0.2
shot tabs_glass_moving
sleep 3
shot tabs_glass_you

adb logcat -d | grep -E "PhoneTabBar|FATAL|Fatal signal|Abort message|backtrace:|#0[0-9] pc|has died|ANR in" | tail -60
echo "---- crash buffer"
adb logcat -d -b crash | tail -80
