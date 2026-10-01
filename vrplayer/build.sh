#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PICO="$ROOT/_work/pico/OpenXR/Sample/VideoPlayer"
MPV_PREFIX="$ROOT/_work/mpv-android/buildscripts/prefix/arm64"

prepare() {
  test -d "$PICO/app"
  test -d "$MPV_PREFIX/include"
  test -f "$MPV_PREFIX/lib/libavcodec.so"

  cp "$ROOT/vrplayer/overlay/build.gradle" "$PICO/build.gradle"
  cp "$ROOT/vrplayer/overlay/CMakeLists.txt" "$PICO/CMakeLists.txt"

  cp "$ROOT/vrplayer/overlay/app/AndroidManifest.xml" "$PICO/app/AndroidManifest.xml"
  cp "$ROOT/vrplayer/overlay/app/options.h" "$PICO/app/options.h"
  cp "$ROOT/vrplayer/overlay/app/player.h" "$PICO/app/player.h"
  cp "$ROOT/vrplayer/overlay/app/player.cpp" "$PICO/app/player.cpp"

  mkdir -p "$PICO/app/java/com/khronos/player"
  cp "$ROOT/vrplayer/overlay/app/java/com/khronos/player/"*.java "$PICO/app/java/com/khronos/player/"
  rm -f "$PICO/app/java/com/khronos/hello_xr/MainActivity.java"

  mkdir -p "$PICO/ffmpeg-include" "$PICO/native-libs/arm64-v8a"
  cp -a "$MPV_PREFIX/include/." "$PICO/ffmpeg-include/"

  for lib in avutil swresample swscale avcodec avformat; do
    cp "$MPV_PREFIX/lib/lib${lib}.so" "$PICO/native-libs/arm64-v8a/"
  done

  echo "Prepared PICO project at $PICO"
  ls -lh "$PICO/native-libs/arm64-v8a"
}

case "${1:-}" in
  prepare) prepare ;;
  *)
    echo "usage: $0 prepare" >&2
    exit 2
    ;;
esac
