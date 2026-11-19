#!/usr/bin/env bash
# Emulator test of Search in Settings (run 3: the gear opens Settings, search from the top), from the real app: Settings (with What's new and Check for updates at the
# bottom), the search icon there, results for a few words, and a result opening its page. Each step is a screenshot
# in the log (base64 JPEG between ==IMG name== and ==END==, also kept in ./screens).
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
query() { adb shell input keyevent KEYCODE_MOVE_END; for i in $(seq 1 25); do adb shell input keyevent 67; done; adb shell input text "$1"; sleep 4; }

adb logcat -c
adb shell am start -n "$PKG/$SPLASH"
sleep 40
tap 'text="You"' || tap 'content-desc="You"'
sleep 5
tap 'content-desc="Settings"' || tap 'text="Settings"'
sleep 6
shot s1_settings
# The bottom: Updates (What's new, Check for updates)
for i in 1 2 3 4 5; do adb shell input swipe 540 1900 540 500 300; sleep 1; done
sleep 2
shot s2_settings_bottom

# The search icon on the Settings page (back at the top, where the bar is): a box to search the settings
for i in 1 2 3 4 5 6; do adb shell input swipe 540 700 540 2000 300; sleep 1; done
sleep 2
tap 'content-desc="Search YouTube"'
sleep 3
shot s3_search_box
query "quality"
shot s4_quality
query "speed"
shot s5_speed
query "subtitle%ssize"
shot s6_subtitle
query "zzzz"
shot s7_none

# A result opens its page on its group
query "quality"
adb shell input keyevent KEYCODE_BACK   # the keyboard away
sleep 2
adb shell input tap 540 520
sleep 5
shot s8_result_opened
adb shell input keyevent KEYCODE_BACK
sleep 3
shot s9_back_to_results

adb logcat -d | grep -E "AndroidRuntime|FATAL|PhoneSettings" | tail -40
