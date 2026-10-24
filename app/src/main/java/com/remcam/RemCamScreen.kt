package com.remcam

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lens
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remcam.ui.components.CameraShutterButton
import com.remcam.ui.components.ManualControlPanel
import com.remcam.ui.components.RemCamSettingsSheet
import com.remcam.ui.components.StatusDot
import com.remcam.ui.components.ZoomControl
import com.remcam.ui.theme.RemCamAccent
import com.remcam.ui.theme.RemCamAmber
import com.remcam.ui.theme.RemCamBlack
import com.remcam.ui.theme.RemCamGreen
import com.remcam.ui.theme.RemCamOnAccent
import com.remcam.ui.theme.RemCamRed
import com.remcam.ui.theme.RemCamText
import com.remcam.ui.theme.RemCamTextDim
import com.remcam.ui.theme.RemCamTheme
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@Composable
fun RemCamScreen(viewModel: RemCamViewModel) {
    RemCamTheme {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val clipboardManager = LocalClipboardManager.current
        val density = LocalDensity.current
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        // The activity is portrait-locked and SurfaceTexture already applies
        // the sensor orientation, so only the sensor rotation matters here.
        val relativeRotation = ((state.sensorOrientation % 360) + 360) % 360
        val previewView = remember { TextureView(context) }
        var showSettings by remember { mutableStateOf(false) }
        var showUrlBanner by remember { mutableStateOf(false) }
        var focusPoint by remember { mutableStateOf<Offset?>(null) }
        var focusRequestKey by remember { mutableIntStateOf(0) }
        val focusAlpha = remember { Animatable(0f) }
        val focusScale = remember { Animatable(0.72f) }
        val focusRingSize = 72.dp
        val focusRingSizePx = with(density) { focusRingSize.toPx() }
        val copiedMessage = stringResource(R.string.copied_to_clipboard)

        fun copyUrl(text: String) {
            if (text.isBlank()) return
            clipboardManager.setText(AnnotatedString(text))
            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
        }

        val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { /* Best effort; local sockets work on this device's OS version. */ }

        fun requestLocalNetworkPermission() {
            if (Build.VERSION.SDK_INT >= 37) {
                val granted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_LOCAL_NETWORK
                ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
                }
            }
        }

        val cameraPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { granted ->
            viewModel.onCameraPermissionResult(granted)
            if (granted) requestLocalNetworkPermission()
        }

        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) {
            // Start regardless of the answer: the foreground service is still
            // allowed to run, the notification is simply not shown.
            viewModel.startStreaming()
        }

        fun startStreamingWithNotificationPermission() {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                viewModel.startStreaming()
            }
        }

        DisposableEffect(previewView) {
            viewModel.onPreviewAttached(previewView)
            onDispose { viewModel.onPreviewDetached(previewView) }
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> viewModel.onScreenVisible()
                    Lifecycle.Event.ON_STOP -> viewModel.onScreenHidden()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                viewModel.onScreenHidden()
            }
        }

        LaunchedEffect(Unit) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            viewModel.onCameraPermissionResult(granted)
            if (granted) {
                requestLocalNetworkPermission()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        DisposableEffect(state.isStreaming) {
            previewView.keepScreenOn = state.isStreaming
            onDispose { previewView.keepScreenOn = false }
        }

        LaunchedEffect(focusRequestKey) {
            if (focusPoint == null) return@LaunchedEffect
            focusAlpha.snapTo(1f)
            focusScale.snapTo(0.72f)
            focusScale.animateTo(1f, tween(150, easing = FastOutSlowInEasing))
            delay(550)
            focusAlpha.animateTo(0f, tween(650, easing = LinearEasing))
            focusPoint = null
        }

        LaunchedEffect(state.isStreaming, state.streamUrl) {
            if (state.isStreaming && state.streamUrl.isNotBlank()) {
                showUrlBanner = true
                delay(5_000)
                showUrlBanner = false
            } else {
                showUrlBanner = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(RemCamBlack)
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val resolution = state.selectedResolution
                val rotatedDisplay = relativeRotation % 180 != 0
                val bufferWidth = state.previewWidth.takeIf { it > 0 } ?: resolution.width
                val bufferHeight = state.previewHeight.takeIf { it > 0 } ?: resolution.height
                val videoAspect = if (rotatedDisplay) {
                    bufferHeight.toFloat() / bufferWidth.toFloat()
                } else {
                    bufferWidth.toFloat() / bufferHeight.toFloat()
                }
                val availableAspect = if (maxHeight.value > 0f) {
                    maxWidth.value / maxHeight.value
                } else {
                    1f
                }
                // Contain: the complete camera frame is shown, never cropped.
                val displayWidth = if (availableAspect > videoAspect) {
                    maxHeight * videoAspect
                } else {
                    maxWidth
                }
                val displayHeight = if (availableAspect > videoAspect) {
                    maxHeight
                } else {
                    maxWidth / videoAspect
                }
                val displayWidthPx = with(density) { displayWidth.toPx() }
                val displayHeightPx = with(density) { displayHeight.toPx() }
                // The TextureView is laid out at the real camera buffer size so
                // TextureView.onSizeChanged keeps the SurfaceTexture at the
                // camera output size. The layer then un-stretches the surface
                // rotation by scaling the frame to the display box.
                val frameScaleX = displayWidthPx / bufferWidth.toFloat()
                val frameScaleY = displayHeightPx / bufferHeight.toFloat()
                val bufferWidthDp = with(density) { bufferWidth.toDp() }
                val bufferHeightDp = with(density) { bufferHeight.toDp() }

                Box(
                    modifier = Modifier
                        .size(displayWidth, displayHeight)
                        .clipToBounds()
                ) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .requiredSize(bufferWidthDp, bufferHeightDp)
                            .graphicsLayer {
                                scaleX = frameScaleX
                                scaleY = frameScaleY
                            }
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(
                                state.cameraReady,
                                state.isStreaming,
                                state.tapToFocusSupported,
                                state.manualMode,
                                relativeRotation,
                                displayWidthPx,
                                displayHeightPx
                            ) {
                                if ((state.cameraReady || state.isStreaming) &&
                                    state.tapToFocusSupported &&
                                    !state.manualMode
                                ) {
                                    detectTapGestures { offset ->
                                        val u = offset.x / displayWidthPx
                                        val v = offset.y / displayHeightPx
                                        if (u !in 0f..1f || v !in 0f..1f) {
                                            return@detectTapGestures
                                        }
                                        val bufferX: Float
                                        val bufferY: Float
                                        when (relativeRotation) {
                                            90 -> {
                                                bufferX = v
                                                bufferY = 1f - u
                                            }

                                            180 -> {
                                                bufferX = 1f - u
                                                bufferY = 1f - v
                                            }

                                            270 -> {
                                                bufferX = 1f - v
                                                bufferY = u
                                            }

                                            else -> {
                                                bufferX = u
                                                bufferY = v
                                            }
                                        }
                                        if (viewModel.tapToFocus(
                                                bufferX.coerceIn(0f, 1f),
                                                bufferY.coerceIn(0f, 1f)
                                            )
                                        ) {
                                            focusPoint = offset
                                            focusRequestKey++
                                        }
                                    }
                                }
                            }
                            .pointerInput(
                                state.cameraReady,
                                state.maxZoom,
                                state.isStreaming
                            ) {
                                if ((state.cameraReady || state.isStreaming) &&
                                    state.maxZoom > 1.01f
                                ) {
                                    detectTransformGestures { _, _, zoom, _ ->
                                        if (zoom != 1f) viewModel.zoomBy(zoom)
                                    }
                                }
                            }
                    )

                    focusPoint?.let { point ->
                        Box(
                            modifier = Modifier
                                .offset {
                                    IntOffset(
                                        (point.x - focusRingSizePx / 2f).roundToInt(),
                                        (point.y - focusRingSizePx / 2f).roundToInt()
                                    )
                                }
                                .size(focusRingSize)
                                .graphicsLayer {
                                    alpha = focusAlpha.value
                                    scaleX = focusScale.value
                                    scaleY = focusScale.value
                                }
                                .border(2.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(4.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                            )
                        }
                    }
                }
            }

            if (state.hasCameraPermission) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xCC000000),
                                    Color(0x66000000),
                                    Color.Transparent
                                )
                            )
                        )
                ) {
                    TopControlBar(
                        state = state,
                        onToggleTorch = viewModel::toggleTorch,
                        onSelectCamera = viewModel::selectCamera,
                        onOpenSettings = { showSettings = true }
                    )
                    if (state.errorMessage != null) {
                        ErrorBanner(
                            message = state.errorMessage.orEmpty(),
                            onDismiss = viewModel::dismissError
                        )
                    }
                    if (showUrlBanner) {
                        LiveUrlBanner(
                            url = state.streamUrl,
                            onCopy = { copyUrl(state.streamUrl) }
                        )
                    }
                }

                BottomControlArea(
                    state = state,
                    viewModel = viewModel,
                    onStart = ::startStreamingWithNotificationPermission,
                    onStop = viewModel::stopStreaming,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )

                if (!state.cameraReady && !state.isStreaming && !state.isStarting) {
                    CameraStateOverlay(
                        errorMessage = state.errorMessage,
                        onRetry = viewModel::retryPreview
                    )
                }
            } else {
                PermissionOverlay(
                    onRequest = {
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    }
                )
            }

            if (showSettings) {
                RemCamSettingsSheet(
                    state = state,
                    viewModel = viewModel,
                    onDismiss = { showSettings = false }
                )
            }
        }
    }
}

@Composable
private fun TopControlBar(
    state: RemCamUiState,
    onToggleTorch: () -> Unit,
    onSelectCamera: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val statusText = when {
        state.isStreaming -> stringResource(R.string.status_live)
        state.isStarting -> stringResource(R.string.status_starting)
        state.isStopping -> stringResource(R.string.status_stopping)
        state.errorMessage != null -> stringResource(R.string.status_error)
        state.cameraReady -> stringResource(R.string.status_ready)
        else -> stringResource(R.string.status_camera_off)
    }
    val lensLabel = state.lensLabel.ifBlank { stringResource(R.string.camera_fallback) }
    val statusColor = when {
        state.isStreaming -> RemCamGreen
        state.isStarting || state.isStopping -> RemCamAmber
        state.errorMessage != null -> RemCamRed
        else -> RemCamTextDim
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(60.dp)
            .padding(horizontal = 8.dp)
    ) {
        if (state.availableCameras.size > 1) {
            CameraLensMenu(
                state = state,
                onSelectCamera = onSelectCamera,
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .clip(RoundedCornerShape(50))
                .background(Color(0x99000000))
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(color = statusColor, size = 7.dp)
            Spacer(Modifier.width(6.dp))
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                color = RemCamText,
                maxLines = 1
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(
                    R.string.status_lens_resolution,
                    lensLabel,
                    state.selectedResolution.height
                ),
                style = MaterialTheme.typography.labelSmall,
                color = RemCamTextDim,
                maxLines = 1
            )
        }

        Row(
            modifier = Modifier.align(Alignment.CenterEnd),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.flashAvailable) {
                IconButton(
                    onClick = onToggleTorch,
                    enabled = state.cameraReady || state.isStreaming
                ) {
                    Icon(
                        imageVector = if (state.torchEnabled) {
                            Icons.Default.FlashOn
                        } else {
                            Icons.Default.FlashOff
                        },
                        contentDescription = if (state.torchEnabled) {
                            stringResource(R.string.torch_off)
                        } else {
                            stringResource(R.string.torch_on)
                        },
                        tint = when {
                            !(state.cameraReady || state.isStreaming) ->
                                Color.White.copy(alpha = 0.35f)

                            state.torchEnabled -> RemCamAccent
                            else -> Color.White
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = stringResource(R.string.settings),
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun CameraLensMenu(
    state: RemCamUiState,
    onSelectCamera: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val enabled = !state.isStreaming && !state.isBusy
    Box(
        modifier = modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.5f)
    ) {
        IconButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                Icons.Default.Lens,
                contentDescription = stringResource(R.string.select_camera),
                tint = if (enabled) Color.White else Color.White.copy(alpha = 0.35f),
                modifier = Modifier.size(22.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            state.availableCameras.forEach { camera ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = camera.label,
                                color = if (camera.id == state.selectedCameraId) {
                                    RemCamAccent
                                } else {
                                    RemCamText
                                },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (camera.detail.isNotBlank()) {
                                Text(
                                    text = camera.detail,
                                    color = RemCamTextDim,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        if (camera.id == state.selectedCameraId) {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = RemCamAccent,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelectCamera(camera.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun BottomControlArea(
    state: RemCamUiState,
    viewModel: RemCamViewModel,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val streaming = state.isStreaming
    Column(modifier = modifier.fillMaxWidth()) {
        if (state.manualMode && state.manualCapabilities != null && state.manualPanelVisible) {
            ManualControlPanel(
                capabilities = state.manualCapabilities,
                values = state.manualValues,
                fps = state.selectedFps.fps,
                onValuesChanged = viewModel::updateManualValues,
                onHide = viewModel::hideManualPanel
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    ModePill(
                        manualMode = state.manualMode,
                        autoEnabled = !state.isBusy,
                        manualEnabled = state.supportsManual &&
                            (state.cameraReady || state.isStreaming) &&
                            !state.isBusy,
                        onModeChange = viewModel::setManualMode
                    )
                }

                CameraShutterButton(
                    streaming = streaming,
                    busy = state.isBusy,
                    enabled = state.hasCameraPermission,
                    onClick = {
                        if (streaming) onStop() else onStart()
                    }
                )

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    ZoomControl(
                        zoom = state.zoom,
                        maxZoom = state.maxZoom,
                        enabled = state.cameraReady || streaming,
                        onZoomIn = { viewModel.zoomBy(1.5f) },
                        onZoomOut = { viewModel.zoomBy(1f / 1.5f) },
                        onReset = viewModel::resetZoom,
                        modifier = Modifier.width(104.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ModePill(
    manualMode: Boolean,
    autoEnabled: Boolean,
    manualEnabled: Boolean,
    onModeChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .size(width = 104.dp, height = 34.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xB3000000))
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ModePillSegment(
            label = stringResource(R.string.auto),
            selected = !manualMode,
            enabled = autoEnabled,
            modifier = Modifier.weight(1f),
            onClick = { onModeChange(false) }
        )
        ModePillSegment(
            label = stringResource(R.string.manual),
            selected = manualMode,
            enabled = manualEnabled,
            modifier = Modifier.weight(1f),
            onClick = { onModeChange(true) }
        )
    }
}

@Composable
private fun ModePillSegment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(50))
            .background(if (selected && enabled) RemCamAccent else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> RemCamTextDim.copy(alpha = 0.4f)
                selected -> RemCamOnAccent
                else -> RemCamText
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LiveUrlBanner(
    url: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xB3000000))
            .padding(start = 12.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(color = RemCamGreen, size = 7.dp)
        Spacer(Modifier.size(8.dp))
        Text(
            text = url,
            style = MaterialTheme.typography.labelMedium,
            color = RemCamText,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onCopy,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.copy_stream_url),
                tint = RemCamTextDim,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = RemCamText,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.dismiss_error),
                tint = RemCamTextDim,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun PermissionOverlay(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(RemCamBlack),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = RemCamAccent,
                modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.permission_title),
                style = MaterialTheme.typography.titleMedium,
                color = RemCamText
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.permission_body),
                style = MaterialTheme.typography.bodySmall,
                color = RemCamTextDim,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))
            Button(
                onClick = onRequest,
                shape = RoundedCornerShape(50)
            ) {
                Text(stringResource(R.string.permission_button))
            }
        }
    }
}

@Composable
private fun CameraStateOverlay(
    errorMessage: String?,
    onRetry: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xAA000000))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (errorMessage == null) {
                CircularProgressIndicator(color = RemCamAccent)
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.opening_camera),
                    style = MaterialTheme.typography.bodySmall,
                    color = RemCamTextDim
                )
            } else {
                Text(
                    text = stringResource(R.string.camera_unavailable),
                    style = MaterialTheme.typography.titleSmall,
                    color = RemCamText
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = RemCamTextDim,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onRetry,
                    shape = RoundedCornerShape(50)
                ) {
                    Text(stringResource(R.string.retry))
                }
            }
        }
    }
}
