package com.remcam.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.remcam.R
import com.remcam.ui.theme.RemCamAccent
import com.remcam.ui.theme.RemCamOnAccent
import com.remcam.ui.theme.RemCamRed
import com.remcam.ui.theme.RemCamSurface
import com.remcam.ui.theme.RemCamSurfaceHigh
import com.remcam.ui.theme.RemCamText
import com.remcam.ui.theme.RemCamTextDim
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 7.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
fun CameraShutterButton(
    streaming: Boolean,
    busy: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(78.dp)
            .alpha(if (enabled || busy) 1f else 0.45f)
            .clip(CircleShape)
            .clickable(enabled = enabled && !busy, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(if (streaming) RemCamRed else RemCamAccent),
            contentAlignment = Alignment.Center
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = if (streaming) Color.White else RemCamOnAccent,
                    strokeWidth = 2.5.dp
                )
            } else {
                Icon(
                    imageVector = if (streaming) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (streaming) {
                        stringResource(R.string.stop_streaming)
                    } else {
                        stringResource(R.string.start_streaming)
                    },
                    tint = if (streaming) Color.White else RemCamOnAccent,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
fun ZoomControl(
    zoom: Float,
    maxZoom: Float,
    enabled: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val canZoom = enabled && maxZoom > 1.01f
    Row(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xB3000000))
            .alpha(if (canZoom) 1f else 0.45f),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ZoomStepButton(
            imageVector = Icons.Default.Remove,
            contentDescription = stringResource(R.string.zoom_out),
            enabled = canZoom && zoom > 1.01f,
            onClick = onZoomOut
        )
        Text(
            text = formatZoom(zoom),
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = canZoom && zoom > 1.01f, onClick = onReset),
            textAlign = TextAlign.Center,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
        ZoomStepButton(
            imageVector = Icons.Default.Add,
            contentDescription = stringResource(R.string.zoom_in),
            enabled = canZoom && zoom < maxZoom - 0.01f,
            onClick = onZoomIn
        )
    }
}

@Composable
private fun ZoomStepButton(
    imageVector: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .alpha(if (enabled) 1f else 0.3f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            modifier = Modifier.size(15.dp),
            tint = Color.White
        )
    }
}

@Composable
fun SelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) RemCamAccent else RemCamSurfaceHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) RemCamOnAccent else RemCamText,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1
        )
    }
}

fun formatZoom(zoom: Float): String {
    return if (abs(zoom - zoom.roundToInt()) < 0.05f) {
        "${zoom.roundToInt()}.0x"
    } else {
        String.format(Locale.US, "%.1fx", zoom)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier,
        color = RemCamTextDim,
        style = MaterialTheme.typography.labelMedium
    )
}

@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(RemCamSurface)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        content()
    }
}
