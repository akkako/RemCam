package com.remcam.streaming

import android.content.Context
import android.graphics.PointF
import androidx.annotation.StringRes
import android.view.TextureView
import android.util.Log
import com.remcam.BitratePreset
import com.remcam.R
import com.remcam.FpsPreset
import com.remcam.ManualControlValues
import com.remcam.RemCamStreamService
import com.remcam.ResolutionPreset
import com.remcam.StreamStatus
import com.remcam.VideoCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class StreamRequest(
    val cameraId: String,
    val resolution: ResolutionPreset,
    val fps: FpsPreset,
    val bitrate: BitratePreset,
    val codec: VideoCodec,
    val port: Int,
    val hostAddress: String,
    val authEnabled: Boolean,
    val authUsername: String,
    val authPassword: String
)

data class RemCamEngineState(
    val cameraReady: Boolean = false,
    val streamStatus: StreamStatus = StreamStatus.NOT_STREAMING,
    val streamUrl: String? = null,
    val errorMessage: String? = null,
    val errorId: Long = 0L,
    val manualFallbackId: Long = 0L,
    val previewWidth: Int = 0,
    val previewHeight: Int = 0
)

/**
 * Application-scoped camera/streaming engine.
 *
 * It outlives the Activity and its ViewModel: pressing Home, opening recents or
 * turning the screen off does not stop the camera, encoder, RTSP server or the
 * stream. The foreground service started by [startStreaming] keeps the process
 * alive and the UI observes [state] to re-sync after returning.
 */
class RemCamEngine private constructor(private val appContext: Context) {

    private val cameraManager = CameraStreamManager(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(RemCamEngineState())
    val state: StateFlow<RemCamEngineState> = _state.asStateFlow()

    private var streamGeneration = 0

    @Volatile
    private var uiVisible = false

    init {
        cameraManager.listener = object : CameraStreamManager.Listener {
            override fun onManualControlsFailed(message: String) {
                _state.update {
                    it.copy(
                        errorMessage = message,
                        errorId = it.errorId + 1,
                        manualFallbackId = it.manualFallbackId + 1
                    )
                }
            }

            override fun onCameraRequestFailed(message: String) {
                _state.update {
                    it.copy(errorMessage = message, errorId = it.errorId + 1)
                }
            }

            override fun onCameraDisconnected(message: String) {
                ++streamGeneration
                _state.update {
                    it.copy(
                        cameraReady = false,
                        streamStatus = StreamStatus.NOT_STREAMING,
                        streamUrl = null,
                        errorMessage = message,
                        errorId = it.errorId + 1
                    )
                }
                scope.launch {
                    runCatching { cameraManager.closeCamera() }
                }
                RemCamStreamService.stop(appContext)
            }
        }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        appContext.getString(id, *args)

    companion object {
        @Volatile
        private var instance: RemCamEngine? = null

        fun get(context: Context): RemCamEngine {
            val existing = instance
            if (existing != null) return existing
            return synchronized(this) {
                instance ?: RemCamEngine(context.applicationContext).also { instance = it }
            }
        }
    }

    // ------------------------------------------------------------------
    // UI lifecycle / preview attachment
    // ------------------------------------------------------------------

    fun onUiVisible() {
        uiVisible = true
        cameraManager.resumePreview()
    }

    fun onUiHidden() {
        uiVisible = false
        cameraManager.suspendPreview()
    }

    fun attachPreview(view: TextureView) {
        cameraManager.attachPreview(view)
    }

    fun detachPreview(view: TextureView) {
        cameraManager.detachPreview(view)
        if (_state.value.streamStatus != StreamStatus.STREAMING) {
            _state.update { it.copy(cameraReady = false) }
        }
    }

    fun onViewModelCleared() {
        val status = _state.value.streamStatus
        if (status != StreamStatus.STREAMING &&
            status != StreamStatus.STARTING &&
            status != StreamStatus.STOPPING
        ) {
            scope.launch { runCatching { cameraManager.closeCamera() } }
            _state.update { it.copy(cameraReady = false) }
        }
    }

    // ------------------------------------------------------------------
    // Preview camera
    // ------------------------------------------------------------------

    suspend fun openPreview(
        cameraId: String,
        fps: Int,
        resolution: ResolutionPreset
    ): Result<Unit> {
        if (isStreamBusy()) return Result.success(Unit)
        return try {
            cameraManager.openPreview(cameraId, fps, resolution)
            _state.update {
                it.copy(
                    cameraReady = true,
                    errorMessage = null,
                    previewWidth = cameraManager.getPreviewWidth(),
                    previewHeight = cameraManager.getPreviewHeight()
                )
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            _state.update {
                it.copy(
                    cameraReady = false,
                    errorMessage = error.message ?: str(R.string.error_unable_open_camera),
                    errorId = it.errorId + 1
                )
            }
            Result.failure(error)
        }
    }

    suspend fun closeCamera() {
        cameraManager.closeCamera()
        _state.update { it.copy(cameraReady = false) }
    }

    // ------------------------------------------------------------------
    // Streaming
    // ------------------------------------------------------------------

    fun startStreaming(request: StreamRequest, url: String) {
        val status = _state.value.streamStatus
        if (status == StreamStatus.STARTING ||
            status == StreamStatus.STREAMING ||
            status == StreamStatus.STOPPING
        ) {
            return
        }
        val generation = ++streamGeneration
        _state.update {
            it.copy(streamStatus = StreamStatus.STARTING, streamUrl = url, errorMessage = null)
        }
        Log.i(TAG_ENGINE, "Starting stream: $url")
        RemCamStreamService.start(appContext, url)

        scope.launch {
            val result = runCatching {
                cameraManager.startStreaming(
                    cameraId = request.cameraId,
                    resolution = request.resolution,
                    fps = request.fps,
                    bitrate = request.bitrate,
                    codec = request.codec,
                    port = request.port,
                    hostAddress = request.hostAddress,
                    authEnabled = request.authEnabled,
                    authUsername = request.authUsername,
                    authPassword = request.authPassword
                )
            }
            if (generation != streamGeneration) {
                Log.i(TAG_ENGINE, "Stream start superseded by stop request")
                runCatching { cameraManager.stopStreaming(keepPreview = uiVisible) }
                return@launch
            }
            result.onSuccess { startedUrl ->
                Log.i(TAG_ENGINE, "Stream running at $startedUrl")
                _state.update {
                    it.copy(
                        streamStatus = StreamStatus.STREAMING,
                        streamUrl = startedUrl,
                        cameraReady = true,
                        errorMessage = null,
                        previewWidth = cameraManager.getPreviewWidth(),
                        previewHeight = cameraManager.getPreviewHeight()
                    )
                }
            }.onFailure { error ->
                if (error is CancellationException) return@onFailure
                Log.e(TAG_ENGINE, "Stream start failed: ${error.message}", error)
                RemCamStreamService.stop(appContext)
                _state.update {
                    it.copy(
                        streamStatus = StreamStatus.ERROR,
                        streamUrl = null,
                        cameraReady = cameraManager.isCameraOpen(),
                        errorMessage = error.message ?: str(R.string.error_failed_start_stream),
                        errorId = it.errorId + 1,
                        previewWidth = cameraManager.getPreviewWidth(),
                        previewHeight = cameraManager.getPreviewHeight()
                    )
                }
            }
        }
    }

    fun stopStreaming() {
        val status = _state.value.streamStatus
        if (status == StreamStatus.STOPPING) return
        if (status == StreamStatus.NOT_STREAMING) {
            RemCamStreamService.stop(appContext)
            if (!uiVisible) {
                scope.launch { runCatching { cameraManager.closeCamera() } }
                _state.update { it.copy(cameraReady = false) }
            }
            return
        }
        ++streamGeneration
        _state.update { it.copy(streamStatus = StreamStatus.STOPPING) }
        Log.i(TAG_ENGINE, "Stopping stream")
        scope.launch {
            runCatching { cameraManager.stopStreaming(keepPreview = uiVisible) }
            val cameraOpen = cameraManager.isCameraOpen()
            _state.update {
                it.copy(
                    streamStatus = StreamStatus.NOT_STREAMING,
                    streamUrl = null,
                    cameraReady = cameraOpen
                )
            }
            RemCamStreamService.stop(appContext)
            Log.i(TAG_ENGINE, "Stream stopped")
        }
    }

    fun clearError() {
        _state.update {
            if (it.streamStatus == StreamStatus.ERROR) {
                it.copy(streamStatus = StreamStatus.NOT_STREAMING, errorMessage = null)
            } else {
                it.copy(errorMessage = null)
            }
        }
    }

    // ------------------------------------------------------------------
    // Camera controls
    // ------------------------------------------------------------------

    fun setZoom(ratio: Float): Float = cameraManager.setZoom(ratio)

    fun scaleZoomBy(factor: Float): Float = cameraManager.scaleZoomBy(factor)

    fun resetZoom(): Float = cameraManager.resetZoom()

    fun getCurrentZoomRatio(): Float = cameraManager.getCurrentZoomRatio()

    fun getMaxZoomRatio(): Float = cameraManager.getMaxZoomRatio()

    fun setTorch(enabled: Boolean) = cameraManager.setTorch(enabled)

    fun setManualControlsEnabled(enabled: Boolean) =
        cameraManager.setManualControlsEnabled(enabled)

    /** Current Auto exposure/ISO/focus/white-balance, or null before the first frame. */
    fun snapshotAutoManualValues(): ManualControlValues? =
        cameraManager.snapshotAutoValues()

    /** Applies the manual flag and values atomically, in a single request. */
    fun applyManualControls(enabled: Boolean, values: ManualControlValues) =
        cameraManager.applyManualControls(enabled, values)

    fun updateManualControls(values: ManualControlValues) =
        cameraManager.updateManualControls(values)

    /**
     * @return true when an AF scan was requested. The caller can then show the
     * focus ring; fixed-focus cameras and Manual mode return false.
     */
    fun focusAtBufferPoint(point: PointF): Boolean =
        cameraManager.focusAtBufferPoint(point.x, point.y)

    private fun isStreamBusy(): Boolean {
        val status = _state.value.streamStatus
        return status == StreamStatus.STARTING ||
            status == StreamStatus.STREAMING ||
            status == StreamStatus.STOPPING
    }
}

private const val TAG_ENGINE = "REM_CAM_ENGINE"
