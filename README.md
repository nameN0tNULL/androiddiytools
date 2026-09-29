# androiddiytools — Sakura Mate8 Lite

Current app version: **0.3.4**

This repository builds the Android 7 compatible Sakura Mate8 Lite floating translator client.

## v0.3.4

- Huawei Mate 8 / Android 7 compatible.
- No Google Play Services or AndroidX dependency.
- Portrait/landscape independent OCR crop regions.
- Draggable translation overlay with configurable font/background style.
- Manual translate / crop / force-retranslate floating controls.
- Landscape-safe floating control layout.
- Automatic translation toggle in the start section.
- Low-power local auto gate:
  - 17x9 dHash first pass.
  - 32x12 grayscale MAD only when dHash suggests a change.
  - About 250ms local stability confirmation before JPEG/network work.
- Unchanged/static frames do not leave the phone.
- Identical Chinese translation does not refresh the overlay.
- Failures/timeouts use the same styled translation overlay.
- Automatic backend failures use a local backoff.

## Build

GitHub Actions workflow:

`.github/workflows/build-apk.yml`

Artifact:

`SakuraMate8Lite-Android7-v0.3.4.apk`
