#!/usr/bin/env bash
# Installs an older release, then the latest APK over it without uninstalling (the update path users take).
set -x
PKG=org.smarttube.phone
OLD_TAG="${OLD_TAG:-build-39}"
mkdir -p smoke
for i in 1 2 3 4 5; do
  curl -fsSL -o old.apk "https://github.com/shreyas-bangera/streamtube/releases/download/$OLD_TAG/SmartTube-Phone-arm64.apk" && break
  sleep 10
done
adb install -g old.apk > smoke/install_old.txt 2>&1
adb shell dumpsys package $PKG | grep -E "versionCode|versionName" > smoke/version_old.txt
adb shell am start -n "$PKG/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"
sleep 20
adb install -r -g app.apk > smoke/install_update.txt 2>&1
echo "update exit=$?" >> smoke/install_update.txt
adb shell dumpsys package $PKG | grep -E "versionCode|versionName|firstInstallTime|lastUpdateTime" > smoke/version_new.txt
adb shell am start -n "$PKG/com.liskovsoft.smartyoutubetv2.tv.ui.main.SplashActivity"
sleep 25
adb exec-out screencap -p > smoke/after_update.png
adb logcat -d -b crash > smoke/crash.txt
exit 0
