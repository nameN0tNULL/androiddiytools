# PICO Neo 3 AV1 VR Player (MVP)

This directory contains an overlay and GitHub Actions pipeline that turns PICO's OpenXR VideoPlayer demo into a Neo 3 oriented AV1 player.

## MVP scope

- PICO Neo 3 / Android / arm64-v8a.
- 360-degree monoscopic equirectangular video.
- MP4, MKV, WebM and MOV containers through FFmpeg.
- AV1 software decoding explicitly prefers libdav1d.
- Audio is decoded by FFmpeg, resampled to stereo PCM, and played with Oboe.
- SMB2/SMB3 browsing through jcifs-ng.
- The selected SMB file is exposed only on loopback as an HTTP byte-range stream at 127.0.0.1:8877/video, allowing FFmpeg to seek without copying the complete file.
- SMB passwords are kept in process memory and are not written to disk.

## Architecture

BrowserActivity -> jcifs-ng -> SmbBridgeService -> loopback HTTP Range -> FFmpeg demux/codec -> libdav1d for AV1 -> NV12 frame queue -> PICO OpenXR OpenGLES 360 renderer.

The FFmpeg/dav1d build is produced by the upstream mpv-android build scripts. The OpenXR rendering path is based on picoxr/OpenXR_VideoPlayer_Demo.

Pinned upstream revisions:

- mpv-android: 687c7fd586d826fd0387809e14bec7a2b4fc45a1
- PICO OpenXR VideoPlayer Demo: 8831f7a5e1dc76c2765d784bc21ccef27c7980a6

## Build

Run the GitHub Actions workflow named Build PICO Neo3 AV1 VR Player. The artifact is PicoNeo3-AV1-VR-debug.apk.

Install it with adb install -r PicoNeo3-AV1-VR-debug.apk.

## Usage

1. Launch Neo3 AV1 VR.
2. Enter an SMB directory such as smb://192.168.1.20/video/.
3. Enter domain (optional), username and password.
4. Tap Connect / Refresh.
5. Navigate folders and tap a video file.
6. The app starts the loopback range bridge and enters the OpenXR 360 player.

## Current limitation

Neo 3 still has to decode AV1 in software. A 4320x2160 at 60 fps AV1 stream can exceed the XR2 CPU budget. This APK is intended to measure that real ceiling without transcoding. The first MVP fixes projection to 360 mono; SBS/OU, playback HUD, seek UI and frame-performance statistics are follow-up work.
