package com.remcam

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import android.view.TextureView
import android.hardware.camera2.CaptureRequest
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.remcam.streaming.CameraCapabilities
import com.remcam.streaming.CameraCapabilityReader
import com.remcam.streaming.RemCamEngine
import com.remcam.streaming.StreamRequest
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class RemCamViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences: SharedPreferences =
        application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val capabilityReader = CameraCapabilityReader(application)
    private val engine = RemCamEngine.get(application)
    private val capabilitiesByCameraId = mutableMapOf<String, CameraCapabilities>()

    private val _uiState = MutableStateFlow(buildInitialUiState())
    val uiState: StateFlow<RemCamUiState> = _uiState.asStateFlow()

    private var screenVisible = false
    private var previewAttached = false
    private var previewGeneration = 0
    private var openedCameraId: String? = null
    private var openingCameraId: String? = null
    private var previewJob: Job? = null

    private var lastEngineErrorId = 0L
    private var lastManualFallbackId = 0L

    init {
        // The UI starts in Auto; make sure a leftover manual state from a
        // previous Activity cannot keep the engine in manual capture.
        engine.setManualControlsEnabled(false)
        engine.updateManualControls(_uiState.value.manualValues)

        viewModelScope.launch {
            engine.state.collect { engineState ->
                val newError = engineState.errorId > lastEngineErrorId
                val manualFallback = engineState.manualFallbackId > lastManualFallbackId
                if (newError) lastEngineErrorId = engineState.errorId
                if (manualFallback) lastManualFallbackId = engineState.manualFallbackId

                _uiState.update { current ->
                    current.copy(
                        cameraReady = engineState.cameraReady,
                        streamStatus = engineState.streamStatus,
                        streamUrl = engineState.streamUrl ?: current.streamUrl,
                        previewWidth = if (engineState.previewWidth > 0) {
                            engineState.previewWidth
                        } else {
                            current.previewWidth
                        },
                        previewHeight = if (engineState.previewHeight > 0) {
                            engineState.previewHeight
                        } else {
                            current.previewHeight
                        },
                        errorMessage = if (newError) {
                            engineState.errorMessage
                        } else {
                            current.errorMessage
                        },
                        manualMode = if (manualFallback) false else current.manualMode,
                        manualPanelVisible = if (manualFallback) {
                            false
                        } else {
                            current.manualPanelVisible
                        }
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Lifecycle / preview
    // ---------------------------------------------------------------------

    fun onScreenVisible() {
        screenVisible = true
        refreshNetwork()
        engine.onUiVisible()
        if (_uiState.value.hasCameraPermission &&
            !_uiState.value.isStreaming &&
            !_uiState.value.isBusy
        ) {
            openPreviewForCurrentCamera()
        }
    }

    fun onScreenHidden() {
        screenVisible = false
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
        openedCameraId = null
        openingCameraId = null
        engine.onUiHidden()

        val state = _uiState.value
        if (state.isStreaming || state.isStarting) {
            // Keep camera, encoder, RTSP server and stream alive. The engine
            // rebuilds the capture session without the preview surface.
            return
        }
        viewModelScope.launch {
            runCatching { engine.closeCamera() }
            _uiState.update {
                it.copy(
                    cameraReady = false,
                    streamStatus = StreamStatus.NOT_STREAMING,
                    torchEnabled = false,
                    zoom = 1f
                )
            }
        }
    }

    fun onPreviewAttached(view: TextureView) {
        engine.attachPreview(view)
        previewAttached = true
        if (screenVisible &&
            _uiState.value.hasCameraPermission &&
            !_uiState.value.isStreaming &&
            !_uiState.value.isBusy
        ) {
            openPreviewForCurrentCamera()
        }
    }

    fun onPreviewDetached(view: TextureView) {
        previewAttached = false
        openedCameraId = null
        engine.detachPreview(view)
    }

    fun onCameraPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                hasCameraPermission = granted,
                errorMessage = if (granted) {
                    it.errorMessage
                } else {
                    string(R.string.error_camera_permission_denied)
                }
            )
        }
        if (granted) {
            refreshNetwork()
            if (_uiState.value.availableCameras.isEmpty()) {
                refreshCameraOptions()
            }
            openPreviewForCurrentCamera()
        }
    }

    fun retryPreview() {
        if (!screenVisible || !previewAttached) return
        openPreviewForCurrentCamera(force = true)
    }

    private fun openPreviewForCurrentCamera(force: Boolean = false) {
        val state = _uiState.value
        if (!state.hasCameraPermission || !screenVisible || !previewAttached) return
        if (state.isStreaming || state.isBusy) return
        val cameraId = state.selectedCameraId
        if (cameraId.isBlank()) return
        if (!force && state.cameraReady && openedCameraId == cameraId) return
        if (!force && previewJob?.isActive == true && openingCameraId == cameraId) return

        val generation = ++previewGeneration
        val fps = state.selectedFps.fps
        val resolution = state.selectedResolution
        openingCameraId = cameraId
        _uiState.update { it.copy(cameraReady = false) }
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            if (force) {
                runCatching { engine.closeCamera() }
                if (generation != previewGeneration) return@launch
            }
            val result = engine.openPreview(cameraId, fps, resolution)
            if (generation != previewGeneration) return@launch
            openingCameraId = null
            result.onSuccess {
                openedCameraId = cameraId
                _uiState.update {
                    it.copy(
                        cameraReady = true,
                        errorMessage = null,
                        zoom = engine.getCurrentZoomRatio(),
                        maxZoom = engine.getMaxZoomRatio()
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                openedCameraId = null
                _uiState.update {
                    it.copy(
                        cameraReady = false,
                        errorMessage = error.message ?: string(R.string.error_unable_open_camera)
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Camera switching
    // ---------------------------------------------------------------------

    fun selectCamera(cameraId: String) {
        val state = _uiState.value
        if (state.isStreaming || state.isBusy) return
        if (cameraId == state.selectedCameraId) return
        if (state.availableCameras.none { it.id == cameraId }) return
        applyCamera(cameraId)
    }

    private fun applyCamera(cameraId: String, reopen: Boolean = true) {
        val state = _uiState.value
        val capabilities = capabilitiesFor(cameraId)
        val resolutions = capabilities.resolutions.ifEmpty { state.availableResolutions }
        val resolution = resolutions.firstOrNull { it == state.selectedResolution }
            ?: resolutions.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: resolutions.firstOrNull()
            ?: state.selectedResolution
        val fpsOptions = capabilities.fpsOptionsFor(resolution).ifEmpty {
            state.availableFpsOptions.ifEmpty { listOf(FpsPreset.FPS_30) }
        }
        val fps = fpsOptions.firstOrNull { it == state.selectedFps }
            ?: fpsOptions.firstOrNull { it.fps == FpsPreset.FPS_30.fps }
            ?: fpsOptions.first()
        val manualValues = clampManualValues(state.manualValues, capabilities, fps.fps)
        val manualMode = state.manualMode && capabilities.manualCapabilities != null

        val nextState = state.copy(
            selectedCameraId = cameraId,
            sensorOrientation = capabilities.sensorOrientation,
            selectedResolution = resolution,
            selectedFps = fps,
            availableResolutions = resolutions,
            availableFpsOptions = fpsOptions,
            manualCapabilities = capabilities.manualCapabilities,
            manualMode = manualMode,
            manualPanelVisible = manualMode && state.manualPanelVisible,
            manualValues = manualValues,
            flashAvailable = capabilities.flashAvailable,
            torchEnabled = false,
            maxZoom = capabilities.maxDigitalZoom,
            zoom = 1f,
            cameraReady = false,
            tapToFocusSupported = capabilities.tapToFocusSupported,
            errorMessage = null
        ).withUrl()

        previewGeneration++
        previewJob?.cancel()
        openedCameraId = null
        _uiState.value = nextState
        engine.setTorch(false)
        engine.updateManualControls(manualValues)
        engine.setManualControlsEnabled(manualMode)
        saveSettings(nextState)
        if (reopen) openPreviewForCurrentCamera(force = true)
    }

    // ---------------------------------------------------------------------
    // Manual controls / zoom / torch
    // ---------------------------------------------------------------------

    fun setManualMode(enabled: Boolean) {
        val state = _uiState.value
        if (enabled && state.manualCapabilities == null) return
        if (enabled) {
            if (state.manualMode) {
                // Tapping the already selected Manual segment toggles the panel.
                _uiState.update { it.copy(manualPanelVisible = !it.manualPanelVisible) }
            } else {
                engine.setManualControlsEnabled(true)
                engine.updateManualControls(state.manualValues)
                _uiState.update {
                    it.copy(manualMode = true, manualPanelVisible = true)
                }
            }
        } else {
            engine.setManualControlsEnabled(false)
            _uiState.update {
                it.copy(manualMode = false, manualPanelVisible = false)
            }
        }
    }

    fun hideManualPanel() {
        _uiState.update {
            if (it.manualMode) it.copy(manualPanelVisible = false) else it
        }
    }

    fun updateManualValues(values: ManualControlValues) {
        val state = _uiState.value
        val clamped = clampManualValues(
            values = values,
            capabilities = capabilitiesFor(state.selectedCameraId),
            fps = state.selectedFps.fps
        )
        engine.updateManualControls(clamped)
        _uiState.update { it.copy(manualValues = clamped) }
    }

    fun setZoom(ratio: Float) {
        _uiState.update { it.copy(zoom = engine.setZoom(ratio)) }
    }

    fun zoomBy(factor: Float) {
        _uiState.update { it.copy(zoom = engine.scaleZoomBy(factor)) }
    }

    fun resetZoom() {
        _uiState.update { it.copy(zoom = engine.resetZoom()) }
    }

    fun toggleTorch() {
        val state = _uiState.value
        if (!state.flashAvailable) return
        val next = !state.torchEnabled
        engine.setTorch(next)
        _uiState.update { it.copy(torchEnabled = next) }
    }

    /**
     * Requests a one-shot tap-to-focus at normalised preview-buffer
     * coordinates. Returns false when the camera is in Manual mode, has no
     * usable autofocus, or no preview/capture session is running.
     */
    fun tapToFocus(normalizedX: Float, normalizedY: Float): Boolean {
        val state = _uiState.value
        if (!state.hasCameraPermission) return false
        if (state.manualMode) return false
        if (!state.tapToFocusSupported) return false
        if (!state.cameraReady && !state.isStreaming) return false
        return engine.focusAtBufferPoint(PointF(normalizedX, normalizedY))
    }

    // ---------------------------------------------------------------------
    // Streaming
    // ---------------------------------------------------------------------

    fun startStreaming() {
        val state = _uiState.value
        if (state.isStreaming || state.isBusy) return
        refreshNetwork()
        val current = _uiState.value
        val validationError = validateStartRequest(current)
        if (validationError != null) {
            _uiState.update {
                it.copy(
                    streamStatus = StreamStatus.ERROR,
                    errorMessage = validationError
                )
            }
            return
        }

        val port = current.portInput.toIntOrNull()
        val host = current.localIpAddress
        if (port == null || host == null) {
            _uiState.update {
                it.copy(
                    streamStatus = StreamStatus.ERROR,
                    errorMessage = string(R.string.error_no_local_address)
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                streamStatus = StreamStatus.STARTING,
                errorMessage = null
            )
        }
        engine.startStreaming(
            request = StreamRequest(
                cameraId = current.selectedCameraId,
                resolution = current.selectedResolution,
                fps = current.selectedFps,
                bitrate = current.selectedBitrate,
                codec = current.selectedCodec,
                port = port,
                hostAddress = host,
                authEnabled = current.rtspAuthEnabled,
                authUsername = current.rtspUsername,
                authPassword = current.rtspPassword
            ),
            url = "rtsp://$host:$port/camera"
        )
    }

    fun stopStreaming() {
        val state = _uiState.value
        if (state.isStopping || (!state.isStreaming && !state.isStarting)) return
        _uiState.update {
            it.copy(streamStatus = StreamStatus.STOPPING)
        }
        engine.stopStreaming()
    }

    fun dismissError() {
        engine.clearError()
        _uiState.update {
            if (it.streamStatus == StreamStatus.ERROR) {
                it.copy(
                    streamStatus = StreamStatus.NOT_STREAMING,
                    errorMessage = null
                )
            } else {
                it.copy(errorMessage = null)
            }
        }
    }

    // ---------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------

    fun updatePortInput(input: String) {
        val filtered = input.filter(Char::isDigit).take(5)
        _uiState.update { current ->
            current.copy(portInput = filtered).withUrl().also(::saveSettings)
        }
    }

    fun selectResolution(preset: ResolutionPreset) {
        val state = _uiState.value
        if (state.isStreaming || state.isBusy) return
        if (preset !in state.availableResolutions) return
        val capabilities = capabilitiesFor(state.selectedCameraId)
        val fpsOptions = capabilities.fpsOptionsFor(preset).ifEmpty {
            state.availableFpsOptions.ifEmpty { listOf(FpsPreset.FPS_30) }
        }
        val fps = fpsOptions.firstOrNull { it == state.selectedFps }
            ?: fpsOptions.firstOrNull { it.fps == FpsPreset.FPS_30.fps }
            ?: fpsOptions.first()
        val values = clampManualValues(state.manualValues, capabilities, fps.fps)
        _uiState.update {
            it.copy(
                selectedResolution = preset,
                selectedFps = fps,
                availableFpsOptions = fpsOptions,
                manualValues = values
            ).also(::saveSettings)
        }
        engine.updateManualControls(values)
        // Keep the preview's buffer/framing in sync with the selected stream
        // size instead of updating it only when streaming starts.
        if (!state.isStreaming && !state.isBusy) {
            openPreviewForCurrentCamera(force = true)
        }
    }

    fun selectFps(preset: FpsPreset) {
        val state = _uiState.value
        if (state.isStreaming || state.isBusy) return
        if (preset !in state.availableFpsOptions) return
        val capabilities = capabilitiesFor(state.selectedCameraId)
        val values = clampManualValues(state.manualValues, capabilities, preset.fps)
        _uiState.update {
            it.copy(selectedFps = preset, manualValues = values).also(::saveSettings)
        }
        engine.updateManualControls(values)
    }

    fun selectBitrate(preset: BitratePreset) {
        val state = _uiState.value
        if (state.isStreaming || state.isBusy) return
        _uiState.update {
            it.copy(selectedBitrate = preset).also(::saveSettings)
        }
    }

    fun selectCodec(codec: VideoCodec) {
        val state = _uiState.value
        if (state.isStreaming || state.isBusy) return
        val option = state.availableCodecs.firstOrNull { it.codec == codec } ?: return
        _uiState.update {
            it.copy(
                selectedCodec = codec,
                encoderMaxBitrateMbps = option.maxBitrateMbps
            ).also(::saveSettings)
        }
    }

    fun setRtspAuthEnabled(enabled: Boolean) {
        val state = _uiState.value
        if (state.isStreaming || state.isBusy) return
        _uiState.update {
            it.copy(rtspAuthEnabled = enabled).also(::saveSettings)
        }
    }

    fun updateRtspUsername(username: String) {
        val state = _uiState.value
        if (state.isStreaming || state.isBusy) return
        _uiState.update {
            it.copy(rtspUsername = username).also(::saveSettings)
        }
    }

    fun updateRtspPassword(password: String) {
        val state = _uiState.value
        if (state.isStreaming || state.isBusy) return
        _uiState.update {
            it.copy(rtspPassword = password).also(::saveSettings)
        }
    }

    fun refreshLocalNetwork() {
        refreshNetwork()
    }

    // ---------------------------------------------------------------------
    // Validation / helpers
    // ---------------------------------------------------------------------

    private fun validateStartRequest(state: RemCamUiState): String? {
        if (!state.hasCameraPermission) {
            return string(R.string.error_camera_permission_denied)
        }
        if (state.selectedCameraId.isBlank() ||
            state.availableCameras.none { it.id == state.selectedCameraId }
        ) {
            return string(R.string.error_camera_not_available)
        }
        if (!state.hasLocalAddress) {
            return string(R.string.error_no_local_network)
        }
        if (state.availableResolutions.isEmpty()) {
            return string(R.string.error_camera_capabilities_unavailable)
        }
        if (state.selectedResolution !in state.availableResolutions) {
            return string(R.string.error_resolution_unsupported)
        }
        if (state.selectedFps !in state.availableFpsOptions) {
            return string(R.string.error_fps_unsupported, state.selectedResolution.label)
        }
        val port = state.portInput.toIntOrNull()
        if (port == null || port !in 1..65535) {
            return string(R.string.error_invalid_port)
        }
        if (state.rtspAuthEnabled && state.rtspUsername.isBlank()) {
            return string(R.string.error_auth_username)
        }
        if (state.rtspAuthEnabled && state.rtspPassword.isBlank()) {
            return string(R.string.error_auth_password)
        }
        return null
    }

    private fun refreshCameraOptions() {
        val cameras = capabilityReader.availableCameras()
        if (cameras.isEmpty()) return
        val state = _uiState.value
        val selected = cameras.firstOrNull { it.id == state.selectedCameraId }
            ?: cameras.first()
        _uiState.update {
            it.copy(
                availableCameras = cameras,
                selectedCameraId = selected.id
            )
        }
        applyCamera(selected.id, reopen = false)
    }

    private fun capabilitiesFor(cameraId: String): CameraCapabilities {
        if (cameraId.isBlank()) return CameraCapabilities.empty()
        return capabilitiesByCameraId.getOrPut(cameraId) {
            capabilityReader.readCapabilities(cameraId)
        }
    }

    private fun buildInitialUiState(): RemCamUiState {
        val saved = readSavedSettings()
        val cameras = capabilityReader.availableCameras()
        val selectedCamera = cameras.firstOrNull { it.id == saved.cameraId }
            ?: cameras.firstOrNull()
        val cameraId = selectedCamera?.id ?: saved.cameraId.orEmpty()
        val capabilities = capabilitiesFor(cameraId)
        val resolutions = capabilities.resolutions.ifEmpty { listOf(saved.resolution) }
        val resolution = resolutions.firstOrNull { it == saved.resolution }
            ?: resolutions.firstOrNull { it.width == 1280 && it.height == 720 }
            ?: resolutions.first()
        val fpsOptions = capabilities.fpsOptionsFor(resolution).ifEmpty {
            listOf(saved.fps)
        }
        val fps = fpsOptions.firstOrNull { it == saved.fps }
            ?: fpsOptions.firstOrNull { it.fps == FpsPreset.FPS_30.fps }
            ?: fpsOptions.first()
        val manualValues = defaultManualValues(capabilities, fps.fps)
        val codecOptions = capabilityReader.supportedVideoCodecs()
            .ifEmpty { listOf(VideoCodecOption(VideoCodec.H264, hardware = false)) }
        val selectedCodec = saved.codec
            .takeIf { codec -> codecOptions.any { it.codec == codec } }
            ?: codecOptions.firstOrNull { it.codec == VideoCodec.H264 }?.codec
            ?: codecOptions.first().codec
        val codecOption = codecOptions.first { it.codec == selectedCodec }

        return RemCamUiState(
            selectedCameraId = cameraId,
            availableCameras = cameras,
            sensorOrientation = capabilities.sensorOrientation,
            selectedResolution = resolution,
            selectedFps = fps,
            selectedBitrate = saved.bitrate,
            selectedCodec = selectedCodec,
            availableCodecs = codecOptions,
            availableResolutions = resolutions,
            availableFpsOptions = fpsOptions,
            encoderMaxBitrateMbps = codecOption.maxBitrateMbps,
            manualCapabilities = capabilities.manualCapabilities,
            manualValues = manualValues,
            flashAvailable = capabilities.flashAvailable,
            maxZoom = capabilities.maxDigitalZoom,
            tapToFocusSupported = capabilities.tapToFocusSupported,
            rtspAuthEnabled = saved.authEnabled,
            rtspUsername = saved.username,
            rtspPassword = saved.password,
            portInput = saved.port
        ).withNetworkInfo(readNetworkInfo())
    }

    private fun defaultManualValues(
        capabilities: CameraCapabilities,
        fps: Int
    ): ManualControlValues {
        val manual = capabilities.manualCapabilities
            ?: return ManualControlValues()
        val exposureMax = manualExposureMaxNs(
            sensorMaxNs = manual.exposureTimeRangeNs.last,
            fps = fps
        ).coerceAtLeast(manual.exposureTimeRangeNs.first)
        // Start manual exposure near a normal indoor value instead of the
        // sensor maximum, which would blow out the preview on the first open.
        val exposure = MANUAL_DEFAULT_EXPOSURE_NS.coerceIn(
            manual.exposureTimeRangeNs.first,
            exposureMax
        )
        val iso = MANUAL_DEFAULT_ISO.coerceIn(
            manual.sensitivityRange.first,
            manual.sensitivityRange.last
        )
        val awb = manual.whiteBalancePresets
            .firstOrNull { it.awbMode == CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT }
            ?.awbMode
            ?: manual.whiteBalancePresets.firstOrNull()?.awbMode
        return ManualControlValues(
            exposureTimeNs = exposure,
            iso = iso,
            focusDistanceDiopters = 0f,
            awbMode = awb
        )
    }

    private fun clampManualValues(
        values: ManualControlValues,
        capabilities: CameraCapabilities,
        fps: Int
    ): ManualControlValues {
        val manual = capabilities.manualCapabilities ?: return values
        val exposureMax = manualExposureMaxNs(
            sensorMaxNs = manual.exposureTimeRangeNs.last,
            fps = fps
        ).coerceAtLeast(manual.exposureTimeRangeNs.first)
        val focusMax = manual.maxFocusDistanceDiopters ?: 0f
        val awb = values.awbMode?.takeIf { mode ->
            manual.whiteBalancePresets.any { it.awbMode == mode }
        } ?: manual.whiteBalancePresets.firstOrNull()?.awbMode
        return values.copy(
            exposureTimeNs = values.exposureTimeNs.coerceIn(
                manual.exposureTimeRangeNs.first,
                exposureMax
            ),
            iso = values.iso.coerceIn(
                manual.sensitivityRange.first,
                manual.sensitivityRange.last
            ),
            focusDistanceDiopters = values.focusDistanceDiopters.coerceIn(0f, focusMax),
            awbMode = awb
        )
    }

    private fun readSavedSettings(): SavedSettings {
        val cameraId = preferences.getString(KEY_CAMERA_ID, null)
        val resolution = parseResolution(preferences.getString(KEY_RESOLUTION, null))
            ?: ResolutionPreset(1280, 720)
        val fps = FpsPreset.entries.firstOrNull {
            it.name == preferences.getString(KEY_FPS, FpsPreset.FPS_30.name)
        } ?: FpsPreset.FPS_30
        val bitrate = BitratePreset.entries.firstOrNull {
            it.name == preferences.getString(KEY_BITRATE, BitratePreset.MBPS_6.name)
        } ?: BitratePreset.MBPS_6
        val codec = VideoCodec.entries.firstOrNull {
            it.name == preferences.getString(KEY_CODEC, VideoCodec.H264.name)
        } ?: VideoCodec.H264
        val port = preferences.getString(KEY_PORT, "8554").orEmpty().ifBlank { "8554" }
        return SavedSettings(
            cameraId = cameraId,
            resolution = resolution,
            fps = fps,
            bitrate = bitrate,
            codec = codec,
            port = port,
            authEnabled = preferences.getBoolean(KEY_AUTH_ENABLED, false),
            username = preferences.getString(KEY_AUTH_USERNAME, "").orEmpty(),
            password = preferences.getString(KEY_AUTH_PASSWORD, "").orEmpty()
        )
    }

    private fun parseResolution(value: String?): ResolutionPreset? {
        if (value.isNullOrBlank()) return null
        return when (value) {
            "HD" -> ResolutionPreset(1280, 720)
            "FULL_HD" -> ResolutionPreset(1920, 1080)
            else -> {
                val parts = value.split('x', 'X')
                if (parts.size != 2) return null
                val width = parts[0].trim().toIntOrNull() ?: return null
                val height = parts[1].trim().toIntOrNull() ?: return null
                if (width > 0 && height > 0) ResolutionPreset(width, height) else null
            }
        }
    }

    private fun saveSettings(state: RemCamUiState) {
        preferences.edit()
            .putString(KEY_CAMERA_ID, state.selectedCameraId)
            .putString(
                KEY_RESOLUTION,
                "${state.selectedResolution.width}x${state.selectedResolution.height}"
            )
            .putString(KEY_FPS, state.selectedFps.name)
            .putString(KEY_BITRATE, state.selectedBitrate.name)
            .putString(KEY_CODEC, state.selectedCodec.name)
            .putString(KEY_PORT, state.portInput)
            .putBoolean(KEY_AUTH_ENABLED, state.rtspAuthEnabled)
            .putString(KEY_AUTH_USERNAME, state.rtspUsername)
            .putString(KEY_AUTH_PASSWORD, state.rtspPassword)
            .apply()
    }

    // ---------------------------------------------------------------------
    // Network discovery
    // ---------------------------------------------------------------------

    private fun refreshNetwork() {
        _uiState.update { it.withNetworkInfo(readNetworkInfo()) }
    }

    private fun readNetworkInfo(): NetworkInfo {
        val addresses = findLocalAddresses()
        return NetworkInfo(
            addresses = addresses,
            primaryAddress = addresses.firstOrNull()?.address
        )
    }

    private fun string(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun interfaceLabel(name: String): String = when {
        name.startsWith("wlan") -> string(R.string.network_wifi)
        name.startsWith("rndis") || name.startsWith("usb") ->
            string(R.string.network_usb_tethering)

        name.startsWith("eth") -> string(R.string.network_ethernet)
        name.startsWith("ap") || name.startsWith("swlan") ->
            string(R.string.network_hotspot)

        name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") ->
            string(R.string.network_mobile_data)

        name.startsWith("tun") || name.startsWith("ppp") -> string(R.string.network_vpn)
        else -> string(R.string.network_other)
    }

    private fun findLocalAddresses(): List<LocalAddress> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { iface ->
                    runCatching { iface.isUp && !iface.isLoopback }.getOrDefault(false)
                }
                .flatMap { iface ->
                    iface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .filter { address ->
                            !address.isLoopbackAddress && !address.isLinkLocalAddress
                        }
                        .mapNotNull { address ->
                            address.hostAddress?.let { host ->
                                LocalAddress(
                                    address = host,
                                    interfaceName = iface.name,
                                    label = interfaceLabel(iface.name)
                                )
                            }
                        }
                }
                .distinctBy { it.address }
                .sortedWith(
                    compareBy<LocalAddress>(
                        { interfacePriority(it.interfaceName) },
                        { it.address }
                    )
                )
        }.getOrDefault(emptyList())
    }

    override fun onCleared() {
        // Streaming and the camera are application-scoped now. Never tear the
        // engine down here: the Activity may simply have been destroyed while
        // the foreground service keeps streaming.
        engine.onViewModelCleared()
        super.onCleared()
    }

    private data class SavedSettings(
        val cameraId: String?,
        val resolution: ResolutionPreset,
        val fps: FpsPreset,
        val bitrate: BitratePreset,
        val codec: VideoCodec,
        val port: String,
        val authEnabled: Boolean,
        val username: String,
        val password: String
    )
}

private data class NetworkInfo(
    val addresses: List<LocalAddress>,
    val primaryAddress: String?
)

private fun RemCamUiState.withNetworkInfo(info: NetworkInfo): RemCamUiState =
    copy(
        localAddresses = info.addresses,
        localIpAddress = info.primaryAddress
    ).withUrl()

private fun RemCamUiState.withUrl(): RemCamUiState {
    val ip = localIpAddress
    val port = portInput.toIntOrNull()
    val url = if (!ip.isNullOrBlank() && port != null) {
        "rtsp://$ip:$port/camera"
    } else {
        ""
    }
    return copy(streamUrl = url)
}

private fun interfacePriority(name: String): Int = when {
    name.startsWith("rndis") || name.startsWith("usb") -> 0
    name.startsWith("wlan") -> 1
    name.startsWith("eth") -> 2
    name.startsWith("ap") || name.startsWith("swlan") -> 3
    else -> 4
}

private const val PREFS_NAME = "remcam_settings"
private const val KEY_CAMERA_ID = "camera_id"
private const val KEY_RESOLUTION = "resolution"
private const val KEY_FPS = "fps"
private const val KEY_BITRATE = "bitrate"
private const val KEY_CODEC = "video_codec"
private const val KEY_PORT = "port"
private const val KEY_AUTH_ENABLED = "rtsp_auth_enabled"
private const val KEY_AUTH_USERNAME = "rtsp_username"
private const val KEY_AUTH_PASSWORD = "rtsp_password"
