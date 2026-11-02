#!/bin/sh
# Prepare host FFmpeg and its dependent libraries for a relocatable macOS app.
set -eu
root=$(CDPATH= cd -- "$(dirname "$0")/.." && pwd)
test "$(uname -s)" = Darwin || { echo 'This script requires macOS.' >&2; exit 1; }
case "$(uname -m)" in
  arm64) profile=macos-arm64 ;;
  x86_64) profile=macos-x64 ;;
  *) exit 1 ;;
esac
media="$root/native/$profile/media"
mkdir -p "$media"
for tool in ffmpeg ffprobe; do
  source=$(command -v "$tool")
  cp "$source" "$media/$tool"
  python3 "$root/scripts/bundle-macos-libraries.py" "$media/$tool"
done
prefix=$(brew --prefix ffmpeg)
mkdir -p "$media/licenses/ffmpeg"
cp "$prefix"/COPYING* "$prefix/LICENSE.md" "$media/licenses/ffmpeg/"
"$media/ffmpeg" -version > "$media/FFmpeg-version.txt" 2>&1
"$media/ffmpeg" -L > "$media/FFmpeg-LICENSE.txt" 2>&1
echo "Prepared media tools at $media"
