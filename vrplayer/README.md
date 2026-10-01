# NAS AV1 VR for PICO Neo 3

A deliberately minimal PICO Neo 3 VR video player for anonymous/guest SMB shares.

## User flow

1. Launch NAS AV1 VR.
2. The app immediately discovers SMB services on the local network.
3. Tap a NAS.
4. Tap a share or folder.
5. Tap a video.
6. The OpenXR player starts in 360 mono mode.

There is no login form, domain field, username field, password field, local-file browser, or other account/setup UI.

## NAS access model

The app intentionally supports only SMB Guest / anonymous access.

Discovery uses Android NSD for _smb._tcp. and also attempts jcifs root SMB browsing as a fallback. Once a NAS is selected, jcifs-ng browses its shares and folders.

If a NAS requires authenticated SMB access, this build will not prompt for credentials; enable a Guest/anonymous read-only share on the NAS instead.

## Playback pipeline

SMB2/SMB3 Guest share
-> jcifs-ng
-> loopback HTTP byte-range bridge at 127.0.0.1:8877/video
-> FFmpeg demux/audio decode
-> AV1 explicitly prefers libdav1d
-> NV12 frame queue
-> PICO OpenXR OpenGLES 360 renderer

Audio is decoded by FFmpeg, resampled to 48 kHz stereo PCM and played with Oboe.

## Supported containers

- MP4
- MKV
- WebM
- MOV
- M4V

## Build

Run the GitHub Actions workflow named Build PICO Neo3 AV1 VR Player.

The produced artifact is PicoNeo3-AV1-VR-debug.apk.

Install with: adb install -r PicoNeo3-AV1-VR-debug.apk

## PICO safety boundary

The app uses PICO OpenXR for immersive playback. PICO may require its system safety boundary / play-area confirmation when an OpenXR VR activity starts. That prompt belongs to the headset runtime and is not an account/login feature copied into this app.

The application-level UI itself contains no safety-zone setup page.

## Current limitations

- 360 mono only.
- AV1 is decoded in software on Neo 3; 4320x2160 at 60 fps may exceed XR2 CPU performance.
- No playback HUD or seek controls yet.
- NAS must allow Guest/anonymous SMB access.