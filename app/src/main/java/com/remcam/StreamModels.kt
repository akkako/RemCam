package com.remcam

import android.hardware.camera2.CaptureRequest
import androidx.annotation.StringRes

enum class StreamStatus {
    NOT_STREAMING,
    STARTING,
    STOPPING,
    STREAMING,
    ERROR
}

enum class ManualParameter {
    ISO,
    EXPOSURE,
    FOCUS,
    WHITE_BALANCE
}

data class ResolutionPreset(
    val width: Int,
    val height: Int
) {
    val label: String get() = "$width x $height"
    val aspectRatioLabel: String get() = aspectRatioLabel(width, height)
}

enum class FpsPreset(
    val label: String,
    val fps: Int
) {
    FPS_15("15 fps", 15),
    FPS_24("24 fps", 24),
    FPS_30("30 fps", 30),
    FPS_60("60 fps", 60)
}

enum class BitratePreset(
    val label: String,
    val bitrateMbps: Int,
    val bitrate: Int
) {
    MBPS_4("4 Mbps", 4, 4_000_000),
    MBPS_6("6 Mbps", 6, 6_000_000),
    MBPS_8("8 Mbps", 8, 8_000_000),
    MBPS_10("10 Mbps", 10, 10_000_000),
    MBPS_12("12 Mbps", 12, 12_000_000),
    MBPS_16("16 Mbps", 16, 16_000_000),
    MBPS_20("20 Mbps", 20, 20_000_000),
    MBPS_25("25 Mbps", 25, 25_000_000),
    MBPS_30("30 Mbps", 30, 30_000_000),
    MBPS_40("40 Mbps", 40, 40_000_000),
    MBPS_50("50 Mbps", 50, 50_000_000),
    MBPS_60("60 Mbps", 60, 60_000_000),
    MBPS_80("80 Mbps", 80, 80_000_000),
    MBPS_100("100 Mbps", 100, 100_000_000),
    MBPS_120("120 Mbps", 120, 120_000_000),
    MBPS_150("150 Mbps", 150, 150_000_000),
    MBPS_200("200 Mbps", 200, 200_000_000),
    MBPS_250("250 Mbps", 250, 250_000_000)
}

data class LocalAddress(
    val address: String,
    val interfaceName: String,
    val label: String
)

/**
 * Hardware capabilities that make the manual panel possible.
 *
 * The whole object is only present when the camera supports AE OFF plus
 * exposure-time and sensitivity ranges. Focus and white balance remain
 * optional inside it so unsupported rows can simply be hidden.
 */
data class ManualCapabilities(
    val exposureTimeRangeNs: LongRange,
    val sensitivityRange: IntRange,
    val maxFocusDistanceDiopters: Float?,
    val whiteBalancePresets: List<WhiteBalancePreset>
) {
    val supportsFocus: Boolean get() = (maxFocusDistanceDiopters ?: 0f) > 0f
    val supportsWhiteBalance: Boolean get() = whiteBalancePresets.isNotEmpty()
}

/**
 * Discrete white-balance presets backed by CONTROL_AWB_MODE values.
 * No continuous Kelvin or color-gain implementation by design.
 */
data class WhiteBalancePreset(
    @StringRes val labelRes: Int,
    val awbMode: Int
)

val manualWhiteBalancePresets: List<WhiteBalancePreset> = listOf(
    WhiteBalancePreset(R.string.white_balance_auto, CaptureRequest.CONTROL_AWB_MODE_AUTO),
    WhiteBalancePreset(R.string.wb_incandescent, CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT),
    WhiteBalancePreset(R.string.wb_warm_fluorescent, CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT),
    WhiteBalancePreset(R.string.wb_fluorescent, CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT),
    WhiteBalancePreset(R.string.wb_daylight, CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT),
    WhiteBalancePreset(R.string.wb_cloudy, CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT),
    WhiteBalancePreset(R.string.wb_twilight, CaptureRequest.CONTROL_AWB_MODE_TWILIGHT),
    WhiteBalancePreset(R.string.wb_shade, CaptureRequest.CONTROL_AWB_MODE_SHADE)
)

data class ManualControlValues(
    val exposureTimeNs: Long = 16_666_667L,
    val iso: Int = 400,
    val focusDistanceDiopters: Float = 0f,
    val awbMode: Int? = null
)

enum class VideoCodec(
    val mimeType: String,
    val sdpName: String,
    @StringRes val labelRes: Int
) {
    H264("video/avc", "H264", R.string.codec_h264),
    H265("video/hevc", "H265", R.string.codec_h265)
}

data class VideoCodecOption(
    val codec: VideoCodec,
    val hardware: Boolean,
    val maxBitrateMbps: Int? = null
)

data class EncodedParameterSets(
    val codec: VideoCodec,
    val vps: ByteArray? = null,
    val sps: ByteArray,
    val pps: ByteArray
)

data class CameraOption(
    val id: String,
    val lensFacing: Int,
    val label: String,
    val detail: String = ""
)

data class RemCamUiState(
    val hasCameraPermission: Boolean = false,
    val cameraReady: Boolean = false,
    val streamStatus: StreamStatus = StreamStatus.NOT_STREAMING,
    val errorMessage: String? = null,
    val selectedCameraId: String = "",
    val availableCameras: List<CameraOption> = emptyList(),
    val sensorOrientation: Int = 90,
    val selectedResolution: ResolutionPreset = ResolutionPreset(1280, 720),
    val selectedFps: FpsPreset = FpsPreset.FPS_30,
    val selectedBitrate: BitratePreset = BitratePreset.MBPS_6,
    val selectedCodec: VideoCodec = VideoCodec.H264,
    val availableCodecs: List<VideoCodecOption> = emptyList(),
    val availableResolutions: List<ResolutionPreset> = emptyList(),
    val availableFpsOptions: List<FpsPreset> = listOf(FpsPreset.FPS_30),
    val encoderMaxBitrateMbps: Int? = null,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0,
    val manualCapabilities: ManualCapabilities? = null,
    val manualMode: Boolean = false,
    val manualPanelVisible: Boolean = true,
    val manualValues: ManualControlValues = ManualControlValues(),
    val flashAvailable: Boolean = false,
    val torchEnabled: Boolean = false,
    val maxZoom: Float = 1f,
    val zoom: Float = 1f,
    val tapToFocusSupported: Boolean = false,
    val rtspAuthEnabled: Boolean = false,
    val rtspUsername: String = "",
    val rtspPassword: String = "",
    val portInput: String = "8554",
    val localAddresses: List<LocalAddress> = emptyList(),
    val localIpAddress: String? = null,
    val streamUrl: String = ""
) {
    val isStreaming: Boolean get() = streamStatus == StreamStatus.STREAMING
    val isStarting: Boolean get() = streamStatus == StreamStatus.STARTING
    val isStopping: Boolean get() = streamStatus == StreamStatus.STOPPING
    val isBusy: Boolean get() = isStarting || isStopping
    val hasLocalAddress: Boolean get() = localAddresses.isNotEmpty()
    val selectedCodecOption: VideoCodecOption?
        get() = availableCodecs.firstOrNull { it.codec == selectedCodec }
    val supportsManual: Boolean get() = manualCapabilities != null
    val supportsFocus: Boolean get() = manualCapabilities?.supportsFocus == true
    val supportsWhiteBalance: Boolean get() = manualCapabilities?.supportsWhiteBalance == true
    val lensLabel: String get() =
        availableCameras.firstOrNull { it.id == selectedCameraId }?.label.orEmpty()

    val selectedCameraDetail: String get() =
        availableCameras.firstOrNull { it.id == selectedCameraId }?.detail.orEmpty()
}

private fun aspectRatioLabel(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "?"
    val divisor = gcd(width, height)
    return "${width / divisor}:${height / divisor}"
}

/**
 * Manual exposure must stay strictly below SENSOR_FRAME_DURATION. Keeping a
 * 1 ms margin avoids HAL rejection when exposure would equal the frame
 * duration (e.g., exactly 1/30 s at 30 fps).
 */
fun manualExposureMaxNs(sensorMaxNs: Long, fps: Int): Long {
    val frameDurationNs = 1_000_000_000L / fps.coerceAtLeast(1)
    val safeMax = (frameDurationNs - 1_000_000L).coerceAtLeast(1_000_000L)
    return minOf(sensorMaxNs, safeMax)
}

/** Balanced starting point for the manual panel (1/60 s, ISO 200). */
const val MANUAL_DEFAULT_EXPOSURE_NS = 16_666_667L
const val MANUAL_DEFAULT_ISO = 200

private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
