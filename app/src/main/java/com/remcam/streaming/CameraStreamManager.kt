package com.remcam.streaming

import android.annotation.SuppressLint
import androidx.annotation.StringRes
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import com.remcam.BitratePreset
import com.remcam.EncodedParameterSets
import com.remcam.R
import com.remcam.VideoCodec
import com.remcam.FpsPreset
import com.remcam.ManualCapabilities
import com.remcam.ManualControlValues
import com.remcam.ResolutionPreset
import com.remcam.manualExposureMaxNs
import java.net.BindException
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the Camera2 device, the preview-only / streaming capture sessions, the
 * H.264 encoder and the RTSP server.
 *
 * Design notes:
 *  - The camera preview is opened as soon as the screen is visible, like a
 *    native camera app. The RTSP server is only started by [startStreaming].
 *  - All manual/zoom/torch/AF updates submit a new repeating request; the
 *    capture session is never recreated for those changes.
 *  - While streaming, the preview surface is optional. When the app goes to
 *    the background the preview surface is released and only the encoder
 *    surface remains in the capture session, keeping camera, encoder, RTSP
 *    server and stream alive. Returning to the foreground rebuilds the
 *    session with preview + encoder.
 *  - Every optional capability is checked and request failures fall back to
 *    fully automatic capture instead of crashing.
 */
class CameraStreamManager(context: Context) {

    interface Listener {
        fun onManualControlsFailed(message: String)
        fun onCameraRequestFailed(message: String)
        fun onCameraDisconnected(message: String)
    }

    var listener: Listener? = null

    private val appContext = context.applicationContext
    private val cameraManager =
        appContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("RemCamCamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()

    @Volatile
    private var textureView: TextureView? = null
    private var surfaceReady = CompletableDeferred<Unit>()

    @Volatile
    private var previewSuspended = false

    @Volatile
    private var cameraDevice: CameraDevice? = null

    @Volatile
    private var captureSession: CameraCaptureSession? = null

    @Volatile
    private var previewSurface: Surface? = null

    @Volatile
    private var videoSurface: Surface? = null

    /**
     * Extra camera output used only in preview mode so the HAL picks the same
     * sensor mode / crop as the preview + encoder streaming session. Without
     * it, preview-only uses a narrower sensor mode and the FOV jumps on Start.
     */
    private var previewAuxReader: ImageReader? = null
    private var mediaCodec: MediaCodec? = null
    private var encoderJob: Job? = null
    private var rtspServer: RtspServer? = null

    @Volatile
    private var isStreaming = false

    // Capture-session shape tracking (guarded by stateMutex).
    private var sessionHasEncoder = false
    private var sessionHasPreview = false
    private var sessionPreviewGeneration = -1L
    private var previewGeneration = 0L

    /** Camera currently opened by CameraDevice. */
    private var activeCameraId: String? = null

    /** Camera requested by the UI / stream; may differ while switching. */
    @Volatile
    private var requestedCameraId: String? = null

    @Volatile
    private var activeStreamSize = Size(1280, 720)

    @Volatile
    private var activePreviewSize = Size(1280, 720)

    @Volatile
    private var activeFps: Int = 30

    @Volatile
    private var activeSensorOrientation: Int = 90

    private var sensorRect: Rect? = null
    private var baseCropRect: Rect? = null
    private var activeFpsRange: Range<Int>? = null

    @Volatile
    private var maxZoomRatio: Float = 1f

    @Volatile
    private var currentZoomRatio: Float = 1f

    @Volatile
    private var manualCapabilities: ManualCapabilities? = null

    @Volatile
    private var manualControlsEnabled = false

    @Volatile
    private var manualValues = ManualControlValues()

    @Volatile
    private var flashAvailable = false

    @Volatile
    private var torchEnabled = false

    private var autoAfMode: Int? = null
    private var autoAwbMode: Int? = null

    // Tap-to-focus state. All of it is in sensor coordinates.
    @Volatile
    private var autoFocusRegion: MeteringRectangle? = null

    @Volatile
    private var autoFocusAeRegion: MeteringRectangle? = null

    @Volatile
    private var tapAfMode: Int? = null

    @Volatile
    private var supportsTapToFocus = false

    @Volatile
    private var maxAfRegions = 0

    @Volatile
    private var maxAeRegions = 0

    private val submitRunnable = Runnable { submitRepeatingRequestNow(allowFallback = true) }

    fun attachPreview(view: TextureView) {
        if (textureView === view) {
            if (view.isAvailable) resumePreview()
            return
        }
        textureView = view
        surfaceReady = CompletableDeferred()

        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) {
                if (!surfaceReady.isCompleted) surfaceReady.complete(Unit)
                if (!previewSuspended) resumePreview()
            }

            override fun onSurfaceTextureSizeChanged(
                surface: SurfaceTexture,
                width: Int,
                height: Int
            ) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                val hadPreviewInSession = sessionHasPreview
                previewGeneration++
                runCatching { previewSurface?.release() }
                previewSurface = null
                surfaceReady = CompletableDeferred()
                if (hadPreviewInSession) {
                    // The old session points at a dead buffer queue; close it
                    // before it starts logging errors and rebuild without preview.
                    runCatching { captureSession?.close() }
                    captureSession = null
                    sessionHasPreview = false
                }
                if (isStreaming) {
                    scope.launch {
                        stateMutex.withLock { rebuildSessionLocked() }
                    }
                }
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }
        if (view.isAvailable) {
            if (!surfaceReady.isCompleted) surfaceReady.complete(Unit)
            if (!previewSuspended) resumePreview()
        }
    }

    fun detachPreview(view: TextureView) {
        if (textureView !== view) return
        view.surfaceTextureListener = null
        textureView = null
        surfaceReady = CompletableDeferred()
        scope.launch {
            stateMutex.withLock {
                releasePreviewSurfaceLocked()
                if (isStreaming) {
                    rebuildSessionLocked()
                } else {
                    runCatching { captureSession?.close() }
                    captureSession = null
                    closeCameraInternal()
                }
            }
        }
    }

    /**
     * Detaches the preview because the UI went to the background. While
     * streaming the camera, encoder and RTSP server stay alive; the capture
     * session is rebuilt with the encoder surface only.
     */
    fun suspendPreview() {
        previewSuspended = true
        scope.launch {
            stateMutex.withLock {
                if (sessionHasPreview) {
                    runCatching { captureSession?.close() }
                    captureSession = null
                    sessionHasPreview = false
                }
                releasePreviewSurfaceLocked()
                if (isStreaming) {
                    rebuildSessionLocked()
                }
            }
        }
    }

    /** Re-attaches the preview after the UI returns to the foreground. */
    fun resumePreview() {
        previewSuspended = false
        scope.launch {
            stateMutex.withLock {
                if (isStreaming || cameraDevice != null) {
                    rebuildSessionLocked()
                }
            }
        }
    }

    fun isCameraOpen(): Boolean = cameraDevice != null

    fun getPreviewWidth(): Int = activePreviewSize.width

    fun getPreviewHeight(): Int = activePreviewSize.height

    fun getCurrentZoomRatio(): Float = currentZoomRatio

    fun getMaxZoomRatio(): Float = maxZoomRatio

    fun setZoom(ratio: Float): Float {
        currentZoomRatio = ratio.coerceIn(1f, maxZoomRatio.coerceAtLeast(1f))
        submitRepeatingRequest()
        return currentZoomRatio
    }

    fun scaleZoomBy(scaleFactor: Float): Float {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return currentZoomRatio
        val clampedScale = scaleFactor.coerceIn(0.5f, 2f)
        return setZoom(currentZoomRatio * clampedScale)
    }

    fun resetZoom(): Float = setZoom(1f)

    fun setTorch(enabled: Boolean) {
        torchEnabled = enabled
        submitRepeatingRequest()
    }

    fun setManualControlsEnabled(enabled: Boolean) {
        manualControlsEnabled = enabled
        submitRepeatingRequest()
    }

    fun updateManualControls(values: ManualControlValues) {
        manualValues = values
        if (manualControlsEnabled) submitRepeatingRequest()
    }

    /**
     * Sets a one-shot AF/AE metering region from normalized preview-buffer
     * coordinates (0..1) and triggers an AF scan. Only valid while the camera
     * is in Auto mode and the lens supports AF with metering regions.
     */
    fun focusAtBufferPoint(normalizedX: Float, normalizedY: Float): Boolean {
        if (!supportsTapToFocus || manualControlsEnabled) return false
        if (normalizedX !in 0f..1f || normalizedY !in 0f..1f) return false
        if (cameraDevice == null || captureSession == null) return false
        val crop = computeZoomCrop() ?: sensorRect ?: return false
        if (crop.width() <= 0 || crop.height() <= 0) return false

        val centerX = crop.left + (normalizedX * crop.width()).roundToInt()
        val centerY = crop.top + (normalizedY * crop.height()).roundToInt()
        val regionWidth = (crop.width() * 0.10f).roundToInt().coerceAtLeast(20)
            .coerceAtMost(crop.width())
        val regionHeight = (crop.height() * 0.10f).roundToInt().coerceAtLeast(20)
            .coerceAtMost(crop.height())
        val left = (centerX - regionWidth / 2)
            .coerceIn(crop.left, (crop.right - regionWidth).coerceAtLeast(crop.left))
        val top = (centerY - regionHeight / 2)
            .coerceIn(crop.top, (crop.bottom - regionHeight).coerceAtLeast(crop.top))
        val region = MeteringRectangle(
            Rect(left, top, left + regionWidth, top + regionHeight),
            MeteringRectangle.METERING_WEIGHT_MAX
        )
        cameraHandler.post {
            autoFocusRegion = region
            autoFocusAeRegion = if (maxAeRegions > 0) region else null
            triggerTapFocusNow()
        }
        return true
    }

    private fun triggerTapFocusNow() {
        val session = captureSession ?: return
        if (manualControlsEnabled || !supportsTapToFocus) return
        val builder = buildRequest() ?: return
        try {
            if (tapAfMode == CaptureRequest.CONTROL_AF_MODE_AUTO) {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                session.capture(builder.build(), null, cameraHandler)
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            }
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
            Log.i(TAG_VIDEO_ENCODER, "Tap-to-focus triggered at region ${autoFocusRegion?.rect}")
        } catch (error: Exception) {
            Log.w(TAG_VIDEO_ENCODER, "Tap-to-focus request failed: ${error.message}", error)
        }
    }

    /** Opens the camera for preview only (no encoder, no RTSP server). */
    suspend fun openPreview(
        cameraId: String,
        fps: Int,
        resolution: ResolutionPreset
    ) {
        stateMutex.withLock {
            if (isStreaming) return
            if (cameraId !in cameraManager.cameraIdList) {
                throw IllegalStateException(str(R.string.error_requested_camera_unavailable))
            }
            val configurationMap = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException(str(R.string.error_camera_config_unavailable))
            val requestedPreviewSize = chooseEncoderSize(
                configurationMap,
                Size(resolution.width, resolution.height)
            )
            if (requestedCameraId == cameraId &&
                activeCameraId == cameraId &&
                cameraDevice != null &&
                captureSession != null &&
                activeStreamSize == requestedPreviewSize
            ) {
                return
            }
            if (requestedCameraId != cameraId) {
                torchEnabled = false
                currentZoomRatio = 1f
                clearAutoFocus()
            }
            requestedCameraId = cameraId
            activeFps = fps.coerceAtLeast(1)
            // Preview-only uses the same stream size as RTSP, so the framing
            // does not change when Start is pressed.
            activeStreamSize = requestedPreviewSize
            stopStreamingInternal()
            openCameraAndStartSession(includeEncoder = false)
        }
    }

    suspend fun startStreaming(
        cameraId: String,
        resolution: ResolutionPreset,
        fps: FpsPreset,
        bitrate: BitratePreset,
        codec: VideoCodec,
        port: Int,
        hostAddress: String,
        authEnabled: Boolean,
        authUsername: String,
        authPassword: String
    ): String {
        return stateMutex.withLock {
            if (requestedCameraId != cameraId) clearAutoFocus()
            requestedCameraId = cameraId
            activeFps = fps.fps
            stopStreamingInternal()

            val selectedCameraId = cameraId.takeIf { it in cameraManager.cameraIdList }
                ?: throw IllegalStateException(str(R.string.error_requested_camera_unavailable))
            val characteristics = cameraManager.getCameraCharacteristics(selectedCameraId)
            val configurationMap = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: throw IllegalStateException(str(R.string.error_camera_config_unavailable))

            manualCapabilities = readManualControlCapabilities(characteristics)
            activeSensorOrientation = characteristics
                .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            flashAvailable = characteristics
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!flashAvailable) torchEnabled = false
            sensorRect = characteristics
                .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            maxZoomRatio = (
                characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                    ?: 1f
                ).coerceAtLeast(1f)
            readAutoFocusCapabilities(characteristics)

            activeStreamSize = chooseEncoderSize(
                configurationMap,
                Size(resolution.width, resolution.height)
            )
            activePreviewSize = choosePreviewSize(configurationMap, activeStreamSize)
            baseCropRect = sensorRect?.let { sensor ->
                computeBaseCropRect(sensor, activeStreamSize.width, activeStreamSize.height)
            }
            activeFpsRange = chooseFpsRange(
                characteristics,
                activeFps,
                activeStreamSize,
                configurationMap
            )
            currentZoomRatio = currentZoomRatio.coerceIn(1f, maxZoomRatio)

            var attempt = 0
            while (true) {
                val server = RtspServer(
                    port = port,
                    hostAddress = hostAddress,
                    streamPath = "/camera",
                    authEnabled = authEnabled,
                    authUsername = authUsername,
                    authPassword = authPassword
                )

                val formatReady = CompletableDeferred<Unit>()
                try {
                    server.start()
                    rtspServer = server
                    Log.i(
                        TAG_VIDEO_ENCODER,
                        "Preparing encoder for ${activeStreamSize.width}x${activeStreamSize.height} " +
                            "@ ${activeFps}fps bitrate=${bitrate.bitrate} codec=${codec.sdpName} manual=$manualControlsEnabled"
                    )
                    prepareEncoder(
                        codec = codec,
                        width = activeStreamSize.width,
                        height = activeStreamSize.height,
                        fps = activeFps,
                        bitrate = bitrate.bitrate,
                        onFormatReady = { sets ->
                            server.updateStreamInfo(
                                codec = sets.codec,
                                width = activeStreamSize.width,
                                height = activeStreamSize.height,
                                fps = activeFps,
                                vps = sets.vps,
                                sps = sets.sps,
                                pps = sets.pps
                            )
                            Log.i(
                                TAG_VIDEO_ENCODER,
                                "Encoder output format ready; ${sets.codec.sdpName} parameter sets published"
                            )
                            if (!formatReady.isCompleted) formatReady.complete(Unit)
                        },
                        onEncodedData = { accessUnit, ptsUs, isKeyFrame ->
                            server.dispatchSample(accessUnit, ptsUs, isKeyFrame)
                        }
                    )
                    isStreaming = true
                    openCameraAndStartSession(includeEncoder = true)
                    withTimeout(if (activeStreamSize.width >= 2560) 15_000 else 8_000) {
                        formatReady.await()
                    }
                    Log.i(TAG_VIDEO_ENCODER, "Encoder and RTSP server started successfully")
                    break
                } catch (error: BindException) {
                    Log.e(TAG_VIDEO_ENCODER, "RTSP port $port is already in use", error)
                    isStreaming = false
                    runCatching { stopStreamingInternal() }
                    runCatching { openCameraAndStartSession(includeEncoder = false) }
                    throw IllegalStateException(str(R.string.error_port_in_use, port))
                } catch (error: Exception) {
                    Log.e(TAG_VIDEO_ENCODER, "Failed to start encoder/stream: ${error.message}", error)
                    isStreaming = false
                    runCatching { stopStreamingInternal() }

                    if (attempt == 0 &&
                        manualControlsEnabled &&
                        error is TimeoutCancellationException
                    ) {
                        // A rejected/stalled manual request must never leave the
                        // app unusable. Drop to fully automatic capture and retry
                        // once inside the same Start action.
                        attempt++
                        manualControlsEnabled = false
                        Log.w(
                            TAG_VIDEO_ENCODER,
                            "Manual controls stalled the stream; retrying with automatic capture"
                        )
                        listener?.onManualControlsFailed(
                            str(R.string.error_manual_fallback)
                        )
                        continue
                    }

                    runCatching { openCameraAndStartSession(includeEncoder = false) }
                    throw IllegalStateException(buildStartFailureMessage(error), error)
                }
            }

            "rtsp://$hostAddress:$port/camera"
        }
    }

    /**
     * Stops encoding/server. When [keepPreview] is true and the preview is
     * usable, the camera stays open with a preview-only session; otherwise the
     * camera is released too (used when stopping from the background).
     */
    suspend fun stopStreaming(keepPreview: Boolean = true) {
        stateMutex.withLock {
            val wasStreaming = isStreaming
            torchEnabled = false
            isStreaming = false
            stopStreamingInternal()
            val canKeepPreview = keepPreview &&
                !previewSuspended &&
                textureView?.isAvailable == true
            if (wasStreaming) {
                if (canKeepPreview) {
                    runCatching { openCameraAndStartSession(includeEncoder = false) }
                        .onFailure {
                            Log.w(TAG_VIDEO_ENCODER, "Could not restore preview: ${it.message}")
                            closeCameraInternal()
                        }
                } else {
                    closeCameraInternal()
                }
            } else if (!keepPreview) {
                closeCameraInternal()
            }
        }
    }

    /** Releases the camera entirely and resets transient preview state. */
    suspend fun closeCamera() {
        stateMutex.withLock {
            torchEnabled = false
            isStreaming = false
            stopStreamingInternal()
            closeCameraInternal()
            clearAutoFocus()
            resetZoomState()
        }
    }

    private suspend fun stopStreamingInternal() {
        isStreaming = false
        runCatching { captureSession?.close() }
        captureSession = null
        sessionHasEncoder = false
        sessionHasPreview = false
        sessionPreviewGeneration = -1L
        releasePreviewAuxReader()

        rtspServer?.stop()
        rtspServer = null

        encoderJob?.cancelAndJoin()
        encoderJob = null

        runCatching { mediaCodec?.stop() }
        runCatching { mediaCodec?.release() }
        mediaCodec = null

        runCatching { videoSurface?.release() }
        videoSurface = null

        releasePreviewSurfaceLocked()
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCameraAndStartSession(includeEncoder: Boolean) {
        if (!includeEncoder) awaitPreviewSurface()

        if (includeEncoder && videoSurface == null) {
            throw IllegalStateException(str(R.string.error_encoder_surface_unavailable))
        }

        val selectedCameraId = requestedCameraId
            ?: throw IllegalStateException(str(R.string.error_no_camera_selected))

        if (activeCameraId != selectedCameraId) {
            closeCameraInternal()
            activeCameraId = selectedCameraId
            clearAutoFocus()
        }

        val characteristics = cameraManager.getCameraCharacteristics(selectedCameraId)
        val configurationMap = characteristics
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: throw IllegalStateException(str(R.string.error_camera_config_unavailable))

        manualCapabilities = readManualControlCapabilities(characteristics)
        activeSensorOrientation = characteristics
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        flashAvailable = characteristics
            .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        if (!flashAvailable) torchEnabled = false
        sensorRect = characteristics
            .get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        maxZoomRatio = (
            characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
                ?: 1f
            ).coerceAtLeast(1f)
        autoAfMode = readAutoAfMode(characteristics)
        autoAwbMode = readAutoAwbMode(characteristics)
        readAutoFocusCapabilities(characteristics)

        activeStreamSize = chooseEncoderSize(configurationMap, activeStreamSize)
        activePreviewSize = choosePreviewSize(configurationMap, activeStreamSize)
        baseCropRect = sensorRect?.let { sensor ->
            computeBaseCropRect(sensor, activeStreamSize.width, activeStreamSize.height)
        }
        activeFpsRange = chooseFpsRange(
            characteristics,
            activeFps,
            activeStreamSize,
            configurationMap
        )
        currentZoomRatio = currentZoomRatio.coerceIn(1f, maxZoomRatio)

        val device = cameraDevice ?: openCameraDevice(selectedCameraId)
        cameraDevice = device

        val hasPreview = ensurePreviewSurfaceLocked()
        if (!includeEncoder && !hasPreview) {
            throw IllegalStateException(str(R.string.error_preview_surface_not_ready))
        }

        // Match the streaming session's output count so the HAL keeps the
        // same sensor mode / field of view before and during streaming.
        val auxSurface = if (!includeEncoder) {
            ensurePreviewAuxSurface(activeStreamSize)
        } else {
            releasePreviewAuxReader()
            null
        }

        val surfaces = buildList {
            if (hasPreview) {
                add(previewSurface ?: throw IllegalStateException(str(R.string.error_preview_surface_unavailable)))
            }
            if (includeEncoder) {
                add(videoSurface ?: throw IllegalStateException(str(R.string.error_encoder_surface_unavailable)))
            } else {
                auxSurface?.let { add(it) }
            }
        }
        if (surfaces.isEmpty()) {
            throw IllegalStateException(str(R.string.error_no_output_surfaces))
        }

        runCatching { captureSession?.close() }
        captureSession = createCaptureSession(device, surfaces)
        sessionHasEncoder = includeEncoder
        sessionHasPreview = hasPreview
        sessionPreviewGeneration = previewGeneration

        submitRepeatingRequestNow(allowFallback = true)
        Log.i(
            TAG_VIDEO_ENCODER,
            "Capture session started (encoder=$includeEncoder preview=$hasPreview) " +
                "${activeStreamSize.width}x${activeStreamSize.height} " +
                "previewBuffer=${activePreviewSize.width}x${activePreviewSize.height} " +
                "sensorRotation=$activeSensorOrientation zoom=$currentZoomRatio " +
                "tapAf=$supportsTapToFocus"
        )
    }

    /**
     * Rebuilds the capture session for the current preview availability.
     * Must be called with [stateMutex] held. While streaming the encoder
     * surface is always kept, so the RTSP stream survives preview loss.
     */
    private suspend fun rebuildSessionLocked() {
        val device = cameraDevice ?: return
        val includeEncoder = isStreaming
        val usePreview = !previewSuspended && ensurePreviewSurfaceLocked()
        val preview = if (usePreview) previewSurface else null
        if (!includeEncoder && preview == null) {
            runCatching { captureSession?.close() }
            captureSession = null
            sessionHasEncoder = false
            sessionHasPreview = false
            sessionPreviewGeneration = -1L
            return
        }
        if (captureSession != null &&
            sessionHasEncoder == includeEncoder &&
            sessionHasPreview == (preview != null) &&
            sessionPreviewGeneration == previewGeneration
        ) {
            return
        }
        val video = videoSurface
        if (includeEncoder && video == null) return

        runCatching { captureSession?.close() }
        captureSession = null

        val auxSurface = if (!includeEncoder) {
            ensurePreviewAuxSurface(activeStreamSize)
        } else {
            releasePreviewAuxReader()
            null
        }
        val surfaces = buildList {
            preview?.let { add(it) }
            if (includeEncoder && video != null) {
                add(video)
            } else {
                auxSurface?.let { add(it) }
            }
        }
        if (surfaces.isEmpty()) return

        captureSession = createCaptureSession(device, surfaces)
        sessionHasEncoder = includeEncoder
        sessionHasPreview = preview != null
        sessionPreviewGeneration = previewGeneration
        submitRepeatingRequestNow(allowFallback = true)
        Log.i(
            TAG_VIDEO_ENCODER,
            "Capture session rebuilt (encoder=$includeEncoder preview=${preview != null}) streaming=$isStreaming"
        )
    }

    private fun ensurePreviewSurfaceLocked(): Boolean {
        if (previewSuspended) return false
        val view = textureView ?: return false
        if (!view.isAvailable) return false
        val existing = previewSurface
        if (existing != null && existing.isValid) return true
        runCatching { existing?.release() }
        previewSurface = null
        val texture = view.surfaceTexture ?: return false
        texture.setDefaultBufferSize(activePreviewSize.width, activePreviewSize.height)
        previewSurface = Surface(texture)
        previewGeneration++
        return true
    }

    private fun releasePreviewSurfaceLocked() {
        previewGeneration++
        runCatching { previewSurface?.release() }
        previewSurface = null
    }

    /**
     * Adds a second same-size output to preview-only sessions. The camera HAL
     * chooses its sensor mode from the complete session configuration, so
     * preview-only (one output) and preview + encoder (two outputs) can expose
     * different fields of view. This placeholder output keeps both sessions on
     * the same mode; its frames are discarded immediately.
     */
    private fun ensurePreviewAuxSurface(size: Size): Surface {
        val existing = previewAuxReader
        if (existing != null && existing.width == size.width && existing.height == size.height) {
            return existing.surface
        }
        releasePreviewAuxReader()
        val reader = ImageReader.newInstance(
            size.width,
            size.height,
            ImageFormat.YUV_420_888,
            2
        )
        reader.setOnImageAvailableListener(
            { imageReader ->
                runCatching { imageReader.acquireLatestImage()?.close() }
            },
            cameraHandler
        )
        previewAuxReader = reader
        return reader.surface
    }

    private fun releasePreviewAuxReader() {
        runCatching { previewAuxReader?.close() }
        previewAuxReader = null
    }

    private fun clearAutoFocus() {
        autoFocusRegion = null
        autoFocusAeRegion = null
    }

    private fun resetZoomState() {
        currentZoomRatio = 1f
        maxZoomRatio = 1f
        baseCropRect = null
        sensorRect = null
    }

    private fun submitRepeatingRequest() {
        cameraHandler.removeCallbacks(submitRunnable)
        cameraHandler.post(submitRunnable)
    }

    private fun submitRepeatingRequestNow(allowFallback: Boolean) {
        val session = captureSession ?: return
        try {
            val builder = buildRequest() ?: return
            session.setRepeatingRequest(builder.build(), null, cameraHandler)
        } catch (error: Exception) {
            Log.w(TAG_VIDEO_ENCODER, "Repeating request failed: ${error.message}", error)
            if (allowFallback && manualControlsEnabled) {
                manualControlsEnabled = false
                val recovered = runCatching {
                    val builder = buildRequest() ?: return
                    session.setRepeatingRequest(builder.build(), null, cameraHandler)
                }
                if (recovered.isSuccess) {
                    Log.w(TAG_VIDEO_ENCODER, "Manual controls failed; fell back to automatic capture")
                    listener?.onManualControlsFailed(
                        str(R.string.error_manual_rejected)
                    )
                } else {
                    listener?.onCameraRequestFailed(
                        str(R.string.error_camera_request, error.message ?: str(R.string.error_unknown))
                    )
                }
            } else {
                listener?.onCameraRequestFailed(
                    str(R.string.error_camera_request, error.message ?: str(R.string.error_unknown))
                )
            }
        }
    }

    private fun buildRequest(): CaptureRequest.Builder? {
        val device = cameraDevice ?: return null
        val template = if (isStreaming) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW
        val builder = device.createCaptureRequest(template)
        var hasTarget = false
        val preview = previewSurface
        if (preview != null && !previewSuspended && preview.isValid) {
            builder.addTarget(preview)
            hasTarget = true
        }
        if (isStreaming) {
            val video = videoSurface ?: return null
            builder.addTarget(video)
            hasTarget = true
        } else {
            previewAuxReader?.surface?.takeIf { it.isValid }?.let { aux ->
                builder.addTarget(aux)
                hasTarget = true
            }
        }
        if (!hasTarget) return null
        applyControls(builder)
        return builder
    }

    private fun applyControls(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        // Keep the preview and the encoded stream on the exact same field of
        // view. EIS/OIS can crop the frame differently between the preview and
        // record templates, which made the image jump when Start was pressed.
        builder.set(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
        )
        builder.set(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
        )

        activeFpsRange?.let {
            builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it)
        }

        val capabilities = manualCapabilities

        if (manualControlsEnabled && capabilities != null) {
            val frameDurationNs = 1_000_000_000L / activeFps.coerceAtLeast(1)
            val exposureMax = manualExposureMaxNs(
                sensorMaxNs = capabilities.exposureTimeRangeNs.last,
                fps = activeFps
            ).coerceAtLeast(capabilities.exposureTimeRangeNs.first)
            val exposure = manualValues.exposureTimeNs.coerceIn(
                capabilities.exposureTimeRangeNs.first,
                exposureMax
            )
            val iso = manualValues.iso.coerceIn(
                capabilities.sensitivityRange.first,
                capabilities.sensitivityRange.last
            )
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure)
            builder.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            builder.set(CaptureRequest.SENSOR_FRAME_DURATION, frameDurationNs)

            val maxFocus = capabilities.maxFocusDistanceDiopters
            if (maxFocus != null) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.set(
                    CaptureRequest.LENS_FOCUS_DISTANCE,
                    manualValues.focusDistanceDiopters.coerceIn(0f, maxFocus)
                )
            } else {
                autoAfMode?.let { builder.set(CaptureRequest.CONTROL_AF_MODE, it) }
            }

            val whiteBalance = capabilities.whiteBalancePresets
                .firstOrNull { it.awbMode == manualValues.awbMode }
            if (whiteBalance != null) {
                builder.set(CaptureRequest.CONTROL_AWB_MODE, whiteBalance.awbMode)
            } else {
                autoAwbMode?.let { builder.set(CaptureRequest.CONTROL_AWB_MODE, it) }
            }
        } else {
            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            val afRegion = autoFocusRegion
            val afMode = tapAfMode
            if (afRegion != null && afMode != null && supportsTapToFocus) {
                builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(afRegion))
                val aeRegion = autoFocusAeRegion
                if (aeRegion != null && maxAeRegions > 0) {
                    builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(aeRegion))
                }
            } else {
                autoAfMode?.let { builder.set(CaptureRequest.CONTROL_AF_MODE, it) }
            }
            autoAwbMode?.let { builder.set(CaptureRequest.CONTROL_AWB_MODE, it) }
        }

        if (flashAvailable) {
            builder.set(
                CaptureRequest.FLASH_MODE,
                if (torchEnabled) CaptureRequest.FLASH_MODE_TORCH
                else CaptureRequest.FLASH_MODE_OFF
            )
        }

        computeZoomCrop()?.let {
            builder.set(CaptureRequest.SCALER_CROP_REGION, it)
        }
    }

    private fun readAutoFocusCapabilities(characteristics: CameraCharacteristics) {
        val afModes = characteristics
            .get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: intArrayOf()
        val mode = when {
            afModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
                CaptureRequest.CONTROL_AF_MODE_AUTO

            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO

            afModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE

            else -> null
        }
        val minimumFocusDistance = characteristics
            .get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            ?: 0f
        maxAfRegions = characteristics
            .get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF)
            ?: 0
        maxAeRegions = characteristics
            .get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE)
            ?: 0
        tapAfMode = mode
        supportsTapToFocus = mode != null && minimumFocusDistance > 0f && maxAfRegions > 0
        Log.i(
            TAG_VIDEO_ENCODER,
            "AF capabilities: tapFocus=$supportsTapToFocus mode=$mode " +
                "minFocusDistance=$minimumFocusDistance afRegions=$maxAfRegions " +
                "aeRegions=$maxAeRegions"
        )
    }

    private fun computeZoomCrop(): Rect? {
        val base = baseCropRect ?: return sensorRect?.let { Rect(it) }
        if (currentZoomRatio <= 1.001f) return Rect(base)
        val width = (base.width() / currentZoomRatio).roundToInt().coerceAtLeast(2)
        val height = (base.height() / currentZoomRatio).roundToInt().coerceAtLeast(2)
        val centerX = base.centerX()
        val centerY = base.centerY()
        return Rect(
            centerX - width / 2,
            centerY - height / 2,
            centerX - width / 2 + width,
            centerY - height / 2 + height
        )
    }

    private fun computeBaseCropRect(sensor: Rect, width: Int, height: Int): Rect {
        if (width <= 0 || height <= 0) return Rect(sensor)
        val targetRatio = width.toFloat() / height.toFloat()
        var cropWidth = sensor.width()
        var cropHeight = (cropWidth / targetRatio).roundToInt()
        if (cropHeight > sensor.height()) {
            cropHeight = sensor.height()
            cropWidth = (cropHeight * targetRatio).roundToInt()
        }
        if (cropWidth > sensor.width()) cropWidth = sensor.width()
        cropWidth = (cropWidth / 2 * 2).coerceAtLeast(2)
        cropHeight = (cropHeight / 2 * 2).coerceAtLeast(2)
        val left = sensor.left + (sensor.width() - cropWidth) / 2
        val top = sensor.top + (sensor.height() - cropHeight) / 2
        return Rect(left, top, left + cropWidth, top + cropHeight)
    }

    private fun prepareEncoder(
        codec: VideoCodec,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int,
        onFormatReady: (EncodedParameterSets) -> Unit,
        onEncodedData: (ByteArray, Long, Boolean) -> Unit
    ) {
        val encoder = configureEncoderWithFallback(codec, width, height, fps, bitrate)
        videoSurface = encoder.createInputSurface()
        encoder.start()
        mediaCodec = encoder

        encoderJob = scope.launch {
            val bufferInfo = MediaCodec.BufferInfo()
            try {
                while (isActive && isStreaming) {
                    val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                    when {
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = encoder.outputFormat
                            when (codec) {
                                VideoCodec.H264 -> {
                                    val sps = newFormat.getByteBuffer("csd-0")
                                        ?.toByteArrayWithoutStartCode()
                                    val pps = newFormat.getByteBuffer("csd-1")
                                        ?.toByteArrayWithoutStartCode()
                                    if (sps != null && pps != null) {
                                        onFormatReady(
                                            EncodedParameterSets(
                                                codec = VideoCodec.H264,
                                                sps = sps,
                                                pps = pps
                                            )
                                        )
                                    }
                                }

                                VideoCodec.H265 -> {
                                    val sets = hevcParameterSets(newFormat)
                                    if (sets != null) {
                                        onFormatReady(sets)
                                    } else {
                                        Log.e(
                                            TAG_VIDEO_ENCODER,
                                            "HEVC output format did not contain VPS/SPS/PPS"
                                        )
                                    }
                                }
                            }
                        }

                        outputIndex >= 0 -> {
                            val outputBuffer = encoder.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val accessUnit = ByteArray(bufferInfo.size)
                                outputBuffer.get(accessUnit)
                                val isKeyFrame =
                                    (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                val codecConfig =
                                    (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                if (!codecConfig) {
                                    onEncodedData(accessUnit, bufferInfo.presentationTimeUs, isKeyFrame)
                                }
                            }
                            encoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            } catch (error: Exception) {
                if (isActive) {
                    Log.e(TAG_VIDEO_ENCODER, "Encoder output loop failed: ${error.message}", error)
                    listener?.onCameraRequestFailed(str(R.string.error_encoder_stopped))
                }
            }
            Log.i(TAG_VIDEO_ENCODER, "Encoder output loop finished")
        }
    }

    /**
     * MediaCodec exposes HEVC parameter sets either as one csd-0 buffer with
     * VPS/SPS/PPS or as separate csd buffers. Parse both layouts.
     */
    private fun hevcParameterSets(format: MediaFormat): EncodedParameterSets? {
        val buffers = listOf("csd-0", "csd-1", "csd-2").mapNotNull { key ->
            format.getByteBuffer(key)?.let { buffer ->
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                bytes
            }
        }
        if (buffers.isEmpty()) return null
        val nalUnits = buffers.flatMap { bytes ->
            val split = splitNalUnits(bytes)
            if (split.isEmpty() && bytes.size >= 2) listOf(bytes) else split
        }
        val vps = nalUnits.firstOrNull { hevcNalType(it) == 32 } ?: return null
        val sps = nalUnits.firstOrNull { hevcNalType(it) == 33 } ?: return null
        val pps = nalUnits.firstOrNull { hevcNalType(it) == 34 } ?: return null
        return EncodedParameterSets(
            codec = VideoCodec.H265,
            vps = vps,
            sps = sps,
            pps = pps
        )
    }

    private fun hevcNalType(nalUnit: ByteArray): Int =
        if (nalUnit.size >= 2) (nalUnit[0].toInt() ushr 1) and 0x3F else -1

    private fun configureEncoderWithFallback(
        videoCodec: VideoCodec,
        width: Int,
        height: Int,
        fps: Int,
        bitrate: Int
    ): MediaCodec {
        val attempts = if (videoCodec == VideoCodec.H265) {
            listOf(
                EncoderConfigAttempt(
                    label = "low-latency-hevc",
                    optionalConfig = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            setInteger(MediaFormat.KEY_PRIORITY, 0)
                            setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            setInteger(
                                MediaFormat.KEY_COLOR_RANGE,
                                MediaFormat.COLOR_RANGE_LIMITED
                            )
                        }
                    }
                ),
                EncoderConfigAttempt(
                    label = "compatible-hevc-cbr",
                    optionalConfig = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            setInteger(MediaFormat.KEY_PRIORITY, 0)
                        }
                    }
                ),
                EncoderConfigAttempt(label = "minimal-hevc", optionalConfig = { })
            )
        } else {
            listOf(
                EncoderConfigAttempt(
                    label = "low-latency-high-profile",
                    optionalConfig = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            setInteger(MediaFormat.KEY_PRIORITY, 0)
                            setFloat(MediaFormat.KEY_OPERATING_RATE, fps.toFloat())
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            setInteger(
                                MediaFormat.KEY_PROFILE,
                                MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                            )
                            setInteger(MediaFormat.KEY_LEVEL, chooseAvcLevel(width, height, fps))
                        }
                    }
                ),
                EncoderConfigAttempt(
                    label = "low-latency-baseline",
                    optionalConfig = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            setInteger(MediaFormat.KEY_PRIORITY, 0)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                        }
                    }
                ),
                EncoderConfigAttempt(
                    label = "compatible-cbr",
                    optionalConfig = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            setInteger(MediaFormat.KEY_PRIORITY, 0)
                        }
                    }
                ),
                EncoderConfigAttempt(label = "minimal-h264", optionalConfig = { })
            )
        }

        var lastError: Exception? = null
        attempts.forEach { attempt ->
            val encoder = MediaCodec.createEncoderByType(videoCodec.mimeType)
            try {
                val format = MediaFormat.createVideoFormat(
                    videoCodec.mimeType,
                    width,
                    height
                ).apply {
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                    )
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(
                        MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                    )
                    attempt.optionalConfig(this)
                }
                Log.i(
                    TAG_VIDEO_ENCODER,
                    "Trying encoder config: ${videoCodec.sdpName} ${attempt.label}"
                )
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                Log.i(
                    TAG_VIDEO_ENCODER,
                    "Encoder configured with: ${videoCodec.sdpName} ${attempt.label}"
                )
                return encoder
            } catch (error: Exception) {
                lastError = error
                Log.w(
                    TAG_VIDEO_ENCODER,
                    "Encoder config failed: ${videoCodec.sdpName} ${attempt.label}: ${error.message}"
                )
                runCatching { encoder.release() }
            }
        }

        throw IllegalStateException(str(R.string.error_no_encoder_config), lastError)
    }

    private fun buildStartFailureMessage(error: Exception): String {
        val message = error.message?.takeIf { it.isNotBlank() }
            ?: return str(R.string.error_failed_start_stream)
        return when {
            message == str(R.string.error_no_encoder_config) ||
                "No compatible video encoder configuration found" in message ->
                str(R.string.error_encoder_rejected)

            message == str(R.string.error_failed_capture_session) ||
                "Failed to configure camera capture session" in message ->
                str(R.string.error_camera_session_start)

            message == str(R.string.error_preview_surface_not_ready) ||
                "Preview surface is not ready" in message ->
                str(R.string.error_preview_not_ready_retry)

            message == str(R.string.error_camera_disconnected) ||
                "Camera disconnected" in message ->
                str(R.string.error_camera_disconnected_start)

            "Timed out waiting" in message -> str(R.string.error_camera_timeout)

            else -> message
        }
    }

    private data class EncoderConfigAttempt(
        val label: String,
        val optionalConfig: MediaFormat.() -> Unit
    )

    private suspend fun awaitPreviewSurface() {
        if (textureView == null) throw IllegalStateException(str(R.string.error_preview_view_not_attached))
        withTimeout(5_000) { surfaceReady.await() }
    }

    private suspend fun openCameraDevice(cameraId: String): CameraDevice =
        suspendCancellableCoroutine { continuation ->
            cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (continuation.isActive) {
                        cameraDevice = camera
                        continuation.resume(camera)
                    } else {
                        camera.close()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException(str(R.string.error_camera_disconnected))
                        )
                    } else {
                        listener?.onCameraDisconnected(str(R.string.error_camera_disconnected))
                    }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (cameraDevice === camera) cameraDevice = null
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            IllegalStateException(str(R.string.error_camera_error, error))
                        )
                    } else {
                        listener?.onCameraDisconnected(str(R.string.error_camera_error, error))
                    }
                }
            }, cameraHandler)
        }

    private suspend fun createCaptureSession(
        device: CameraDevice,
        surfaces: List<Surface>
    ): CameraCaptureSession = suspendCancellableCoroutine { continuation ->
        device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (continuation.isActive) {
                    continuation.resume(session)
                } else {
                    session.close()
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                if (continuation.isActive) {
                    continuation.resumeWithException(
                        IllegalStateException(str(R.string.error_failed_capture_session))
                    )
                }
            }
        }, cameraHandler)
    }

    private fun closeCameraInternal() {
        runCatching { captureSession?.close() }
        captureSession = null
        sessionHasEncoder = false
        sessionHasPreview = false
        sessionPreviewGeneration = -1L
        releasePreviewAuxReader()
        cameraDevice?.close()
        cameraDevice = null
        activeCameraId = null
        releasePreviewSurfaceLocked()
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        appContext.getString(id, *args)

    private fun chooseEncoderSize(
        configurationMap: StreamConfigurationMap,
        requested: Size
    ): Size {
        val sizes = configurationMap.getOutputSizes(MediaCodec::class.java)?.toList()
            ?: configurationMap.getOutputSizes(SurfaceTexture::class.java)?.toList()
            ?: emptyList()
        if (sizes.isEmpty()) return requested
        sizes.firstOrNull { it == requested }?.let { return it }
        val wide = sizes.filter { it.width * 9 == it.height * 16 }
        return (wide.ifEmpty { sizes })
            .minByOrNull { abs(it.width - requested.width) + abs(it.height - requested.height) }
            ?: requested
    }

    private fun choosePreviewSize(
        configurationMap: StreamConfigurationMap,
        encodeSize: Size
    ): Size {
        val sizes = configurationMap.getOutputSizes(SurfaceTexture::class.java)?.toList()
            ?: return encodeSize
        if (sizes.isEmpty()) return encodeSize
        if (sizes.contains(encodeSize) && encodeSize.width >= 1280) return encodeSize

        val targetRatio = encodeSize.width.toFloat() / encodeSize.height.toFloat()
        val sameAspect = sizes.filter {
            abs(it.width.toFloat() / it.height.toFloat() - targetRatio) < 0.02f
        }
        val preferred = sameAspect
            .filter { it.width <= 1920 }
            .maxByOrNull { it.width }
        if (preferred != null) return preferred
        sameAspect.minByOrNull { it.width }?.let { return it }
        return sizes.filter { it.width <= 1920 }.maxByOrNull { it.width }
            ?: sizes.minByOrNull { it.width }
            ?: encodeSize
    }

    private fun chooseFpsRange(
        characteristics: CameraCharacteristics,
        targetFps: Int,
        encodeSize: Size,
        configurationMap: StreamConfigurationMap
    ): Range<Int>? {
        val durationNs = runCatching {
            configurationMap.getOutputMinFrameDuration(MediaCodec::class.java, encodeSize)
        }.getOrDefault(0L).takeIf { it > 0 } ?: runCatching {
            configurationMap.getOutputMinFrameDuration(SurfaceTexture::class.java, encodeSize)
        }.getOrDefault(0L)
        val maxFps = if (durationNs > 0) 1_000_000_000.0 / durationNs else 60.0
        val ranges = characteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()
            .filter { it.upper <= maxFps + 0.5 }
        if (ranges.isEmpty()) return null
        return ranges.firstOrNull { it.lower == targetFps && it.upper == targetFps }
            ?: ranges
                .filter { targetFps in it.lower..it.upper }
                .minByOrNull { it.upper - it.lower }
            ?: ranges.minByOrNull {
                abs(it.upper - targetFps) + abs(it.lower - targetFps)
            }
    }

    private fun chooseAvcLevel(width: Int, height: Int, fps: Int): Int {
        val macroblocksWide = (width + 15) / 16
        val macroblocksHigh = (height + 15) / 16
        val frameMacroblocks = macroblocksWide * macroblocksHigh
        val macroblocksPerSecond = frameMacroblocks.toLong() * fps
        return when {
            frameMacroblocks <= 8192 && macroblocksPerSecond <= 245_760 ->
                MediaCodecInfo.CodecProfileLevel.AVCLevel4

            frameMacroblocks <= 8704 && macroblocksPerSecond <= 522_240 ->
                MediaCodecInfo.CodecProfileLevel.AVCLevel42

            frameMacroblocks <= 22_080 && macroblocksPerSecond <= 589_824 ->
                MediaCodecInfo.CodecProfileLevel.AVCLevel5

            frameMacroblocks <= 36_864 && macroblocksPerSecond <= 983_040 ->
                MediaCodecInfo.CodecProfileLevel.AVCLevel51

            else -> MediaCodecInfo.CodecProfileLevel.AVCLevel52
        }
    }

}

private const val TAG_VIDEO_ENCODER = "VIDEO_ENCODER"

private fun java.nio.ByteBuffer.toByteArrayWithoutStartCode(): ByteArray {
    val source = duplicate()
    val bytes = ByteArray(source.remaining())
    source.get(bytes)
    return if (bytes.size > 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
        ((bytes[2] == 0.toByte() && bytes[3] == 1.toByte()) || bytes[2] == 1.toByte())
    ) {
        val startCodeLength = if (bytes[2] == 1.toByte()) 3 else 4
        bytes.copyOfRange(startCodeLength, bytes.size)
    } else {
        bytes
    }
}
