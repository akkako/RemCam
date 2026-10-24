package com.remcam.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remcam.BitratePreset
import com.remcam.R
import com.remcam.BuildConfig
import com.remcam.FpsPreset
import com.remcam.LocalAddress
import com.remcam.RemCamUiState
import com.remcam.RemCamViewModel
import com.remcam.ResolutionPreset
import com.remcam.VideoCodecOption
import com.remcam.ui.theme.RemCamAccent
import com.remcam.ui.theme.RemCamBar
import com.remcam.ui.theme.RemCamGreen
import com.remcam.ui.theme.RemCamSurface
import com.remcam.ui.theme.RemCamSurfaceHigh
import com.remcam.ui.theme.RemCamText
import com.remcam.ui.theme.RemCamTextDim
import java.net.URLEncoder
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemCamSettingsSheet(
    state: RemCamUiState,
    viewModel: RemCamViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val editable = !state.isStreaming && !state.isBusy
    val cameraLabel = state.lensLabel.ifBlank { stringResource(R.string.camera_fallback) }
    val selectedCodecOption = state.availableCodecs
        .firstOrNull { it.codec == state.selectedCodec }
    val copiedMessage = stringResource(R.string.copied_to_clipboard)
    val port = state.portInput.toIntOrNull()
    val authenticatedUrl = authenticatedRtspUrl(
        baseUrl = state.streamUrl,
        username = state.rtspUsername,
        password = state.rtspPassword
    )

    fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        clipboardManager.setText(AnnotatedString(text))
        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = RemCamBar,
        contentColor = RemCamText
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = RemCamText
                    )
                    Text(
                        text = stringResource(R.string.settings_subtitle, cameraLabel),
                        style = MaterialTheme.typography.labelMedium,
                        color = RemCamTextDim
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close_settings))
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.section_rtsp_stream))
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (state.rtspAuthEnabled) {
                                    stringResource(R.string.stream_url_authenticated)
                                } else {
                                    stringResource(R.string.stream_url_no_auth)
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = RemCamTextDim
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = state.streamUrl.ifBlank { stringResource(R.string.no_local_address) },
                                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                                color = if (state.streamUrl.isBlank()) RemCamTextDim else RemCamText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { copyToClipboard(state.streamUrl) },
                            enabled = state.streamUrl.isNotBlank()
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = stringResource(R.string.copy_stream_url))
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = viewModel::refreshLocalNetwork,
                            shape = RoundedCornerShape(50)
                        ) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.refresh_network))
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (state.localAddresses.isEmpty()) {
                                stringResource(R.string.no_ipv4_addresses)
                            } else {
                                stringResource(R.string.address_count, state.localAddresses.size)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.localAddresses.isEmpty()) {
                                MaterialTheme.colorScheme.error
                            } else {
                                RemCamTextDim
                            }
                        )
                    }

                    if (state.localAddresses.isEmpty()) {
                        Text(
                            text = stringResource(R.string.no_network_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    } else {
                        HorizontalDivider(color = RemCamSurfaceHigh)
                        state.localAddresses.forEachIndexed { index, address ->
                            AddressRow(
                                address = address,
                                port = port,
                                onCopy = { url -> copyToClipboard(url) }
                            )
                            if (index != state.localAddresses.lastIndex) {
                                HorizontalDivider(color = RemCamSurfaceHigh)
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.section_authentication))
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.basic_auth),
                                style = MaterialTheme.typography.bodyMedium,
                                color = RemCamText
                            )
                            Text(
                                text = if (state.rtspAuthEnabled) {
                                    stringResource(R.string.auth_required_desc)
                                } else {
                                    stringResource(R.string.auth_disabled_desc)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = RemCamTextDim
                            )
                        }
                        Switch(
                            checked = state.rtspAuthEnabled,
                            onCheckedChange = viewModel::setRtspAuthEnabled,
                            enabled = editable
                        )
                    }
                    if (state.rtspAuthEnabled) {
                        OutlinedTextField(
                            value = state.rtspUsername,
                            onValueChange = viewModel::updateRtspUsername,
                            label = { Text(stringResource(R.string.username)) },
                            singleLine = true,
                            enabled = editable,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = state.rtspPassword,
                            onValueChange = viewModel::updateRtspPassword,
                            label = { Text(stringResource(R.string.password)) },
                            singleLine = true,
                            enabled = editable,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedButton(
                            onClick = { copyToClipboard(authenticatedUrl) },
                            enabled = authenticatedUrl.isNotBlank() &&
                                state.rtspUsername.isNotBlank() &&
                                state.rtspPassword.isNotBlank(),
                            shape = RoundedCornerShape(50)
                        ) {
                            Icon(
                                Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.copy_authenticated_url))
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.section_camera))
                SettingsCard {
                    Text(
                        text = stringResource(R.string.selected_camera),
                        style = MaterialTheme.typography.labelSmall,
                        color = RemCamTextDim
                    )
                    Text(
                        text = stringResource(R.string.camera_with_id, cameraLabel, state.selectedCameraId),
                        style = MaterialTheme.typography.bodyMedium,
                        color = RemCamText
                    )
                    if (state.selectedCameraDetail.isNotBlank()) {
                        Text(
                            text = state.selectedCameraDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = RemCamTextDim
                        )
                    }
                    Text(
                        text = stringResource(R.string.switch_lenses_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = RemCamTextDim
                    )
                    HorizontalDivider(color = RemCamSurfaceHigh)
                    Text(
                        text = stringResource(R.string.supported_resolutions),
                        style = MaterialTheme.typography.labelSmall,
                        color = RemCamTextDim
                    )
                    Text(
                        text = if (state.availableResolutions.isEmpty()) {
                            stringResource(R.string.not_available)
                        } else {
                            state.availableResolutions.joinToString("  ·  ") { it.label }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = RemCamText
                    )
                    Text(
                        text = stringResource(R.string.frame_rates_at, state.selectedResolution.label),
                        style = MaterialTheme.typography.labelSmall,
                        color = RemCamTextDim
                    )
                    Text(
                        text = state.availableFpsOptions.joinToString(" / ") { it.label },
                        style = MaterialTheme.typography.bodySmall,
                        color = RemCamText
                    )
                    state.encoderMaxBitrateMbps?.let { maxMbps ->
                        Text(
                            text = stringResource(R.string.encoder_max_bitrate, maxMbps),
                            style = MaterialTheme.typography.labelSmall,
                            color = RemCamTextDim
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.section_stream_settings))
                SettingsCard {
                    OutlinedTextField(
                        value = state.portInput,
                        onValueChange = viewModel::updatePortInput,
                        label = { Text(stringResource(R.string.rtsp_port)) },
                        singleLine = true,
                        enabled = editable,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (selectedCodecOption != null) {
                        SettingDropdown(
                            label = stringResource(R.string.video_codec),
                            selected = selectedCodecOption,
                            options = state.availableCodecs,
                            optionLabel = { option ->
                                val kind = if (option.hardware) {
                                    stringResource(R.string.codec_hardware)
                                } else {
                                    stringResource(R.string.codec_software)
                                }
                                "${stringResource(option.codec.labelRes)} · $kind"
                            },
                            enabled = editable && state.availableCodecs.size > 1,
                            onSelected = { viewModel.selectCodec(it.codec) }
                        )
                    }
                    SettingDropdown(
                        label = stringResource(R.string.resolution),
                        selected = state.selectedResolution,
                        options = state.availableResolutions,
                        optionLabel = { "${it.label} · ${it.aspectRatioLabel}" },
                        enabled = editable && state.availableResolutions.isNotEmpty(),
                        onSelected = viewModel::selectResolution
                    )
                    SettingDropdown(
                        label = stringResource(R.string.frame_rate),
                        selected = state.selectedFps,
                        options = state.availableFpsOptions,
                        optionLabel = { it.label },
                        enabled = editable && state.availableFpsOptions.isNotEmpty(),
                        onSelected = viewModel::selectFps
                    )
                    SettingDropdown(
                        label = stringResource(R.string.bitrate),
                        selected = state.selectedBitrate,
                        options = BitratePreset.entries,
                        optionLabel = { it.label },
                        enabled = editable,
                        onSelected = viewModel::selectBitrate
                    )
                    val encoderMax = state.encoderMaxBitrateMbps
                    if (encoderMax != null && state.selectedBitrate.bitrateMbps > encoderMax) {
                        Text(
                            text = stringResource(R.string.encoder_bitrate_warning, encoderMax),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        text = stringResource(R.string.stream_settings_apply),
                        style = MaterialTheme.typography.labelSmall,
                        color = RemCamTextDim
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.section_camera_capabilities))
                SettingsCard {
                    CapabilityRow(
                        label = stringResource(R.string.manual_iso_exposure),
                        value = if (state.supportsManual) stringResource(R.string.supported) else stringResource(R.string.not_available),
                        supported = state.supportsManual
                    )
                    CapabilityRow(
                        label = stringResource(R.string.manual_focus),
                        value = if (state.supportsFocus) stringResource(R.string.supported) else stringResource(R.string.not_available),
                        supported = state.supportsFocus
                    )
                    CapabilityRow(
                        label = stringResource(R.string.white_balance_presets),
                        value = if (state.supportsWhiteBalance) {
                            stringResource(
                                R.string.presets_count,
                                state.manualCapabilities?.whiteBalancePresets?.size ?: 0
                            )
                        } else {
                            stringResource(R.string.not_available)
                        },
                        supported = state.supportsWhiteBalance
                    )
                    CapabilityRow(
                        label = stringResource(R.string.flash_torch),
                        value = if (state.flashAvailable) stringResource(R.string.supported) else stringResource(R.string.not_available),
                        supported = state.flashAvailable
                    )
                    CapabilityRow(
                        label = stringResource(R.string.digital_zoom),
                        value = if (state.maxZoom > 1.01f) {
                            stringResource(R.string.zoom_max, state.maxZoom)
                        } else {
                            stringResource(R.string.not_available)
                        },
                        supported = state.maxZoom > 1.01f
                    )
                    if (!state.supportsManual) {
                        Text(
                            text = stringResource(R.string.manual_unsupported_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = RemCamTextDim
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle(stringResource(R.string.section_about))
                SettingsCard {
                    Text(
                        text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.titleSmall,
                        color = RemCamText
                    )
                    Text(
                        text = stringResource(R.string.about_stack),
                        style = MaterialTheme.typography.bodySmall,
                        color = RemCamTextDim
                    )
                    Text(
                        text = stringResource(R.string.about_stream),
                        style = MaterialTheme.typography.bodySmall,
                        color = RemCamTextDim
                    )
                }
            }
        }
    }
}

@Composable
private fun AddressRow(
    address: LocalAddress,
    port: Int?,
    onCopy: (String) -> Unit
) {
    val url = if (port != null) "rtsp://${address.address}:$port/camera" else ""
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${address.label} · ${address.interfaceName}",
                style = MaterialTheme.typography.labelSmall,
                color = RemCamTextDim
            )
            Text(
                text = address.address,
                style = MaterialTheme.typography.bodyMedium,
                color = RemCamText
            )
            if (url.isNotBlank()) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    color = RemCamTextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(
            onClick = { onCopy(url) },
            enabled = url.isNotBlank()
        ) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = stringResource(R.string.copy_address_url, address.label)
            )
        }
    }
}

@Composable
private fun CapabilityRow(
    label: String,
    value: String,
    supported: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        StatusDot(if (supported) RemCamGreen else RemCamTextDim)
        Spacer(Modifier.size(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = RemCamText,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = if (supported) RemCamGreen else RemCamTextDim
        )
    }
}

@Composable
private fun <T> SettingDropdown(
    label: String,
    selected: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    enabled: Boolean,
    onSelected: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(RemCamSurfaceHigh)
                .clickable(enabled = enabled) { expanded = true }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = RemCamTextDim
                )
                Text(
                    text = optionLabel(selected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (enabled) RemCamText else RemCamTextDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = RemCamTextDim,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelected(option)
                    }
                )
            }
        }
    }
}

private fun authenticatedRtspUrl(
    baseUrl: String,
    username: String,
    password: String
): String {
    if (baseUrl.isBlank() || username.isBlank()) return baseUrl
    val encodedUsername = URLEncoder.encode(username, "UTF-8").replace("+", "%20")
    val encodedPassword = URLEncoder.encode(password, "UTF-8").replace("+", "%20")
    return baseUrl.replaceFirst("rtsp://", "rtsp://$encodedUsername:$encodedPassword@")
}
