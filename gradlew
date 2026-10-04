#!/usr/bin/env sh
set -eu

GRADLE_VERSION="9.6.0"

if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi

CACHE_ROOT="${GRADLE_USER_HOME:-$HOME/.gradle}/floortrace-bootstrap"
DIST_DIR="$CACHE_ROOT/gradle-$GRADLE_VERSION"
ZIP="$CACHE_ROOT/gradle-$GRADLE_VERSION-bin.zip"
URL="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
EXPECTED_SHA256="bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01"

mkdir -p "$CACHE_ROOT"
if [ ! -x "$DIST_DIR/bin/gradle" ]; then
  if [ ! -f "$ZIP" ]; then
    if command -v curl >/dev/null 2>&1; then
      curl -fL "$URL" -o "$ZIP"
    elif command -v wget >/dev/null 2>&1; then
      wget -O "$ZIP" "$URL"
    else
      echo "Neither Gradle nor curl/wget is available." >&2
      exit 2
    fi
  fi
  if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA256="$(sha256sum "$ZIP" | cut -d ' ' -f 1)"
  elif command -v shasum >/dev/null 2>&1; then
    ACTUAL_SHA256="$(shasum -a 256 "$ZIP" | cut -d ' ' -f 1)"
  else
    echo "sha256sum or shasum is required." >&2
    exit 2
  fi
  [ "$ACTUAL_SHA256" = "$EXPECTED_SHA256" ] || { echo "Gradle checksum mismatch." >&2; exit 2; }
  command -v unzip >/dev/null 2>&1 || { echo "unzip is required." >&2; exit 2; }
  rm -rf "$DIST_DIR"
  unzip -q "$ZIP" -d "$CACHE_ROOT"
fi

exec "$DIST_DIR/bin/gradle" "$@"
