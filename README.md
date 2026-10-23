# RemCam

RemCam 是一个把 Android 手机变成局域网 RTSP 摄像头的应用：使用 Camera2 采集画面，MediaCodec 编码 H.264，在手机本地启动 RTSP/RTP 服务，局域网内的播放器（VLC、ffplay、GStreamer 等）可以直接拉流。

## 功能

- **本地 RTSP 串流**：内置轻量 RTSP 服务器，默认端口 `8554`，流地址为 `rtsp://<手机IP>:8554/camera`。
- **H.264 编码**：MediaCodec 硬件编码，支持分辨率、帧率、码率选择。
- **TCP / UDP 客户端**：支持 RTP/AVP/TCP interleaved 和 RTP/AVP UDP，多个客户端可同时连接。
- **可选 Basic 认证**：设置用户名/密码后，客户端需要认证才能拉流。
- **后台串流**：前台服务保活；按 Home、锁屏后相机、编码器和 RTSP 服务继续运行。
- **相机控制**：前后摄切换、手电筒、点击对焦（自动模式）、双指缩放、+/- 变焦。
- **手动模式**：ISO、曝光时间、手动对焦、白平衡预设；支持实时调整。
- **相机风格 UI**：竖屏全屏预览、浮层控件、底部 Auto/Manual 模式胶囊与居中快门。
- **网络地址管理**：列出 Wi-Fi / USB tethering 等本地 IPv4 地址，可刷新、复制 RTSP URL。

## 环境要求

| 项目 | 要求 |
| --- | --- |
| Android | 8.0（API 26）及以上 |
| 编译 SDK | API 37 |
| JDK | Java 17 |
| 构建工具 | Gradle Wrapper 9.6.0，Android Gradle Plugin 9.4.0 |
| Kotlin / Compose | Kotlin 2.2.10，Compose BOM 2026.02.01 |

## 构建与安装

在项目根目录创建 `local.properties`（该文件已被 `.gitignore` 忽略）：

```properties
sdk.dir=C\:\\path\\to\\Android\\Sdk
```

构建 Debug APK：

```bash
./gradlew :app:assembleDebug
```

安装到已连接的设备：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

或直接运行：

```bash
./gradlew :app:installDebug
```

> Release 构建目前复用 debug 签名配置（见 `app/build.gradle.kts`）。正式发布前请替换为自己的签名。

## 使用方法

1. 启动应用，授予相机权限；Android 13+ 会请求通知权限，Android 17+ 会请求本地网络权限。
2. 右上角齿轮进入设置，选择分辨率、帧率、码率、RTSP 端口，按需开启 Basic 认证。
3. 点击底部中央快门开始串流；状态胶囊显示 `Live`，顶部会出现可复制的 RTSP 地址。
4. 局域网内用客户端打开：

```bash
ffplay -rtsp_transport tcp rtsp://192.168.1.10:8554/camera
```

VLC：媒体 → 打开网络串流，输入同一地址。

带认证的地址格式：

```text
rtsp://username:password@192.168.1.10:8554/camera
```

再次点击快门停止串流。串流过程中按 Home 键或锁屏，应用会通过前台服务继续保持相机、编码器和 RTSP 服务运行；通知栏提供 Stop 操作。

## 设置项

- **RTSP port**：默认 `8554`，范围 1–65535。
- **Resolution**：相机支持的 16:9 分辨率列表，默认 `1280 x 720`。
- **Frame rate**：按分辨率和相机能力过滤，默认 30 fps。
- **Bitrate**：4–250 Mbps；高于编码器上限时设置页会给出警告。
- **Basic authentication**：用户名/密码，可选；启用后未认证客户端会收到 401。
- **Refresh network**：刷新本地 IPv4 地址列表。
- **Camera capabilities**：显示是否支持手动 ISO/曝光、手动对焦、白平衡预设、闪光灯和数字变焦。

串流参数在下次点击 Start 时生效；手动模式参数可实时调整。

## UI 说明

- 竖屏锁定，全屏显示相机画面。
- 顶部：手电筒（有闪光灯时）、状态胶囊（`Ready / Live / Starting / Stopping / Error` + 镜头/分辨率）、设置入口。
- 底部为浮层控件：`Auto / Manual` 紧凑模式胶囊、切换相机、居中快门、变焦胶囊。
- 开始串流后顶部短暂显示 RTSP URL 横幅，可一键复制。
- 手动模式会展开圆角手动控制卡片：参数 chip + 滑杆，支持 Reset 和收起。

## 项目结构

```text
remcam/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/remcam/
│       │   ├── RemCamActivity.kt          # Activity，竖屏锁定 + edge-to-edge
│       │   ├── RemCamScreen.kt            # Compose 相机 UI、预览层、权限、对焦/缩放手势
│       │   ├── RemCamStreamService.kt     # 串流前台服务
│       │   ├── RemCamViewModel.kt         # UI 状态、设置持久化、预览生命周期
│       │   ├── StreamModels.kt            # UI 状态与相机/手动控制模型
│       │   ├── streaming/
│       │   │   ├── RemCamEngine.kt        # 应用级相机/串流引擎（单例）
│       │   │   ├── CameraStreamManager.kt # Camera2、CaptureSession、MediaCodec、手动控制
│       │   │   ├── CameraCapabilities.kt  # 相机能力与分辨率/FPS 读取
│       │   │   └── RtspServer.kt          # RTSP/RTP 服务（TCP/UDP、Basic Auth）
│       │   └── ui/
│       │       ├── components/
│       │       │   ├── Controls.kt        # 快门、变焦、状态点等
│       │       │   ├── ManualControlPanel.kt
│       │       │   └── SettingsSheet.kt
│       │       └── theme/
│       └── res/
├── gradle/wrapper/
├── build.gradle.kts
├── settings.gradle.kts
├── gradlew / gradlew.bat
├── .gitignore
└── README.md
```

## 架构与数据流

```text
Compose UI (RemCamScreen)
        │  collectAsState
RemCamViewModel ── SharedPreferences（端口/分辨率/帧率/码率/认证等）
        │
        ▼
RemCamEngine（Application 级单例，StateFlow）
        │
        ├── CameraStreamManager ── Camera2 + MediaCodec(H.264) + 手动/对焦/变焦/手电筒
        └── RtspServer ── RTSP 信令 + RTP 封包（FU-A）+ SDP
        │
RemCamStreamService（前台服务，保持进程与相机存活）
```

### 会话模式

- **预览模式**：相机输出到预览 `TextureView`，同时挂一路同尺寸的 `ImageReader` 占位输出。两路输出的配置与串流模式一致，避免 Camera2 为单路/双路会话选择不同的 sensor mode 导致取景范围跳变；占位帧会立即丢弃。
- **串流模式**：预览 + `MediaCodec` 输入 Surface 两路输出；启动 RTSP 服务并等待编码器产出 SPS/PPS 后进入 `STREAMING`。
- **后台串流**：UI 不可见时释放预览 Surface，仅保留编码器输出，串流不中断；回到前台重建带预览的会话。
- 进入/退出串流时会重新创建 CaptureSession，重配置期间界面显示 `Starting / Stopping`。

### 预览显示

- Activity 固定竖屏；预览视图按真实相机缓冲尺寸布局，避免 `TextureView` 把视图尺寸写回 SurfaceTexture 导致取流尺寸变化。
- SurfaceTexture 自带传感器方向旋转；Compose 图层再按预览框/缓冲尺寸做非等比缩放，抵消旋转引入的拉伸，完整显示相机画面（contain，不裁剪）。
- 对焦坐标按预览框坐标换算回原始缓冲坐标。
- 串流与预览使用同一分辨率、同一输出数量，并在请求中显式关闭 EIS/OIS，保证 Ready 与 Live 的取景一致。

## 日志与排障

```bash
adb logcat -s REM_CAM_ENGINE VIDEO_ENCODER RTSP_SERVER CLIENT_CONNECTION
```

- **Port in use**：更换 RTSP 端口，或关闭占用端口的进程。
- **No local network address**：连接 Wi-Fi 或开启 USB tethering 后点击 Refresh network。
- **手动设置导致启动超时**：部分设备的 HAL 会拒绝手动 AE 组合；应用会自动回退到 Auto 并重试一次，顶部显示错误横幅。
- 检查流信息：

```bash
ffprobe -v error -rtsp_transport tcp \
  -show_entries stream=codec_name,width,height,r_frame_rate \
  -of default=nw=1 rtsp://<手机IP>:8554/camera
```

## 已知限制

- 界面固定竖屏；横放手机时 RTSP 输出的是传感器原始横向画面，屏幕预览则是该画面的竖屏取景。
- 串流过程中不允许切换镜头（避免重建编码会话）；停止后可切换。
- 手动模式在部分设备上可能被 HAL 拒绝，会自动回退到自动曝光/对焦。
- 预览与串流取景一致性依赖设备的 Camera2/HAL 行为；当前实现已在 Pixel 3 XL 上验证。若移植到其他机型出现 FOV 差异，可进一步读取并补偿 SurfaceTexture 变换矩阵。
- RTSP 服务仅提供 `/camera` 单路 H.264 视频，不含音频；不支持 RTSP over TLS。
- Basic 认证凭据保存在应用私有 `SharedPreferences` 中，未额外加密。
- 预览模式会额外占用一路同尺寸 ImageReader 输出（不编码、不写盘），串流模式下会释放。

## License

本项目暂未指定开源许可证。
