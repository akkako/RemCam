package com.remcam.streaming

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaCodecList
import android.util.Size
import com.remcam.CameraOption
import com.remcam.R
import com.remcam.FpsPreset
import com.remcam.ManualCapabilities
import com.remcam.ResolutionPreset
import com.remcam.manualWhiteBalancePresets
import java.util.Locale

data class CameraCapabilities(
    val sensorOrientation: Int = 90,
    val maxDigitalZoom: Float = 1f,
    val resolutions: List<ResolutionPreset> = emptyList(),
    private val fpsByResolution: Map<ResolutionPreset, List<FpsPreset>> = emptyMap(),
    val manualCapabilities: ManualCapabilities? = null,
    val flashAvailable: Boolean = false,
    val tapToFocusSupported: Boolean = false
) {
    fun fpsOptionsFor(resolution: ResolutionPreset): List<FpsPreset> =
        fpsByResolution[resolution].orEmpty()

    companion object {
        fun empty(): CameraCapabilities = CameraCapabilities()
    }
}

class CameraCapabilityReader(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /**
     * Real camera ids exposed by Camera2, back first and in camera-service
     * order. Multiple rear lenses (main / ultrawide / telephoto) stay separate
     * instead of being collapsed to LENS_FACING_BACK.
     */
    fun availableCameras(): List<CameraOption> {
        return runCatching {
            val ids = cameraManager.cameraIdList?.toList().orEmpty()
            val raw = ids.mapNotNull { id ->
                val characteristics = runCatching {
                    cameraManager.getCameraCharacteristics(id)
                }.getOrNull() ?: return@mapNotNull null
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
                    ?: return@mapNotNull null
                val focal = characteristics
                    .get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull()
                val maxSize = characteristics
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(SurfaceTexture::class.java)
                    ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
                val detail = buildList {
                    focal?.let { add(String.format(Locale.US, "%.1f mm", it)) }
                    maxSize?.let { add("${it.width}×${it.height}") }
                }.joinToString(" · ")
                Triple(id, facing, detail)
            }.sortedWith(
                compareBy<Triple<String, Int, String>>(
                    { lensPriority(it.second) },
                    { ids.indexOf(it.first) }
                )
            )
            val backCount = raw.count { it.second == CameraCharacteristics.LENS_FACING_BACK }
            val frontCount = raw.count { it.second == CameraCharacteristics.LENS_FACING_FRONT }
            var backIndex = 0
            var frontIndex = 0
            raw.map { (id, facing, detail) ->
                val label = when (facing) {
                    CameraCharacteristics.LENS_FACING_BACK -> {
                        backIndex++
                        if (backCount > 1) {
                            appContext.getString(R.string.camera_back_numbered, backIndex)
                        } else {
                            appContext.getString(R.string.camera_back)
                        }
                    }

                    CameraCharacteristics.LENS_FACING_FRONT -> {
                        frontIndex++
                        if (frontCount > 1) {
                            appContext.getString(R.string.camera_front_numbered, frontIndex)
                        } else {
                            appContext.getString(R.string.camera_front)
                        }
                    }

                    else -> appContext.getString(R.string.camera_external)
                }
                CameraOption(id = id, lensFacing = facing, label = label, detail = detail)
            }
        }.getOrElse { emptyList() }
    }

    fun readCapabilities(cameraId: String): CameraCapabilities {
        return runCatching {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val configurationMap = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return@runCatching CameraCapabilities.empty()

            val encoderSizes = configurationMap.getOutputSizes(MediaCodec::class.java)
                ?.toList()
                .orEmpty()
            val previewSizes = configurationMap.getOutputSizes(SurfaceTexture::class.java)
                ?.toList()
                .orEmpty()
            val allSizes = (encoderSizes + previewSizes).distinct()

            val wideSizes = allSizes
                .filter { it.width >= 640 && it.width * 9 == it.height * 16 }
                .sortedByDescending { it.width }
            val candidateSizes = wideSizes.ifEmpty {
                allSizes.filter { it.width >= 640 }.sortedByDescending { it.width }
            }

            val resolutions = candidateSizes
                .map { ResolutionPreset(it.width, it.height) }
                .distinct()

            val fpsRanges = characteristics
                .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                .orEmpty()
                .toList()
            val fixedFpsRates = fpsRanges
                .filter { it.lower == it.upper }
                .map { it.upper }
                .toSet()

            val fpsByResolution = resolutions.associateWith { resolution ->
                val size = Size(resolution.width, resolution.height)
                val maxFps = maxOutputFps(configurationMap, size)
                val withinSensorLimit = FpsPreset.entries.filter {
                    it.fps <= maxFps + 0.5
                }
                val exactFixed = withinSensorLimit.filter { it.fps in fixedFpsRates }
                val insideAnyRange = withinSensorLimit.filter { preset ->
                    fpsRanges.any { preset.fps in it.lower..it.upper }
                }
                when {
                    exactFixed.isNotEmpty() -> exactFixed
                    fixedFpsRates.isEmpty() && insideAnyRange.isNotEmpty() -> insideAnyRange
                    insideAnyRange.isNotEmpty() -> insideAnyRange
                    withinSensorLimit.isNotEmpty() -> withinSensorLimit
                    else -> listOf(FpsPreset.FPS_30)
                }
            }

            CameraCapabilities(
                sensorOrientation = characteristics
                    .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                maxDigitalZoom = (
                    characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                        ?: 1f
                    ).coerceAtLeast(1f),
                resolutions = resolutions,
                fpsByResolution = fpsByResolution,
                manualCapabilities = readManualControlCapabilities(characteristics),
                flashAvailable = characteristics
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true,
                tapToFocusSupported = readTapToFocusSupported(characteristics)
            )
        }.getOrElse {
            CameraCapabilities.empty()
        }
    }

    fun readAvcEncoderMaxBitrateMbps(): Int? {
        return runCatching {
            val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val encoder = codecs.codecInfos.firstOrNull { info ->
                info.isEncoder && info.supportedTypes.any {
                    it.equals("video/avc", ignoreCase = true)
                }
            } ?: return@runCatching null
            encoder.getCapabilitiesForType("video/avc")
                .videoCapabilities
                ?.bitrateRange
                ?.upper
                ?.div(1_000_000)
        }.getOrNull()
    }

    private fun maxOutputFps(configurationMap: StreamConfigurationMap, size: Size): Double {
        val durationNs = runCatching {
            configurationMap.getOutputMinFrameDuration(MediaCodec::class.java, size)
        }.getOrDefault(0L).takeIf { it > 0 } ?: runCatching {
            configurationMap.getOutputMinFrameDuration(SurfaceTexture::class.java, size)
        }.getOrDefault(0L)
        return if (durationNs > 0) 1_000_000_000.0 / durationNs else 30.0
    }
}

private fun lensPriority(lensFacing: Int): Int = when (lensFacing) {
    CameraCharacteristics.LENS_FACING_BACK -> 0
    CameraCharacteristics.LENS_FACING_FRONT -> 1
    else -> 2
}

/**
 * Manual exposure/ISO requires AE OFF plus explicit ranges. Everything else
 * (focus, white balance) is optional so unsupported controls can be hidden.
 */
fun readManualControlCapabilities(
    characteristics: CameraCharacteristics
): ManualCapabilities? {
    val aeModes = characteristics
        .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        ?: return null
    if (!aeModes.contains(CaptureRequest.CONTROL_AE_MODE_OFF)) return null

    val exposureRange = characteristics
        .get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        ?: return null
    val sensitivityRange = characteristics
        .get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        ?: return null
    if (exposureRange.lower <= 0L || exposureRange.upper < exposureRange.lower) return null
    if (sensitivityRange.lower <= 0 || sensitivityRange.upper < sensitivityRange.lower) return null

    val afModes = characteristics
        .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        ?: intArrayOf()
    val minFocusDistance = characteristics
        .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
    val maxFocusDistance = minFocusDistance
        ?.takeIf { it > 0f && afModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF) }

    val awbModes = characteristics
        .get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        ?: intArrayOf()
    val supportedWhiteBalancePresets = manualWhiteBalancePresets.filter {
        awbModes.contains(it.awbMode)
    }

    return ManualCapabilities(
        exposureTimeRangeNs = exposureRange.lower..exposureRange.upper,
        sensitivityRange = sensitivityRange.lower..sensitivityRange.upper,
        maxFocusDistanceDiopters = maxFocusDistance,
        whiteBalancePresets = supportedWhiteBalancePresets
    )
}

/** Best continuous-auto AF mode supported by this camera, or null to leave it alone. */
fun readAutoAfMode(characteristics: CameraCharacteristics): Int? {
    val modes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        ?: return null
    return when {
        modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ->
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO

        modes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE

        modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
            CaptureRequest.CONTROL_AF_MODE_AUTO

        else -> null
    }
}

/** Auto white balance mode if the camera exposes it, otherwise null. */
fun readAutoAwbMode(characteristics: CameraCharacteristics): Int? {
    val modes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
        ?: return null
    return if (modes.contains(CaptureRequest.CONTROL_AWB_MODE_AUTO)) {
        CaptureRequest.CONTROL_AWB_MODE_AUTO
    } else {
        null
    }
}

/**
 * Tap-to-focus needs an AF lens with a real focus range, at least one AF
 * metering region and one of the auto/continuous AF modes.
 */
fun readTapToFocusSupported(characteristics: CameraCharacteristics): Boolean {
    val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        ?: intArrayOf()
    val hasAutoMode = afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ||
        afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ||
        afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    val minimumFocusDistance = characteristics
        .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        ?: 0f
    val afRegions = characteristics
        .get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
        ?: 0
    return hasAutoMode && minimumFocusDistance > 0f && afRegions > 0
}
