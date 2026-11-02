#!/bin/sh
# Host-architecture macOS JVM distribution; no global Java/shell settings are changed.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
test "$(uname -s)" = Darwin || { echo 'This script requires macOS.' >&2; exit 1; }
case "$(uname -m)" in
  arm64) profile=macos-arm64 ;;
  x86_64) profile=macos-x64 ;;
  *) exit 1 ;;
esac
if [ -z "${JAVA_HOME:-}" ]; then JAVA_HOME=$(/usr/libexec/java_home -v 21); export JAVA_HOME; fi
export PATH="$JAVA_HOME/bin:$PATH"
test -f "$root/native/$profile/manifest.properties" || sh "$root/scripts/build-tdlib.sh"
if [ ! -x "$root/native/$profile/media/ffmpeg" ]; then sh "$root/scripts/prepare-media-macos.sh"; fi
cd "$root"
# Use --release to require builder-owned Telegram credentials. Default builds allow offline previews.
case "${1:-}" in
  '') set -- ;;
  --release) set -- -PrequireTelegramApi=true ;;
  *) echo 'Usage: scripts/build-macos.sh [--release]' >&2; exit 1 ;;
esac
if [ "$#" -gt 0 ]; then
  python3 scripts/desktop.py build --release --installer
else
  python3 scripts/desktop.py build --installer
fi
