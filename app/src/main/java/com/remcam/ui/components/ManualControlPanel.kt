package com.remcam.ui.components

import android.hardware.camera2.CaptureRequest
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderColors
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remcam.R
import com.remcam.ManualCapabilities
import com.remcam.ManualControlValues
import com.remcam.ManualParameter
import com.remcam.MANUAL_DEFAULT_EXPOSURE_NS
import com.remcam.MANUAL_DEFAULT_ISO
import com.remcam.manualExposureMaxNs
import com.remcam.ui.theme.RemCamAccent
import com.remcam.ui.theme.RemCamBar
import com.remcam.ui.theme.RemCamSurfaceHigh
import com.remcam.ui.theme.RemCamText
import com.remcam.ui.theme.RemCamTextDim
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Manual panel: one live slider for the selected parameter, discrete chips for
 * white balance. Rows that the camera does not support are never shown.
 */
@Composable
fun ManualControlPanel(
    capabilities: ManualCapabilities,
    values: ManualControlValues,
    fps: Int,
    onValuesChanged: (ManualControlValues) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val parameters = remember(capabilities) {
        buildList {
            add(ManualParameter.ISO)
            add(ManualParameter.EXPOSURE)
            if (capabilities.supportsFocus) add(ManualParameter.FOCUS)
            if (capabilities.supportsWhiteBalance) add(ManualParameter.WHITE_BALANCE)
        }
    }
    var selected by remember(capabilities) {
        mutableStateOf(parameters.first())
    }
    val active = if (selected in parameters) selected else parameters.first()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(RemCamBar)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.manual_controls),
                color = RemCamText,
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.reset),
                color = RemCamAccent,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .clickable {
                        onValuesChanged(resetValue(active, capabilities, values, fps))
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
            IconButton(
                onClick = onHide,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.hide_manual_controls),
                    tint = RemCamTextDim,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            parameters.forEach { parameter ->
                SelectableChip(
                    label = stringResource(parameter.labelRes),
                    selected = parameter == active,
                    onClick = { selected = parameter }
                )
            }
        }

        when (active) {
            ManualParameter.ISO -> IsoControl(capabilities, values, onValuesChanged)
            ManualParameter.EXPOSURE -> ExposureControl(capabilities, values, fps, onValuesChanged)
            ManualParameter.FOCUS -> FocusControl(capabilities, values, onValuesChanged)
            ManualParameter.WHITE_BALANCE ->
                WhiteBalanceControl(capabilities, values, onValuesChanged)
        }
    }
}

@Composable
private fun IsoControl(
    capabilities: ManualCapabilities,
    values: ManualControlValues,
    onValuesChanged: (ManualControlValues) -> Unit
) {
    val min = capabilities.sensitivityRange.first.toDouble()
    val max = capabilities.sensitivityRange.last.toDouble()
    Column {
        ControlHeader(
            title = stringResource(R.string.param_iso),
            value = values.iso.toString()
        )
        Slider(
            value = logPosition(values.iso.toDouble(), min, max),
            onValueChange = { position ->
                val iso = logValue(position, min, max)
                    .roundToInt()
                    .coerceIn(capabilities.sensitivityRange.first, capabilities.sensitivityRange.last)
                onValuesChanged(values.copy(iso = iso))
            },
            valueRange = 0f..1f,
            enabled = max > min,
            colors = remCamSliderColors()
        )
    }
}

@Composable
private fun ExposureControl(
    capabilities: ManualCapabilities,
    values: ManualControlValues,
    fps: Int,
    onValuesChanged: (ManualControlValues) -> Unit
) {
    val min = capabilities.exposureTimeRangeNs.first.toDouble()
    val max = manualExposureMaxNs(
        sensorMaxNs = capabilities.exposureTimeRangeNs.last,
        fps = fps
    ).toDouble().coerceAtLeast(min)
    Column {
        ControlHeader(
            title = stringResource(R.string.param_exposure),
            value = formatExposureTime(values.exposureTimeNs)
        )
        Slider(
            value = logPosition(values.exposureTimeNs.toDouble(), min, max),
            onValueChange = { position ->
                val exposureNs = logValue(position, min, max)
                    .roundToLong()
                    .coerceIn(
                        capabilities.exposureTimeRangeNs.first,
                        max.toLong()
                    )
                onValuesChanged(values.copy(exposureTimeNs = exposureNs))
            },
            valueRange = 0f..1f,
            enabled = max > min,
            colors = remCamSliderColors()
        )
    }
}

@Composable
private fun FocusControl(
    capabilities: ManualCapabilities,
    values: ManualControlValues,
    onValuesChanged: (ManualControlValues) -> Unit
) {
    val maxFocus = capabilities.maxFocusDistanceDiopters ?: return
    if (maxFocus <= 0f) return
    Column {
        ControlHeader(
            title = stringResource(R.string.param_focus),
            value = formatFocusDistance(
                values.focusDistanceDiopters,
                stringResource(R.string.infinity)
            )
        )
        Slider(
            value = (values.focusDistanceDiopters / maxFocus).coerceIn(0f, 1f),
            onValueChange = { position ->
                onValuesChanged(
                    values.copy(focusDistanceDiopters = position.coerceIn(0f, 1f) * maxFocus)
                )
            },
            valueRange = 0f..1f,
            colors = remCamSliderColors()
        )
    }
}

@Composable
private fun WhiteBalanceControl(
    capabilities: ManualCapabilities,
    values: ManualControlValues,
    onValuesChanged: (ManualControlValues) -> Unit
) {
    val presets = capabilities.whiteBalancePresets
    if (presets.isEmpty()) return
    val selectedPreset = presets.firstOrNull { it.awbMode == values.awbMode }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ControlHeader(
            title = stringResource(R.string.param_white_balance),
            value = selectedPreset?.let { stringResource(it.labelRes) }
                ?: stringResource(R.string.white_balance_auto)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { preset ->
                SelectableChip(
                    label = stringResource(preset.labelRes),
                    selected = preset.awbMode == values.awbMode,
                    onClick = { onValuesChanged(values.copy(awbMode = preset.awbMode)) }
                )
            }
        }
    }
}

@Composable
private fun ControlHeader(title: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = RemCamText,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            color = RemCamAccent,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun remCamSliderColors(): SliderColors = SliderDefaults.colors(
    thumbColor = RemCamAccent,
    activeTrackColor = RemCamAccent,
    inactiveTrackColor = RemCamSurfaceHigh,
    activeTickColor = RemCamAccent,
    inactiveTickColor = RemCamTextDim
)

private val ManualParameter.labelRes: Int
    get() = when (this) {
        ManualParameter.ISO -> R.string.param_iso
        ManualParameter.EXPOSURE -> R.string.param_exposure
        ManualParameter.FOCUS -> R.string.param_focus
        ManualParameter.WHITE_BALANCE -> R.string.param_wb
    }

private fun resetValue(
    parameter: ManualParameter,
    capabilities: ManualCapabilities,
    values: ManualControlValues,
    fps: Int
): ManualControlValues = when (parameter) {
    ManualParameter.ISO -> values.copy(
        iso = MANUAL_DEFAULT_ISO.coerceIn(
            capabilities.sensitivityRange.first,
            capabilities.sensitivityRange.last
        )
    )

    ManualParameter.EXPOSURE -> {
        val min = capabilities.exposureTimeRangeNs.first
        val max = manualExposureMaxNs(
            sensorMaxNs = capabilities.exposureTimeRangeNs.last,
            fps = fps
        ).coerceAtLeast(min)
        values.copy(
            exposureTimeNs = MANUAL_DEFAULT_EXPOSURE_NS.coerceIn(min, max)
        )
    }

    ManualParameter.FOCUS -> values.copy(focusDistanceDiopters = 0f)
    ManualParameter.WHITE_BALANCE -> values.copy(
        awbMode = capabilities.whiteBalancePresets
            .firstOrNull { it.awbMode == CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT }
            ?.awbMode
            ?: capabilities.whiteBalancePresets.firstOrNull()?.awbMode
    )
}

private fun formatExposureTime(nanoseconds: Long): String = when {
    nanoseconds >= 1_000_000_000L ->
        String.format(Locale.US, "%.2f s", nanoseconds / 1_000_000_000.0)

    nanoseconds >= 1_000_000L ->
        String.format(Locale.US, "%.1f ms", nanoseconds / 1_000_000.0)

    else ->
        String.format(Locale.US, "%.0f us", nanoseconds / 1_000.0)
}

private fun formatFocusDistance(diopters: Float, infinityLabel: String): String {
    if (diopters <= 0.01f) return infinityLabel
    val centimeters = 100f / diopters
    return if (centimeters >= 100f) {
        String.format(Locale.US, "%.1f m", centimeters / 100f)
    } else {
        String.format(Locale.US, "%.0f cm", centimeters)
    }
}

private fun logPosition(value: Double, min: Double, max: Double): Float {
    val safeMin = min.coerceAtLeast(1.0)
    val safeMax = max.coerceAtLeast(safeMin * 1.0001)
    val clamped = value.coerceIn(safeMin, safeMax)
    return ((ln(clamped) - ln(safeMin)) / (ln(safeMax) - ln(safeMin)))
        .toFloat()
        .coerceIn(0f, 1f)
}

private fun logValue(position: Float, min: Double, max: Double): Double {
    val safeMin = min.coerceAtLeast(1.0)
    val safeMax = max.coerceAtLeast(safeMin * 1.0001)
    val fraction = position.coerceIn(0f, 1f).toDouble()
    return exp(ln(safeMin) + fraction * (ln(safeMax) - ln(safeMin)))
        .coerceIn(safeMin, safeMax)
}
