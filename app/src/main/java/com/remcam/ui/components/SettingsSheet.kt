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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remcam.BitratePreset
import com.remcam.BuildConfig
import com.remcam.FpsPreset
import com.remcam.LocalAddress
import com.remcam.RemCamUiState
import com.remcam.RemCamViewModel
import com.remcam.ResolutionPreset
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
    val port = state.portInput.toIntOrNull()
    val authenticatedUrl = authenticatedRtspUrl(
        baseUrl = state.streamUrl,
        username = state.rtspUsername,
        password = state.rtspPassword
    )

    fun copyToClipboard(text: String) {
        if (text.isBlank()) return
        clipboardManager.setText(AnnotatedString(text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
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
                        text = "Settings",
                        style = MaterialTheme.typography.titleMedium,
                        color = RemCamText
                    )
                    Text(
                        text = "RemCam · ${state.lensLabel} camera",
                        style = MaterialTheme.typography.labelMedium,
                        color = RemCamTextDim
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close settings")
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("RTSP STREAM")
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (state.rtspAuthEnabled) {
                                    "Authenticated stream URL"
                                } else {
                                    "Stream URL (no authentication)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = RemCamTextDim
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                text = state.streamUrl.ifBlank { "No local address" },
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
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy stream URL")
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
                            Text("Refresh network")
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = if (state.localAddresses.isEmpty()) {
                                "No IPv4 addresses"
                            } else {
                                "${state.localAddresses.size} address(es)"
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
                            text = "Connect Wi-Fi or enable USB tethering, then tap refresh.",
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
                SectionTitle("AUTHENTICATION")
                SettingsCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Basic authentication",
                                style = MaterialTheme.typography.bodyMedium,
                                color = RemCamText
                            )
                            Text(
                                text = if (state.rtspAuthEnabled) {
                                    "Username and password required"
                                } else {
                                    "No authentication; anyone on the network can connect"
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
                            label = { Text("Username") },
                            singleLine = true,
                            enabled = editable,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = state.rtspPassword,
                            onValueChange = viewModel::updateRtspPassword,
                            label = { Text("Password") },
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
                            Text("Copy authenticated URL")
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("STREAM SETTINGS")
                SettingsCard {
                    OutlinedTextField(
                        value = state.portInput,
                        onValueChange = viewModel::updatePortInput,
                        label = { Text("RTSP port") },
                        singleLine = true,
                        enabled = editable,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    SettingDropdown(
                        label = "Resolution",
                        selected = state.selectedResolution,
                        options = state.availableResolutions,
                        optionLabel = { "${it.label} · ${it.aspectRatioLabel}" },
                        enabled = editable && state.availableResolutions.isNotEmpty(),
                        onSelected = viewModel::selectResolution
                    )
                    SettingDropdown(
                        label = "Frame rate",
                        selected = state.selectedFps,
                        options = state.availableFpsOptions,
                        optionLabel = { it.label },
                        enabled = editable && state.availableFpsOptions.isNotEmpty(),
                        onSelected = viewModel::selectFps
                    )
                    SettingDropdown(
                        label = "Bitrate",
                        selected = state.selectedBitrate,
                        options = BitratePreset.entries,
                        optionLabel = { it.label },
                        enabled = editable,
                        onSelected = viewModel::selectBitrate
                    )
                    val encoderMax = state.encoderMaxBitrateMbps
                    if (encoderMax != null && state.selectedBitrate.bitrateMbps > encoderMax) {
                        Text(
                            text = "This phone's H.264 encoder is rated up to $encoderMax Mbps. " +
                                "Higher values may be capped or fail to start.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Text(
                        text = "Stream settings apply on the next start.",
                        style = MaterialTheme.typography.labelSmall,
                        color = RemCamTextDim
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("CAMERA CAPABILITIES")
                SettingsCard {
                    CapabilityRow(
                        label = "Manual ISO / exposure",
                        value = if (state.supportsManual) "Supported" else "Not available",
                        supported = state.supportsManual
                    )
                    CapabilityRow(
                        label = "Manual focus",
                        value = if (state.supportsFocus) "Supported" else "Not available",
                        supported = state.supportsFocus
                    )
                    CapabilityRow(
                        label = "White balance presets",
                        value = if (state.supportsWhiteBalance) {
                            "${state.manualCapabilities?.whiteBalancePresets?.size ?: 0} presets"
                        } else {
                            "Not available"
                        },
                        supported = state.supportsWhiteBalance
                    )
                    CapabilityRow(
                        label = "Flash / torch",
                        value = if (state.flashAvailable) "Supported" else "Not available",
                        supported = state.flashAvailable
                    )
                    CapabilityRow(
                        label = "Digital zoom",
                        value = if (state.maxZoom > 1.01f) {
                            String.format(Locale.US, "%.1fx max", state.maxZoom)
                        } else {
                            "Not available"
                        },
                        supported = state.maxZoom > 1.01f
                    )
                    if (!state.supportsManual) {
                        Text(
                            text = "This camera does not expose AE OFF with ISO/exposure ranges, " +
                                "so the Manual panel stays disabled and automatic capture is used.",
                            style = MaterialTheme.typography.bodySmall,
                            color = RemCamTextDim
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("ABOUT")
                SettingsCard {
                    Text(
                        text = "RemCam ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.titleSmall,
                        color = RemCamText
                    )
                    Text(
                        text = "Camera2 · MediaCodec H.264 · local RTSP server",
                        style = MaterialTheme.typography.bodySmall,
                        color = RemCamTextDim
                    )
                    Text(
                        text = "Stream path: /camera · Latin-1-safe RTP payload. " +
                            "No cloud relay or external server.",
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
                contentDescription = "Copy ${address.label} URL"
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
    optionLabel: (T) -> String,
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
