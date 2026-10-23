package com.remcam

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.PointF
import android.view.TextureView
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
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
    private val capabilitiesByLens = mutableMapOf<Int, CameraCapabilities>()
    private val encoderMaxBitrateMbps: Int? by lazy {
        capabilityReader.readAvcEncoderMaxBitrateMbps()
    }

    private val _uiState = MutableStateFlow(buildInitialUiState())
    val uiState: StateFlow<RemCamUiState> = _uiState.asStateFlow()

    private var screenVisible = false
    private var previewAttached = false
    private var previewGeneration = 0
    private var openedLens: Int? = null
    private var openingLens: Int? = null
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
            openPreviewForCurrentLens()
        }
    }

    fun onScreenHidden() {
        screenVisible = false
        previewGeneration++
        previewJob?.cancel()
        previewJob = null
        openedLens = null
        openingLens = null
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
            openPreviewForCurrentLens()
        }
    }

    fun onPreviewDetached(view: TextureView) {
        previewAttached = false
        openedLens = null
        engine.detachPreview(view)
    }

    fun onCameraPermissionResult(granted: Boolean) {
        _uiState.update {
            it.copy(
                hasCameraPermission = granted,
                errorMessage = if (granted) it.errorMessage else "Camera permission denied"
            )
        }
        if (granted) {
            refreshNetwork()
            openPreviewForCurrentLens()
        }
    }

    fun retryPreview() {
        if (!screenVisible || !previewAttached) return
        openPreviewForCurrentLens(force = true)
    }

    private fun openPreviewForCurrentLens(force: Boolean = false) {
        val state = _uiState.value
        if (!state.hasCameraPermission || !screenVisible || !previewAttached) return
        if (state.isStreaming || state.isBusy) return
        if (!force && state.cameraReady && openedLens == state.selectedCameraLens) return
        if (!force && previewJob?.isActive == true && openingLens == state.selectedCameraLens) return

        val generation = ++previewGeneration
        val lens = state.selectedCameraLens
        val fps = state.selectedFps.fps
        val resolution = state.selectedResolution
        openingLens = lens
        _uiState.update { it.copy(cameraReady = false) }
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            if (force) {
                runCatching { engine.closeCamera() }
                if (generation != previewGeneration) return@launch
            }
            val result = engine.openPreview(lens, fps, resolution)
            if (generation != previewGeneration) return@launch
            openingLens = null
            result.onSuccess {
                openedLens = lens
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
                openedLens = null
                _uiState.update {
                    it.copy(
                        cameraReady = false,
                        errorMessage = error.message ?: "Unable to open camera"
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Camera switching
    // ---------------------------------------------------------------------

    fun switchCamera() {
        val state = _uiState.value
        if (state.isStreaming || state.isBusy || state.availableLenses.size < 2) return
        val currentIndex = state.availableLenses.indexOf(state.selectedCameraLens)
        val nextIndex = if (currentIndex < 0) {
            0
        } else {
            (currentIndex + 1) % state.availableLenses.size
        }
        applyLens(state.availableLenses[nextIndex])
    }

    private fun applyLens(lens: Int) {
        val state = _uiState.value
        val capabilities = capabilitiesFor(lens)
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
            selectedCameraLens = lens,
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
        openedLens = null
        _uiState.value = nextState
        engine.setTorch(false)
        engine.updateManualControls(manualValues)
        engine.setManualControlsEnabled(manualMode)
        saveSettings(nextState)
        openPreviewForCurrentLens(force = true)
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
            capabilities = capabilitiesFor(state.selectedCameraLens),
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
                    errorMessage = "No local network address found"
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
                lensFacing = current.selectedCameraLens,
                resolution = current.selectedResolution,
                fps = current.selectedFps,
                bitrate = current.selectedBitrate,
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
        val capabilities = capabilitiesFor(state.selectedCameraLens)
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
            openPreviewForCurrentLens(force = true)
        }
    }

    fun selectFps(preset: FpsPreset) {
        val state = _uiState.value
        if (state.isStreaming || state.isBusy) return
        if (preset !in state.availableFpsOptions) return
        val capabilities = capabilitiesFor(state.selectedCameraLens)
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
        if (!state.hasCameraPermission) return "Camera permission denied"
        if (!state.hasLocalAddress) {
            return "No local network address found. Connect Wi-Fi or enable USB tethering."
        }
        if (state.availableResolutions.isEmpty()) {
            return "Camera capabilities are unavailable"
        }
        if (state.selectedResolution !in state.availableResolutions) {
            return "Selected resolution is not supported by the current camera"
        }
        if (state.selectedFps !in state.availableFpsOptions) {
            return "Selected frame rate is not supported for ${state.selectedResolution.label}"
        }
        val port = state.portInput.toIntOrNull()
        if (port == null || port !in 1..65535) {
            return "Enter a valid RTSP port between 1 and 65535"
        }
        if (state.rtspAuthEnabled && state.rtspUsername.isBlank()) {
            return "Enter an RTSP username or disable authentication"
        }
        if (state.rtspAuthEnabled && state.rtspPassword.isBlank()) {
            return "Enter an RTSP password or disable authentication"
        }
        return null
    }

    private fun capabilitiesFor(lensFacing: Int): CameraCapabilities =
        capabilitiesByLens.getOrPut(lensFacing) {
            capabilityReader.readCapabilities(lensFacing)
        }

    private fun buildInitialUiState(): RemCamUiState {
        val saved = readSavedSettings()
        val lenses = capabilityReader.availableLenses()
            .ifEmpty { listOf(saved.lens) }
        val lens = saved.lens.takeIf { it in lenses } ?: lenses.first()
        val capabilities = capabilitiesFor(lens)
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

        return RemCamUiState(
            selectedCameraLens = lens,
            sensorOrientation = capabilities.sensorOrientation,
            availableLenses = lenses,
            selectedResolution = resolution,
            selectedFps = fps,
            selectedBitrate = saved.bitrate,
            availableResolutions = resolutions,
            availableFpsOptions = fpsOptions,
            encoderMaxBitrateMbps = encoderMaxBitrateMbps,
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
        val lens = preferences.getInt(KEY_CAMERA_LENS, CameraCharacteristics.LENS_FACING_BACK)
        val resolution = parseResolution(preferences.getString(KEY_RESOLUTION, null))
            ?: ResolutionPreset(1280, 720)
        val fps = FpsPreset.entries.firstOrNull {
            it.name == preferences.getString(KEY_FPS, FpsPreset.FPS_30.name)
        } ?: FpsPreset.FPS_30
        val bitrate = BitratePreset.entries.firstOrNull {
            it.name == preferences.getString(KEY_BITRATE, BitratePreset.MBPS_6.name)
        } ?: BitratePreset.MBPS_6
        val port = preferences.getString(KEY_PORT, "8554").orEmpty().ifBlank { "8554" }
        return SavedSettings(
            lens = if (lens == CameraCharacteristics.LENS_FACING_FRONT) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            },
            resolution = resolution,
            fps = fps,
            bitrate = bitrate,
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
            .putInt(KEY_CAMERA_LENS, state.selectedCameraLens)
            .putString(
                KEY_RESOLUTION,
                "${state.selectedResolution.width}x${state.selectedResolution.height}"
            )
            .putString(KEY_FPS, state.selectedFps.name)
            .putString(KEY_BITRATE, state.selectedBitrate.name)
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
        val lens: Int,
        val resolution: ResolutionPreset,
        val fps: FpsPreset,
        val bitrate: BitratePreset,
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

private fun interfaceLabel(name: String): String = when {
    name.startsWith("wlan") -> "Wi-Fi"
    name.startsWith("rndis") || name.startsWith("usb") -> "USB tethering"
    name.startsWith("eth") -> "Ethernet"
    name.startsWith("ap") || name.startsWith("swlan") -> "Hotspot"
    name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("pdp") -> "Mobile data"
    name.startsWith("tun") || name.startsWith("ppp") -> "VPN"
    else -> "Network"
}

private fun interfacePriority(name: String): Int = when {
    name.startsWith("rndis") || name.startsWith("usb") -> 0
    name.startsWith("wlan") -> 1
    name.startsWith("eth") -> 2
    name.startsWith("ap") || name.startsWith("swlan") -> 3
    else -> 4
}

private const val PREFS_NAME = "remcam_settings"
private const val KEY_CAMERA_LENS = "camera_lens"
private const val KEY_RESOLUTION = "resolution"
private const val KEY_FPS = "fps"
private const val KEY_BITRATE = "bitrate"
private const val KEY_PORT = "port"
private const val KEY_AUTH_ENABLED = "rtsp_auth_enabled"
private const val KEY_AUTH_USERNAME = "rtsp_username"
private const val KEY_AUTH_PASSWORD = "rtsp_password"
