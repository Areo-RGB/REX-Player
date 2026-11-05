#!/usr/bin/env bash
# Build the debug APK, install it on the project device over whatever is there (keeps data; allows the
# version-code downgrade and test-only flag the debug build needs), confirm MIUI's USB prompt.
#   .phonectl/install.sh            # device from .phonectl/device (see: phonectl devices)
#   .phonectl/install.sh pixel8     # another saved phonectl device
set -euo pipefail
cd "$(dirname "$0")/.."
device=${1:-$(phonectl devices | awk '/^\*/{print $2}')}
serial=$(phonectl -d "$device" devices | awk '/^\*/{sub("serial=","",$4); print $4}')
echo "installing on $device (adb $serial)"
./gradlew :app:assembleDebug -q
apk=app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
# MIUI blocks `adb install` on an "Install via USB?" dialog: tap it from the side while adb waits
( for _ in $(seq 20); do sleep 1; phonectl -d "$device" tap --text Install --wait-timeout 0.5 >/dev/null 2>&1 && break; done ) &
adb -s "$serial" install -r -d -t "$apk"
wait
