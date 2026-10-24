[English](README.md) | **简体中文**

<p align="right">
  <img src="rem.png" alt="RemCam" width="150">
</p>

# RemCam

[![Build Release APK](https://github.com/akkako/RemCam/actions/workflows/android-release.yml/badge.svg)](https://github.com/akkako/RemCam/actions/workflows/android-release.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

RemCam 可以把一台 Android 手机变成局域网 RTSP 摄像头。它使用 Camera2 采集画面，通过 MediaCodec 编码 H.264 或 H.265，并在手机本地提供 RTSP/RTP 服务。VLC、ffplay、GStreamer 等 RTSP 客户端可以直接拉流，无需云端中转。

第一次使用？请先阅读[使用说明](USER_GUIDE.zh-CN.md)。

## 功能

- **本地 RTSP 串流** — 内置 RTSP 服务器，默认端口 `8554`，流地址为 `rtsp://<手机IP>:8554/camera`。
- **H.264 / H.265** — MediaCodec 硬件编码，支持选择分辨率、帧率和码率。默认 H.264；设备提供硬件 HEVC 编码器时可切换 H.265。
- **TCP 与 UDP 客户端** — 支持 RTP/AVP/TCP interleaved 和 RTP/AVP UDP，可同时连接多个客户端。
- **可选 Basic 认证** — 为串流设置用户名和密码。
- **后台串流** — 通过前台服务保活；按 Home 或锁屏后，相机、编码器和 RTSP 服务继续运行。
- **网络地址管理** — 列出 Wi-Fi、USB 网络共享等本地 IPv4 地址，并提供可复制的 RTSP URL。
- **多镜头选择** — 通过镜头按钮选择任意 Camera2 相机 ID（主摄、超广角、前摄等）。设置页会列出当前相机支持的录制参数。
- **相机控制** — 手电筒、点击对焦、双指缩放和 +/− 变焦按钮。
- **手动模式** — ISO、曝光时间、手动对焦和白平衡预设。从自动切换到手动时，以当前自动曝光、ISO、对焦和白平衡为起点，画面不会跳变。
- **相机风格 UI** — 竖屏全屏预览、浮层控件和紧凑的 Auto/Manual 分段胶囊。
- **界面本地化** — 支持英文和简体中文，结构上可继续添加其他语言。
- **优化的发布构建** — R8 代码压缩/混淆、资源收缩，并移除调试日志。

## 下载

从 [Releases](https://github.com/akkako/RemCam/releases/latest) 下载最新的通用 APK。它同时包含 `arm64-v8a` 和 `armeabi-v7a` 原生库，兼容现代 64 位手机和较老的 32 位 ARM 设备。

> CI 构建使用 debug keystore 签名。正式分发前请配置自己的签名密钥。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Android | 8.0（API 26）及以上 |
| 编译 SDK | API 37 |
| JDK | Java 17 |
| 构建工具 | Gradle 9.6.0，Android Gradle Plugin 9.4.0 |
| Kotlin / Compose | Kotlin 2.2.10，Compose BOM 2026.02.01 |

## 构建

在项目根目录创建 `local.properties`（该文件已被 Git 忽略）：

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

### Debug APK

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

也可以一步完成构建和安装：

```bash
./gradlew :app:installDebug
```

### Release APK

```bash
./gradlew :app:assembleRelease
adb install -r "$(find app/build/outputs/apk/release -maxdepth 1 -name '*.apk' | head -n 1)"
```

Release 构建会开启 R8 代码压缩/混淆、资源收缩和 PNG 优化。`Log.v/d/i` 调用会被移除，警告和错误日志保留。APK 不含 `debuggable` 标志，体积约为 Debug 版的十分之一。

### Release 签名

默认情况下，Release 构建使用 Android debug keystore 签名，方便直接安装测试。但这会带来几个重要影响：

- debug 密钥不是秘密；任何持有该密钥的人都可以签名一个 Android 认可的更新包。
- 在其他机器或 CI 上生成的 debug keystore 签名不同，Android 会拒绝覆盖安装；用户必须先卸载。
- Google Play 和大多数分发渠道要求使用正式的 release 密钥。

要使用自己的密钥，先生成 keystore，然后在项目根目录创建 `keystore.properties`（两个文件都已被 Git 忽略）：

```bash
keytool -genkeypair -v \
  -keystore remcam-release.jks \
  -alias remcam \
  -keyalg RSA -keysize 2048 -validity 10000
```

`keystore.properties`：

```properties
storeFile=remcam-release.jks
storePassword=...
keyAlias=remcam
keyPassword=...
```

正常构建即可。四个字段齐全时，`app/build.gradle.kts` 会自动使用该配置；否则回退到 debug 密钥。

> 从 debug 签名安装切换到 release 签名安装时，需要先卸载旧应用一次。之后使用同一 release 密钥签名的版本可以正常覆盖升级。GitHub Actions 工作流默认仍使用 debug 签名；如需 CI 使用 release 密钥，可把 keystore 和密码配置为仓库 secrets，并在构建步骤前生成 `keystore.properties`。

### GitHub Actions

工作流位于 `.github/workflows/android-release.yml`，会在以下场景构建通用 Release APK：

- 推送到 `main`
- 推送 `v*` 标签
- 在 Actions 页面手动触发

构建结果：

- APK 以 `RemCam-release-<ref>` 的名称上传为 Actions artifact。
- 每次构建都会校验 `arm64-v8a` 和 `armeabi-v7a` 是否都存在。
- 推送 `v*` 标签时，会自动创建 GitHub Release 并附加 `RemCam-<tag>-universal.apk`。

## 使用方法

完整的分步说明见[使用说明](USER_GUIDE.zh-CN.md)。

1. 启动应用并授予相机权限。Android 13+ 还会请求通知权限；Android 17+ 会请求本地网络权限。
2. 点击右上角齿轮进入设置，选择相机、编码、分辨率、帧率、码率和 RTSP 端口。
3. 点击底部中央快门开始串流。状态胶囊变为 `Live`，顶部会短暂显示可复制的 RTSP 地址。
4. 在同一局域网内的设备上打开该地址：

```bash
ffplay -rtsp_transport tcp rtsp://192.168.1.10:8554/camera
```

VLC：**媒体 → 打开网络串流**，输入同一地址。

启用 Basic 认证后，URL 中需要包含用户名和密码：

```text
rtsp://username:password@192.168.1.10:8554/camera
```

再次点击快门停止串流。串流过程中也可以按 Home 或锁屏，前台服务会保持相机和串流运行；通知栏提供 Stop 操作。

## 设置项

- **RTSP port** — 默认 `8554`，范围 1–65535。
- **Video codec** — H.264 或 H.265。只有设备存在硬件 HEVC 编码器时才会提供 H.265。
- **Resolution** — 当前相机支持的 16:9 分辨率。
- **Frame rate** — 根据所选分辨率和相机能力过滤。
- **Bitrate** — 4–250 Mbps；超过编码器上限时设置页会给出警告。
- **Basic authentication** — 可选的用户名和密码。
- **Refresh network** — 刷新本地 IPv4 地址列表（Wi-Fi、USB 网络共享等）。
- **Camera** — 当前相机名称、ID、焦距、最大分辨率、支持的分辨率和帧率。
- **Camera capabilities** — 是否支持手动 ISO/曝光、手动对焦、白平衡预设、闪光灯和数字变焦。

串流参数在下次点击 Start 时生效；手动参数可以实时调整。

## UI 说明

- **左上角** — 相机/镜头选择按钮。
- **顶部中间** — 状态胶囊（`Ready / Live / Starting / Stopping / Error`）和当前相机/分辨率。
- **右上角** — 手电筒（有闪光灯时）和设置入口。
- **左下角** — Auto/Manual 分段胶囊，尺寸与数码变焦胶囊一致。
- **底部中间** — 快门按钮。
- **右下角** — 数码变焦胶囊。
- Auto/Manual 胶囊与变焦胶囊相对快门左右对称。

手动模式下，底部上方会展开圆角控制卡片，包含参数 chip、滑杆、Reset 和收起按钮。

界面固定竖屏是设计选择。RTSP 流使用相机传感器的原始方向（通常为横向）；竖屏预览是应用的取景器。需要其他方向时可在客户端旋转画面。

## 手动模式

- 从自动切换到手动时，RemCam 会记录当前自动曝光、ISO、镜头对焦距离和白平衡模式，并通过一次 capture request 应用，因此第一帧手动画面与自动画面一致，不会跳到默认值。
- 手动对焦范围严格使用当前相机上报的能力值：从 `0`（无穷远）到 `LENS_INFO_MINIMUM_FOCUS_DISTANCE`。代码中没有硬编码距离，每台设备使用自己的物理能力。
- 自动对焦空闲时部分设备会返回 `-1` 等非法对焦距离；应用会忽略这些值并保留上一个有效镜头位置。
- 白平衡提供 **自动** 选项，因此可以在手动曝光/ISO/对焦的同时保持自动白平衡。
- 如果设备拒绝手动请求，应用会自动回退到自动拍摄，并显示错误横幅。

## 性能

- 编码器复用同一个访问单元缓冲区，避免每帧分配大块 `ByteArray`。
- NAL 单元按偏移量直接解析，不做复制；TCP 将一帧的所有 RTP 包拼进一个缓冲区，只执行一次 socket 写入和 flush。
- UDP 复用包缓冲区；socket 使用 512 KB 发送缓冲，缓解关键帧突发时的阻塞。
- Release 构建启用 R8 和资源收缩，并剥离详细/调试/信息级日志。

## 项目结构

```text
remcam/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/remcam/
│       │   ├── RemCamActivity.kt          # Activity，竖屏锁定 + edge-to-edge
│       │   ├── RemCamScreen.kt            # Compose 相机 UI、预览、手势
│       │   ├── RemCamStreamService.kt     # 串流前台服务
│       │   ├── RemCamViewModel.kt         # UI 状态、设置持久化、生命周期
│       │   ├── StreamModels.kt            # UI/相机/手动控制模型
│       │   ├── streaming/
│       │   │   ├── RemCamEngine.kt        # 应用级相机/串流引擎
│       │   │   ├── CameraStreamManager.kt # Camera2 会话、MediaCodec、拍摄控制
│       │   │   ├── CameraCapabilities.kt  # 相机、分辨率与编码能力读取
│       │   │   └── RtspServer.kt          # RTSP/RTP 服务（TCP/UDP、Basic Auth）
│       │   └── ui/                        # Compose 控件、主题、设置面板
│       └── res/                           # 字符串、语言配置、图标
├── .github/workflows/android-release.yml  # CI 发布构建
├── LICENSE
├── README.md
├── README.zh-CN.md
├── USER_GUIDE.md
├── USER_GUIDE.zh-CN.md
└── rem.png
```

## 故障排查

- **端口被占用** — 更换 RTSP 端口，或关闭占用端口的进程。
- **没有本地网络地址** — 连接 Wi-Fi 或开启 USB 网络共享，然后点击 Refresh network。
- **手动模式被拒绝** — 部分 HAL 会拒绝手动 AE/AF 组合；应用会自动回退到 Auto 并重试一次。
- **H.265 无法播放** — 确认客户端支持 HEVC。VLC 和 ffmpeg 通常支持；部分电视和移动播放器不支持。
- 检查流信息：

```bash
ffprobe -v error -rtsp_transport tcp \
  -show_entries stream=codec_name,width,height,r_frame_rate \
  -of default=nw=1 rtsp://<手机IP>:8554/camera
```

- 查看应用日志：

```bash
adb logcat -s REM_CAM_ENGINE VIDEO_ENCODER RTSP_SERVER CLIENT_CONNECTION
```

## 已知限制

- RTSP 服务只提供一路视频轨道（`/camera`），不含音频，也不支持 RTSP over TLS。
- Basic 认证凭据保存在应用私有 `SharedPreferences` 中，未额外加密。
- 预览与串流的取景一致性依赖设备的 Camera2/HAL 实现；不同机型和相机 HAL 可能存在差异。
- 预览模式会额外占用一路同尺寸 `ImageReader` 输出，以保持与串流会话相同的传感器模式；串流时会释放。
- H.265 需要设备具备硬件 HEVC 编码器，且客户端能够解码 HEVC。

## 许可证

本项目基于 [Apache License 2.0](LICENSE) 开源。

Copyright 2026 akako
