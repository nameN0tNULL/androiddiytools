# Brave PICO Neo3 SOCKS5 build

This branch is isolated from the Sakura Mate8 Lite app on `main`.

## Goal

Build an ARM64 Brave APK for PICO Neo3 where proxy configuration lives inside the browser process and does not change Android's system proxy/VPN state.

## Source pins

- Brave core: `v1.97.37` / `973c5c8667ce80d2d3c227ef6e60df530eb26d35`
- Chromium: `153.0.8010.37`
- Cromite proxy-page patch: repository commit `3724efc6ede3d2d94611c1fe71d1b090ebbb40a7`

The Brave and Cromite pins intentionally use the same Chromium base version so the proxy patch has the best chance of applying cleanly.

## Proxy behavior

The imported Cromite patch adds browser-level proxy configuration, including a proxy page and Android settings entry. A SOCKS5 URL such as:

```
socks5://192.168.1.10:1080
```

is handled by Chromium's own network stack. It does not use Android `VpnService` and does not set a global Android/Wi-Fi proxy, so other PICO applications are unaffected.

This build does not add SOCKS5 username/password authentication.

## Build

Workflow:

```
.github/workflows/build-brave-pico-socks5.yml
```

The workflow uses a GitHub-hosted Ubuntu runner, frees disk space, adds swap, initializes the pinned Brave/Chromium tree, verifies/applies the pinned Cromite proxy patch, and builds an optimized ARM64 development APK.

The produced APK is development-signed and is not an official Brave release.
