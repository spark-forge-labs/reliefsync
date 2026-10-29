#!/bin/bash
# One-click: start backend (if needed) + Android emulator, build & install ReliefSync, launch it.
cd "$(dirname "$0")"
ROOT="$(pwd)"
LOG="$ROOT/android-run.log"
exec > >(tee "$LOG") 2>&1
echo "=== ReliefSync Android runner $(date) ==="

for d in "$ANDROID_HOME" "$HOME/Library/Android/sdk" "$HOME/android-sdk"; do
  [ -n "$d" ] && [ -x "$d/platform-tools/adb" ] && export ANDROID_HOME="$d" && break
done
echo "ANDROID_HOME=$ANDROID_HOME"
[ -z "$ANDROID_HOME" ] && { echo "!! Android SDK not found"; exit 1; }
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ -z "$JAVA_HOME" ]; then
  for j in "/Applications/Android Studio.app/Contents/jbr/Contents/Home" "$(/usr/libexec/java_home -v 17 2>/dev/null)" "$(/usr/libexec/java_home 2>/dev/null)"; do
    [ -n "$j" ] && [ -x "$j/bin/java" ] && export JAVA_HOME="$j" && break
  done
fi
echo "JAVA_HOME=$JAVA_HOME"; "$JAVA_HOME/bin/java" -version 2>&1 | head -1
ADB="$ANDROID_HOME/platform-tools/adb"
EMU="$ANDROID_HOME/emulator/emulator"

# 1. backend
if ! curl -s -m 2 http://localhost:8077/api/health | grep -q reliefsync; then
  echo "--- starting backend on :8077"
  nohup bash "$ROOT/run.sh" > "$ROOT/backend.log" 2>&1 &
  for i in $(seq 1 60); do curl -s -m 1 http://localhost:8077/api/health | grep -q reliefsync && break; sleep 1; done
fi
curl -s http://localhost:8077/api/health; echo

# 2. emulator
if ! "$ADB" devices | grep -q "emulator-"; then
  AVD="$("$EMU" -list-avds | head -1)"
  echo "AVDs: $("$EMU" -list-avds | tr '\n' ' ')"
  [ -z "$AVD" ] && { echo "!! No AVD found. Create one in Android Studio > Device Manager."; exit 1; }
  echo "--- booting emulator $AVD"
  nohup "$EMU" -avd "$AVD" -no-snapshot-save -no-boot-anim > "$ROOT/emulator.log" 2>&1 &
fi
"$ADB" wait-for-device
for i in $(seq 1 120); do [ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ] && break; sleep 2; done
echo "device: $("$ADB" devices | sed -n 2p)"

# 3. build + install
cd "$ROOT/android"
echo "sdk.dir=$ANDROID_HOME" > local.properties
chmod +x gradlew
./gradlew --console=plain assembleDebug || { echo "!! BUILD FAILED"; exit 1; }
"$ADB" install -r -g app/build/outputs/apk/debug/app-debug.apk
"$ADB" shell am start -n com.sparkforge.reliefsync/.MainActivity
echo "=== DONE: ReliefSync running on the emulator ==="
