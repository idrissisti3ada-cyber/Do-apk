package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.AccessKeyManager
import com.example.domain.GeneratedKeyRecord
import com.example.ui.MainUiState

private enum class DurationPreset(val label: String, val seconds: Long) {
    MIN_15("15 Min", 15L * 60L),
    HOUR_1("1 Hour", 3_600L),
    HOUR_6("6 Hours", 6L * 3_600L),
    DAY_1("24 Hours", 86_400L),
    DAY_3("3 Days", 3L * 86_400L),
    DAY_7("7 Days", 7L * 86_400L),
    DAY_30("30 Days", 30L * 86_400L),
    UNLIMITED("Unlimited", 0L),
    CUSTOM("Custom", -1L)
}

private enum class CustomUnit(val label: String, val multiplierSeconds: Long) {
    MINUTES("Minutes", 60L),
    HOURS("Hours", 3_600L),
    DAYS("Days", 86_400L)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminScreen(
    uiState: MainUiState,
    onGenerateKey: (durationSeconds: Long, label: String) -> Unit,
    onCopyKey: (String) -> Unit,
    onDeleteKey: (String) -> Unit,
    onLockApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var selectedPreset by remember { mutableStateOf(DurationPreset.DAY_1) }
    var customAmountText by remember { mutableStateOf("2") }
    var customUnit by remember { mutableStateOf(CustomUnit.HOURS) }
    var userLabel by remember { mutableStateOf("") }

    val resolvedDurationSeconds: Long? = when (selectedPreset) {
        DurationPreset.CUSTOM -> {
            val amount = customAmountText.trim().toLongOrNull()
            if (amount != null && amount > 0L) {
                (amount * customUnit.multiplierSeconds).coerceAtMost(0xFFFFFFFFL)
            } else {
                null
            }
        }
        else -> selectedPreset.seconds
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Admin Header Card
            item {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AdminPanelSettings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(30.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.admin_page_title),
                                    style = MaterialTheme.typography.titleLarge
                                )
                                Text(
                                    text = stringResource(R.string.admin_page_subtitle),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        OutlinedButton(
                            onClick = onLockApp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("admin_lock_app_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Lock App (Return to Key Screen)")
                        }
                    }
                }
            }

            // Generator Card
            item {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Schedule,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = "Choose Key Duration",
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DurationPreset.entries.forEach { preset ->
                                FilterChip(
                                    selected = selectedPreset == preset,
                                    onClick = { selectedPreset = preset },
                                    label = { Text(preset.label) },
                                    modifier = Modifier.testTag("duration_preset_${preset.name}")
                                )
                            }
                        }

                        if (selectedPreset == DurationPreset.CUSTOM) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = customAmountText,
                                    onValueChange = { next ->
                                        if (next.all { it.isDigit() } && next.length <= 5) {
                                            customAmountText = next
                                        }
                                    },
                                    label = { Text("Duration") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .weight(1f)
                                        .testTag("custom_duration_input")
                                )
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CustomUnit.entries.forEach { unit ->
                                        FilterChip(
                                            selected = customUnit == unit,
                                            onClick = { customUnit = unit },
                                            label = { Text(unit.label) }
                                        )
                                    }
                                }
                            }
                        }

                        OutlinedTextField(
                            value = userLabel,
                            onValueChange = { userLabel = it },
                            label = { Text("User Note (Optional)") },
                            placeholder = { Text("e.g. Client 1") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("admin_user_label_input")
                        )

                        Button(
                            onClick = {
                                val sec = resolvedDurationSeconds
                                if (sec == null) {
                                    Toast.makeText(context, "Enter a valid custom duration", Toast.LENGTH_SHORT).show()
                                } else {
                                    onGenerateKey(sec, userLabel)
                                    Toast.makeText(context, "Key generated & copied!", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = resolvedDurationSeconds != null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .minimumInteractiveComponentSize()
                                .testTag("generate_user_key_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Key,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (resolvedDurationSeconds == 0L) {
                                    "Generate Unlimited Key"
                                } else if (resolvedDurationSeconds != null) {
                                    "Generate ${AccessKeyManager.formatDurationLabel(resolvedDurationSeconds)} Key"
                                } else {
                                    "Generate Key"
                                }
                            )
                        }
                    }
                }
            }

            // Latest Generated Key Highlight
            uiState.latestGeneratedKey?.let { latest ->
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.35f),
                        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "New Single-Use Key Ready (${AccessKeyManager.formatDurationLabel(latest.durationSeconds)})",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Text(
                                text = latest.key,
                                style = MaterialTheme.typography.titleMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                            FilledTonalButton(
                                onClick = {
                                    onCopyKey(latest.key)
                                    Toast.makeText(context, "Copied key!", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("copy_latest_generated_key")
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Copy Key")
                            }
                        }
                    }
                }
            }

            // Generated Keys History for Admin Reference
            item {
                Text(
                    text = "Generated Keys (${uiState.preferences.generatedKeys.size})",
                    style = MaterialTheme.typography.titleLarge
                )
            }

            if (uiState.preferences.generatedKeys.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No user keys generated yet. Choose a duration above and tap Generate.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(
                    items = uiState.preferences.generatedKeys,
                    key = { it.keyId }
                ) { record ->
                    GeneratedKeyRow(
                        record = record,
                        onCopy = {
                            onCopyKey(record.key)
                            Toast.makeText(context, "Copied key!", Toast.LENGTH_SHORT).show()
                        },
                        onDelete = { onDeleteKey(record.keyId) }
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun GeneratedKeyRow(
    record: GeneratedKeyRecord,
    onCopy: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = if (record.label.isNotBlank()) {
                        "${record.label} • ${AccessKeyManager.formatDurationLabel(record.durationSeconds)}"
                    } else {
                        "Single-Use • ${AccessKeyManager.formatDurationLabel(record.durationSeconds)}"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = record.key,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = "Copy key"
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = "Remove key",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
