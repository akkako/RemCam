**English** | [简体中文](USER_GUIDE.zh-CN.md)

# RemCam User Guide

This guide explains how to install, configure and use RemCam as a local RTSP camera. It covers first-time setup, the camera-style controls, manual mode, client playback and troubleshooting.

## Contents

- [Before you start](#before-you-start)
- [Install RemCam](#install-remcam)
- [First launch and permissions](#first-launch-and-permissions)
- [Screen overview](#screen-overview)
- [Network setup](#network-setup)
- [Start and stop streaming](#start-and-stop-streaming)
- [Connect a client](#connect-a-client)
- [Settings reference](#settings-reference)
- [Camera controls](#camera-controls)
- [Manual mode](#manual-mode)
- [Tips for reliable streaming](#tips-for-reliable-streaming)
- [Troubleshooting](#troubleshooting)
- [FAQ](#faq)
- [Security notes](#security-notes)
- [Quick reference](#quick-reference)
- [How it works](#how-it-works)
- [Related documents](#related-documents)

## Before you start

You need:

- An Android phone running Android 8.0 (API 26) or newer.
- A client on the same local network: VLC, ffplay, GStreamer, or another RTSP player.
- For USB tethering, a USB cable and a PC that supports USB network sharing.

RemCam runs entirely on the phone. It does not need a cloud account, a relay server or root access.

## Install RemCam

1. Download the latest universal APK from [Releases](https://github.com/akkako/RemCam/releases/latest).
2. Open the APK on the phone. Android may ask you to allow installation from the current file manager or browser; enable it for this installation.
3. Install and launch RemCam.

The universal APK contains both `arm64-v8a` and `armeabi-v7a`, so it works on 64-bit and 32-bit ARM phones.

If you are updating an existing installation, the new APK must be signed with the same key. By default, release builds use the Android debug keystore: it is convenient for testing, but it is not secret, and a debug keystore generated on another machine or CI run produces a different signature. Switching between a locally built APK and a CI APK may therefore require uninstalling first, which clears the app's saved settings. For a private, stable key, see **Release signing** in the [README](README.md).

## First launch and permissions

1. Grant **Camera** permission. Without it the preview cannot start.
2. On Android 17+, grant **Local network** permission so the RTSP server can accept connections.
3. The first time you start streaming on Android 13+, grant **Notifications** permission so the foreground-service notification can be shown. Streaming can start even if you deny it, but the notification and its Stop action will not be visible.

The app opens in **Auto** mode with the camera preview running. You can configure it before starting the stream.

## Screen overview

- **Top-left** — camera/lens selection button.
- **Top-center** — status pill: `Ready`, `Live`, `Starting`, `Stopping` or `Error`, followed by the current camera and resolution.
- **Top-right** — torch (if the camera has a flash) and Settings.
- **Bottom-left** — Auto/Manual segmented pill.
- **Bottom-center** — shutter button. Yellow play icon means stopped; red stop icon means live.
- **Bottom-right** — digital zoom pill: `−`, current zoom, `+`.
- **Above the bottom bar (Manual mode)** — ISO, Exposure, Focus and White balance controls, plus Reset and Hide.

## Network setup

The phone and the client must be able to reach each other.

### Wi-Fi

Connect the phone and the client to the same Wi-Fi network. Guest networks and access-point isolation can block device-to-device traffic even when both are connected.

### USB tethering

1. Connect the phone to the PC with a USB cable.
2. Enable **USB tethering** on the phone.
3. Open RemCam → **Settings**. The RTSP card lists the USB network interface (for example `rndis0`) and its IP address.
4. Use that address on the PC. The phone's Wi-Fi address is a different interface and may not be reachable from the PC.

### Find and copy the address

Settings → **RTSP streaming** lists every local IPv4 address with its interface and a copy button. Tap **Refresh network** after changing networks or enabling tethering.

The default RTSP URL is:

```text
rtsp://<phone-ip>:8554/camera
```

The port can be changed in Settings.

## Start and stop streaming

1. Choose a camera, codec, resolution, frame rate and bitrate in Settings.
2. Tap the center shutter.
3. Wait for the status pill to show **Live**. Encoder and capture-session setup can take a few seconds, especially at high resolution.
4. The RTSP URL appears briefly at the top; use its copy button if needed.
5. Tap the shutter again to stop. The status returns to **Ready**.

While streaming you can press Home or lock the screen. A foreground service keeps the camera, encoder and RTSP server running. The notification provides a **Stop** action. Return to the app to reattach the preview.

If startup fails, an error banner appears at the top. Dismiss it and check [Troubleshooting](#troubleshooting).

## Connect a client

### ffplay

```bash
ffplay -rtsp_transport tcp rtsp://192.168.1.10:8554/camera
```

### VLC

Open **Media → Open Network Stream** and enter the same URL.

### ffprobe

```bash
ffprobe -v error -rtsp_transport tcp \
  -show_entries stream=codec_name,width,height,r_frame_rate \
  -of default=nw=1 rtsp://192.168.1.10:8554/camera
```

### TCP or UDP

RemCam accepts both RTP/AVP/TCP interleaved and RTP/AVP UDP. TCP is more reliable on busy or lossy networks; UDP can have lower latency. If a client supports transport selection, start with TCP.

### Multiple clients

Several clients can connect at the same time. Each client receives its own RTP stream, so the required network bandwidth and phone CPU load scale with the number of viewers.

### Basic authentication

Enable **Basic authentication** in Settings and set a username and password. Clients that connect without credentials receive `401 Unauthorized`. Use the credentials in the URL:

```text
rtsp://username:password@192.168.1.10:8554/camera
```

### H.264 and H.265

H.264 works with nearly every RTSP client. H.265 (HEVC) requires both a hardware HEVC encoder on the phone and an HEVC-capable client. If playback fails or shows only a black screen with H.265, switch the codec to H.264 and start the stream again.

## Settings reference

| Setting | Description |
| --- | --- |
| **RTSP port** | Server port, default `8554`, range 1–65535. |
| **Video codec** | H.264 (default) or H.265 when a hardware HEVC encoder exists. |
| **Resolution** | Supported 16:9 sizes reported by the selected camera. Higher sizes need more bandwidth and produce more heat. |
| **Frame rate** | Options filtered by the selected resolution and the camera's capabilities. |
| **Bitrate** | 4–250 Mbps. The page warns when the value exceeds the encoder's reported maximum. |
| **Basic authentication** | Optional username/password protection. |
| **Refresh network** | Re-reads local IPv4 addresses. |
| **Camera** | Current camera label, Camera2 ID, focal length, maximum resolution and supported formats. |
| **Camera capabilities** | Manual ISO/exposure, manual focus, white-balance presets, flash and digital zoom. |
| **About** | App version and author information. |

Streaming settings are applied on the next Start. Manual adjustments can be changed live.

On a fresh install the defaults are H.264, 1280×720 (or the nearest supported size), 30 fps, 6 Mbps and port 8554.

## Camera controls

### Lens selection

Tap the lens button at the top-left to choose a camera. Each entry shows the camera label, focal length and maximum resolution. The selected camera's supported recording formats are shown in Settings.

Lens selection is disabled while streaming or while the camera is starting/stopping. Stop the stream before switching cameras.

### Torch

The torch button appears at the top-right when the selected camera has a flash. It can be toggled while the camera is ready or streaming.

### Tap to focus

In Auto mode, tap the preview to focus on a point. A focus ring appears at the tapped position. Tap-to-focus is unavailable in Manual mode and on cameras without autofocus or metering regions.

### Zoom

- Pinch the preview with two fingers.
- Use the `−` and `+` buttons in the zoom pill.
- Tap the zoom value (for example `1.0x`) to reset to 1.0x.

Zoom is digital and depends on the camera's reported maximum zoom ratio.

### Auto / Manual

The Auto/Manual pill is at the bottom-left. Manual is available only when the selected camera supports AE OFF with exposure/ISO ranges and the preview or stream is running. Tapping the already selected **Manual** segment hides or shows the manual panel.

## Manual mode

Switching from Auto to Manual captures the current auto exposure time, ISO, lens focus distance and white balance, then applies them in one capture request. The first manual frame therefore looks like the auto frame instead of jumping to defaults.

The manual panel has four parameter chips:

- **ISO** — sensitivity. Lower values reduce noise; higher values brighten dark scenes.
- **Exposure** — shutter/exposure time. Longer exposures brighten the image but can blur motion.
- **Focus** — manual lens focus. The slider runs from `∞` to the closest distance reported by the camera.
- **White balance** — `Auto` or a fixed preset (incandescent, fluorescent, daylight, cloudy, etc.).

**Reset** resets the currently selected parameter. The arrow button hides the panel; tap the Manual segment again to reopen it.

### Manual focus range

The focus range comes from the selected camera's `LENS_INFO_MINIMUM_FOCUS_DISTANCE`. The app does not hard-code a distance, so each lens uses its real physical range. If a subject is closer than the camera's minimum focus distance, use Auto focus or choose a different lens from the lens menu.

### Fallback behavior

Some camera HALs reject manual exposure/focus combinations. In that case RemCam switches back to automatic capture, shows an error/warning banner and keeps streaming. Manual controls also stay disabled on cameras that do not expose the required AE OFF and ISO/exposure ranges.

## Tips for reliable streaming

- Start with **1280×720, 30 fps, 4–8 Mbps, H.264** and increase only if the network and client can handle it.
- For 1080p, 10–20 Mbps is a practical starting range. 4K needs a fast link and a capable encoder.
- Prefer USB tethering or a 5 GHz Wi-Fi link for high bitrates.
- Use TCP if UDP playback stutters or shows missing-reference warnings.
- Use H.264 when compatibility matters; use H.265 when the client supports it and you want lower bandwidth.
- High resolution and bitrate increase phone heat and battery use. Keep the phone ventilated and charging if streaming for a long time.
- If background streaming is killed by an OEM battery manager, allow RemCam to run in the background / disable battery optimization for it.
- Keep the phone and client on the same subnet and avoid guest Wi-Fi or AP isolation.

## Troubleshooting

| Symptom | What to check |
| --- | --- |
| Camera preview is black or the app shows a camera error | Grant camera permission, close other camera apps, and retry. Some devices only allow one camera client at a time. |
| **No local network address** | Connect Wi-Fi or enable USB tethering, then tap **Refresh network**. |
| **Port already in use** | Another process is using the RTSP port. Change the port in Settings or stop the other process. |
| Client cannot connect | Confirm both devices are on the same network, use the correct IP for the active interface (USB vs Wi-Fi), check the port and any client/PC firewall. |
| Stream starts but the client shows no video | Try TCP, switch H.265 to H.264, and check `ffprobe` output. |
| Playback stutters or freezes | Lower resolution/bitrate, use TCP, move closer to the access point, or use USB tethering. |
| H.265 does not play | The client or TV does not support HEVC. Use H.264. |
| Manual mode is unavailable | The camera does not report AE OFF plus exposure/ISO ranges. Use Auto mode. |
| Manual mode falls back to Auto | The HAL rejected the manual request. Reduce the exposure/ISO combination or use Auto. |
| Manual focus cannot get closer | The subject is closer than the lens's physical minimum focus distance. Use Auto focus or a different lens. |
| Stream stops when the app is in the background | The OEM battery manager may have killed the foreground service. Allow background activity for RemCam. |
| Preview and RTSP framing differ slightly | Preview/stream sensor-mode matching depends on the Camera2 HAL. Stop and restart after changing resolution; results vary by device. |
| Installation fails with a signature error | The installed app and the new APK use different signing keys. Uninstall the old app (this clears settings), then install the new APK. |

## FAQ

**Does RemCam need internet access?**  
No. It serves the stream over the local network. Internet is only needed to download the APK.

**Can I watch the stream over the internet?**  
It is designed for a trusted LAN. There is no TLS and no cloud relay. If remote access is required, use a VPN instead of exposing the RTSP port directly.

**Can I record video or audio?**  
No. RemCam provides a live RTSP video track only; it does not record files and does not capture audio.

**Can multiple people watch at once?**  
Yes. Each client gets its own RTP stream. Bandwidth and phone load increase with the number of clients.

**Why is the RTSP image landscape while the phone preview is portrait?**  
The stream carries the camera sensor's native orientation, which is usually landscape; the portrait preview is the app's viewfinder. This is by design. Rotate the image in the client if you need a different orientation.

**Can I change the language?**  
The app supports English and Simplified Chinese. It follows the system language; on Android 13+ you can also set a per-app language in the system app settings.

**How do I reset all settings?**  
There is no global reset in the app. Manual controls have per-parameter Reset. Uninstalling and reinstalling the app clears saved settings.

**Where are settings stored?**  
In the app-private `SharedPreferences`. Basic-auth credentials are stored there without extra encryption.

## Security notes

- Use **Basic authentication** on shared or untrusted networks.
- Do not expose the RTSP port directly to the internet. Use a VPN for remote access.
- RTSP credentials and the stream are not encrypted in transit (no TLS support).
- The default release build is signed with the debug key and is intended for testing and personal use. Use your own release keystore for distribution; see **Release signing** in the [README](README.md).

## Quick reference

```text
Stream URL:        rtsp://<phone-ip>:8554/camera
Authenticated URL: rtsp://<username>:<password>@<phone-ip>:8554/camera

ffplay:  ffplay -rtsp_transport tcp rtsp://<phone-ip>:8554/camera
ffprobe: ffprobe -v error -rtsp_transport tcp \
           -show_entries stream=codec_name,width,height,r_frame_rate \
           -of default=nw=1 rtsp://<phone-ip>:8554/camera

Build debug:   ./gradlew :app:assembleDebug
Build release: ./gradlew :app:assembleRelease
```

## How it works

- Camera2 opens the selected camera and feeds a `SurfaceTexture` for preview and, while streaming, a MediaCodec input surface.
- MediaCodec encodes H.264 or H.265. The encoder output is split into NAL units and packetized as RTP.
- The built-in RTSP server publishes one video track at `/camera` over TCP interleaved or UDP.
- An application-scoped engine and a foreground service keep the camera, encoder and server alive when the UI goes to the background.
- In preview-only mode, an auxiliary `ImageReader` keeps the capture session's output configuration close to the streaming session so the field of view does not jump when Start is pressed.

## Related documents

- [README](README.md) — project overview, features, build and license.
- [Apache 2.0 License](LICENSE).
