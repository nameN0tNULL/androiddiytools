# androiddiytools — Sakura Mate8 Lite

Current app version: **0.3.6**

This repository builds the Android 7 compatible Sakura Mate8 Lite floating translator client.

## v0.3.6

- Huawei Mate 8 / Android 7 compatible.
- No Google Play Services or AndroidX dependency.
- Portrait/landscape independent OCR crop regions.
- Draggable translation overlay with configurable font/background style.
- Visual RGB color pickers for font and background colors with live preview and HEX output.
- Manual translate / crop / force-retranslate floating controls.
- Landscape-safe floating control layout.
- Runtime floating `自` button toggles automatic translation after the overlay service starts.
- Automatic mode starts disabled for each overlay session; tap `自` once to enable and again to disable.
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

`SakuraMate8Lite-Android7-v0.3.6.apk`
