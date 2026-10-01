# PICO Neo 3 AV1 VR Player (MVP)

This directory contains an overlay and GitHub Actions pipeline that turns PICO's OpenXR VideoPlayer demo into a Neo 3 oriented AV1 player.

## MVP scope

- PICO Neo 3 / Android / arm64-v8a.
- 360-degree monoscopic equirectangular video.
- MP4, MKV, WebM and MOV containers through FFmpeg.
- AV1 software decoding explicitly prefers libdav1d.
- Audio is decoded by FFmpeg, resampled to stereo PCM, and played with Oboe.
- Opens local videos from Android/PICO file browsers through ACTION_VIEW.
- Supports both content:// and file:// sources.
- Requests legacy read permission only when a file:// source needs it on Android 10.
- SMB2/SMB3 browsing through jcifs-ng remains available inside the app.
- Local and SMB sources are exposed only on loopback as an HTTP byte-range stream at 127.0.0.1:8877/video, allowing FFmpeg to seek without copying the complete file first.
- SMB passwords are kept in process memory and are not written to disk.

## Architecture

Android/PICO file browser -> ACTION_VIEW -> BrowserActivity -> content/file bridge
or
BrowserActivity -> jcifs-ng -> SMB bridge

Both paths continue through:

loopback HTTP Range -> FFmpeg demux/codec -> libdav1d for AV1 -> NV12 frame queue -> PICO OpenXR OpenGLES 360 renderer.

The FFmpeg/dav1d build is produced by the upstream mpv-android build scripts. The OpenXR rendering path is based on picoxr/OpenXR_VideoPlayer_Demo.

Pinned upstream revisions:

- mpv-android: 687c7fd586d826fd0387809e14bec7a2b4fc45a1
- PICO OpenXR VideoPlayer Demo: 8831f7a5e1dc76c2765d784bc21ccef27c7980a6

## Build

Run the GitHub Actions workflow named Build PICO Neo3 AV1 VR Player. The artifact is PicoNeo3-AV1-VR-debug.apk.

Install it with adb install -r PicoNeo3-AV1-VR-debug.apk.

## Usage: PICO / Android file browser

1. Open the built-in PICO file browser or another Android file manager.
2. Select a supported video.
3. Choose Open with / Other app and select Neo3 AV1 VR.
4. The app receives the file URI, starts its loopback range bridge and enters the OpenXR 360 player.

The app registers for video/*, application/x-matroska and application/octet-stream MIME types, plus file:// extension fallbacks for mp4, m4v, mov, mkv and webm.

## Usage: SMB browser

1. Launch Neo3 AV1 VR directly.
2. Enter an SMB directory such as smb://192.168.1.20/video/.
3. Enter domain (optional), username and password.
4. Tap Connect / Refresh.
5. Navigate folders and tap a video file.
6. The app starts the loopback range bridge and enters the OpenXR 360 player.

## Current limitations

Neo 3 still has to decode AV1 in software. A 4320x2160 at 60 fps AV1 stream can exceed the XR2 CPU budget. This APK is intended to measure that real ceiling without transcoding.

The first MVP fixes projection to 360 mono. SBS/OU, playback HUD, seek UI and frame-performance statistics are follow-up work.

For content:// sources, the provider needs to expose a usable file size. Seekable file descriptors are preferred; a sequential fallback exists when direct file-descriptor seeking is unavailable.
