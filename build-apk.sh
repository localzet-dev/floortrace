#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
./gradlew assembleDebug
printf '\nAPK: %s\n' "$(pwd)/app/build/outputs/apk/debug/app-debug.apk"
