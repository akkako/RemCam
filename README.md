**English** | [简体中文](README.zh-CN.md)

<p align="right">
  <img src="rem.png" alt="RemCam" width="150">
</p>

# RemCam

[![Build Release APK](https://github.com/akkako/RemCam/actions/workflows/android-release.yml/badge.svg)](https://github.com/akkako/RemCam/actions/workflows/android-release.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

RemCam turns an Android phone into a LAN RTSP camera. It captures with Camera2, encodes H.264 or H.265 with MediaCodec, and serves the stream locally over RTSP/RTP. VLC, ffplay, GStreamer and other RTSP clients can connect without a cloud relay.

New to RemCam? Start with the [User Guide](USER_GUIDE.md).

## Features

- **Local RTSP streaming** — built-in RTSP server on port `8554`, stream URL `rtsp://<phone-ip>:8554/camera`.
- **H.264 / H.265** — hardware MediaCodec encoding with selectable resolution, frame rate and bitrate. H.264 is the default; H.265 appears when the device exposes a hardware HEVC encoder.
- **TCP and UDP clients** — RTP/AVP/TCP interleaved and RTP/AVP UDP, with multiple simultaneous clients.
- **Optional Basic authentication** — username/password protection for the stream.
- **Background streaming** — a foreground service keeps the camera, encoder and RTSP server alive when the app is in the background.
- **Network address management** — lists local IPv4 addresses for Wi-Fi, USB tethering and other interfaces, with copyable RTSP URLs.
- **Multi-lens selection** — choose any Camera2 camera ID (main, ultra-wide, front, etc.) from the lens button. The Settings page lists the selected camera's recording capabilities.
- **Camera controls** — torch, tap-to-focus, pinch zoom and +/− zoom buttons.
- **Manual mode** — ISO, exposure time, manual focus and white-balance presets. Switching from Auto to Manual starts from the current auto exposure, ISO, focus and white balance, so the image does not jump.
- **Camera-style UI** — portrait full-frame preview, floating controls and a compact segmented Auto/Manual pill.
- **Localized UI** — English and Simplified Chinese, with a structure ready for more languages.
- **Optimized release build** — R8 minification, resource shrinking and debug-log stripping.

## Download

Grab the latest universal APK from [Releases](https://github.com/akkako/RemCam/releases/latest). It contains native libraries for both `arm64-v8a` and `armeabi-v7a`, so it works on modern 64-bit phones as well as older 32-bit ARM devices.

> CI builds are signed with the debug keystore. For production distribution, configure your own signing key.

## Requirements

| Item | Requirement |
| --- | --- |
| Android | 8.0 (API 26) or newer |
| Compile SDK | API 37 |
| JDK | Java 17 |
| Build tools | Gradle 9.6.0, Android Gradle Plugin 9.4.0 |
| Kotlin / Compose | Kotlin 2.2.10, Compose BOM 2026.02.01 |

## Build

Create `local.properties` in the project root (already ignored by Git):

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

### Debug APK

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or build and install in one step:

```bash
./gradlew :app:installDebug
```

### Release APK

```bash
./gradlew :app:assembleRelease
adb install -r "$(find app/build/outputs/apk/release -maxdepth 1 -name '*.apk' | head -n 1)"
```

The release build enables R8 code shrinking/obfuscation, resource shrinking and PNG optimization. `Log.v/d/i` calls are removed; warnings and errors are kept. The APK is not debuggable and is roughly one tenth the size of the debug build.

### Release signing

By default, release builds are signed with the Android debug keystore so the APK can be installed immediately. This is convenient for testing, but it has important consequences:

- The debug key is not secret; anyone who has it can sign an APK that Android accepts as an update.
- A debug keystore generated on another machine or CI run produces a different signature. Android will refuse to update an app signed with another key; users must uninstall first.
- Google Play and most distribution channels require a proper release key.

To use your own key, generate a keystore and create `keystore.properties` in the project root. Both files are ignored by Git:

```bash
keytool -genkeypair -v \
  -keystore remcam-release.jks \
  -alias remcam \
  -keyalg RSA -keysize 2048 -validity 10000
```

`keystore.properties`:

```properties
storeFile=remcam-release.jks
storePassword=...
keyAlias=remcam
keyPassword=...
```

Build as usual. `app/build.gradle.kts` automatically uses this configuration when all four fields are present; otherwise it falls back to the debug key.

> Switching from a debug-signed install to a release-signed install requires uninstalling the old app once. After that, every build signed with the same release key can update in place. The GitHub Actions workflow still uses debug signing by default; to sign CI builds with your release key, provide the keystore and credentials as repository secrets and write `keystore.properties` before the build step.

### GitHub Actions

The workflow at `.github/workflows/android-release.yml` builds a universal release APK on every push to `main`, every `v*` tag and manual dispatch.

- The APK is uploaded as a `RemCam-release-<ref>` artifact.
- Every build verifies that both `arm64-v8a` and `armeabi-v7a` are present.
- A `v*` tag also creates a GitHub Release and attaches `RemCam-<tag>-universal.apk`.

## Usage

For a complete step-by-step walkthrough, see the [User Guide](USER_GUIDE.md).

1. Launch the app and grant camera permission. Android 13+ also asks for notification permission; Android 17+ asks for local-network permission.
2. Open Settings with the gear icon. Choose the camera, codec, resolution, frame rate, bitrate and RTSP port.
3. Tap the center shutter to start streaming. The status pill switches to `Live` and the RTSP URL appears briefly at the top.
4. Open the URL from another device on the same network:

```bash
ffplay -rtsp_transport tcp rtsp://192.168.1.10:8554/camera
```

In VLC: **Media → Open Network Stream**, then enter the same URL.

With Basic authentication enabled, include the credentials in the URL:

```text
rtsp://username:password@192.168.1.10:8554/camera
```

Tap the shutter again to stop. You can also press Home or lock the screen while streaming; the foreground service keeps the camera and stream running. The notification offers a Stop action.

## Settings

- **RTSP port** — default `8554`, range 1–65535.
- **Video codec** — H.264 or H.265. H.265 is only offered when a hardware HEVC encoder exists.
- **Resolution** — 16:9 sizes reported by the selected camera.
- **Frame rate** — filtered by the selected resolution and the camera's capabilities.
- **Bitrate** — 4–250 Mbps; the Settings page warns when the value exceeds the encoder's maximum.
- **Basic authentication** — optional username and password.
- **Refresh network** — refreshes the list of local IPv4 addresses (Wi-Fi, USB tethering, etc.).
- **Camera** — selected camera label, ID, focal length, maximum resolution, supported resolutions and frame rates.
- **Camera capabilities** — manual ISO/exposure, manual focus, white-balance presets, flash and digital zoom.

Streaming settings take effect on the next Start. Manual values can be adjusted live.

## UI Overview

- **Top-left** — camera/lens selection button.
- **Top-center** — status pill (`Ready / Live / Starting / Stopping / Error`) with camera and resolution.
- **Top-right** — torch (when available) and Settings.
- **Bottom-left** — Auto/Manual segmented pill, the same size as the zoom pill.
- **Bottom-center** — shutter button.
- **Bottom-right** — digital zoom pill.
- The Auto/Manual and zoom pills are arranged symmetrically around the shutter.

In Manual mode, a rounded control card expands above the bottom bar with parameter chips and a slider, plus Reset and Hide actions.

The UI is portrait-only by design. The RTSP stream carries the camera sensor's native orientation (usually landscape); the portrait preview is the app's viewfinder. Rotate on the client if a different orientation is required.

## Manual Mode

- When you switch from Auto to Manual, RemCam snapshots the current auto exposure, ISO, lens focus distance and white-balance mode, then applies them in a single capture request. The first manual frame therefore matches the auto image instead of jumping to defaults.
- Manual focus is limited to the selected camera's reported range: `0` (infinity) up to `LENS_INFO_MINIMUM_FOCUS_DISTANCE`. No distance is hard-coded, so each camera uses its own physical capabilities.
- Invalid focus readings such as `-1` while autofocus is idle are ignored; the last valid lens position is retained.
- White balance includes an **Auto** option, so manual exposure/ISO/focus can be combined with automatic white balance.
- If a device rejects a manual request, the app falls back to automatic capture and shows an error banner.

## Performance

- The encoder reuses one access-unit buffer instead of allocating a large `ByteArray` per frame.
- NAL units are parsed by offset without copying; TCP batches all RTP packets of a frame into one buffer and performs a single socket write/flush.
- UDP reuses its packet buffer, and sockets use a 512 KB send buffer to absorb keyframe bursts.
- The release build uses R8 and resource shrinking; verbose/debug/info logs are stripped.

## Project Structure

```text
remcam/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/remcam/
│       │   ├── RemCamActivity.kt          # Activity, portrait lock, edge-to-edge
│       │   ├── RemCamScreen.kt            # Compose camera UI, preview, gestures
│       │   ├── RemCamStreamService.kt     # Foreground streaming service
│       │   ├── RemCamViewModel.kt         # UI state, settings persistence, lifecycle
│       │   ├── StreamModels.kt            # UI/camera/manual models
│       │   ├── streaming/
│       │   │   ├── RemCamEngine.kt        # Application-scoped camera/stream engine
│       │   │   ├── CameraStreamManager.kt # Camera2 sessions, MediaCodec, capture controls
│       │   │   ├── CameraCapabilities.kt  # Camera, resolution and codec capabilities
│       │   │   └── RtspServer.kt          # RTSP/RTP server (TCP/UDP, Basic Auth)
│       │   └── ui/                        # Compose controls, theme, settings sheet
│       └── res/                           # Strings, locales, icons
├── .github/workflows/android-release.yml  # CI release build
├── LICENSE
├── README.md
├── README.zh-CN.md
├── USER_GUIDE.md
├── USER_GUIDE.zh-CN.md
└── rem.png
```

## Troubleshooting

- **Port already in use** — choose another RTSP port or stop the process using it.
- **No local network address** — connect Wi-Fi or enable USB tethering, then tap Refresh network.
- **Manual mode rejected** — some HALs reject manual AE/AF combinations. The app automatically falls back to Auto and retries once.
- **H.265 will not play** — make sure the client supports HEVC. VLC and ffmpeg usually do; some TVs and mobile players do not.
- Check the stream with:

```bash
ffprobe -v error -rtsp_transport tcp \
  -show_entries stream=codec_name,width,height,r_frame_rate \
  -of default=nw=1 rtsp://<phone-ip>:8554/camera
```

- Follow app logs:

```bash
adb logcat -s REM_CAM_ENGINE VIDEO_ENCODER RTSP_SERVER CLIENT_CONNECTION
```

## Known Limitations

- The RTSP server exposes one video track (`/camera`) with no audio and no RTSP over TLS.
- Basic-auth credentials are stored in the app-private `SharedPreferences` without additional encryption.
- Preview and streaming field-of-view matching depends on the Camera2/HAL implementation; results can vary between devices and camera HALs.
- Preview-only mode uses an extra same-size `ImageReader` output to keep the sensor mode consistent with the streaming session; it is released while streaming.
- H.265 requires a hardware HEVC encoder and a client that can decode HEVC.

## License

Licensed under the [Apache License 2.0](LICENSE).

Copyright 2026 akako
