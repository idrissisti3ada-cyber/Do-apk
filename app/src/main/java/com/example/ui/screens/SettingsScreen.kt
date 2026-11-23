package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.OutlinedButton
import com.example.R
import com.example.domain.AccessKeyManager
import com.example.ui.MainUiState
import com.example.ui.theme.WarningAmber

@Composable
fun SettingsScreen(
    uiState: MainUiState,
    onVibrateChange: (Boolean) -> Unit,
    onSoundChange: (Boolean) -> Unit,
    onAutoStartBootChange: (Boolean) -> Unit,
    onAutoRedeemChange: (Boolean) -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onRequestPostNotifications: () -> Unit,
    onLockApp: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs = uiState.preferences
    val perms = uiState.permissionStatus

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
            item {
                Text(
                    text = stringResource(R.string.nav_settings),
                    style = MaterialTheme.typography.headlineMedium
                )
            }

            // License Status & Lock
            item {
                val licenseTitle = when {
                    prefs.isAdmin -> "Admin Access Active"
                    prefs.isUnlimitedLicense -> "Unlimited Key Active"
                    else -> "Timed Key Active"
                }
                val licenseSubtitle = when {
                    prefs.isAdmin -> "Admin key active • Unlimited access & key generator unlocked"
                    prefs.isUnlimitedLicense -> "Single-use unlimited key active"
                    else -> "Time remaining: ${AccessKeyManager.formatRemainingMillis(uiState.remainingMillis ?: 0L)}"
                }
                SettingsGroup(title = "Access License") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VpnKey,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = licenseTitle, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = licenseSubtitle,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = onLockApp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("settings_lock_app_button")
                        ) {
                            Text("Lock / Enter Another Key")
                        }
                    }
                }
            }

            // Feedback Section
            item {
                SettingsGroup(title = stringResource(R.string.settings_feedback_header)) {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.Vibration,
                        title = stringResource(R.string.setting_vibrate_title),
                        subtitle = stringResource(R.string.setting_vibrate_desc),
                        checked = prefs.vibrateOnCopy,
                        testTag = "switch_vibrate_on_copy",
                        onCheckedChange = onVibrateChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchRow(
                        icon = Icons.Rounded.VolumeUp,
                        title = stringResource(R.string.setting_sound_title),
                        subtitle = stringResource(R.string.setting_sound_desc),
                        checked = prefs.soundOnCopy,
                        testTag = "switch_sound_on_copy",
                        onCheckedChange = onSoundChange
                    )
                }
            }

            // Startup & Background
            item {
                SettingsGroup(title = stringResource(R.string.settings_system_header)) {
                    SettingsSwitchRow(
                        icon = Icons.Rounded.PowerSettingsNew,
                        title = stringResource(R.string.setting_boot_title),
                        subtitle = stringResource(R.string.setting_boot_desc),
                        checked = prefs.autoStartOnBoot,
                        testTag = "switch_boot_autostart",
                        onCheckedChange = onAutoStartBootChange
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    SettingsSwitchRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = stringResource(R.string.setting_autoredeem_title),
                        subtitle = stringResource(R.string.setting_autoredeem_desc),
                        checked = prefs.autoRedeemShop2Game,
                        testTag = "switch_settings_autoredeem",
                        onCheckedChange = onAutoRedeemChange
                    )
                }
            }

            // Permissions Status
            item {
                SettingsGroup(title = stringResource(R.string.settings_permissions_header)) {
                    PermissionStatusRow(
                        icon = Icons.Rounded.NotificationsActive,
                        title = "Notification Access",
                        granted = perms.notificationAccess,
                        testTag = "settings_perm_notif_access",
                        onClick = onOpenNotificationAccess
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionStatusRow(
                        icon = Icons.Rounded.RemoveRedEye,
                        title = "Screen Reading & OCR (Accessibility)",
                        granted = perms.accessibilityAccess,
                        testTag = "settings_perm_accessibility",
                        onClick = onOpenAccessibilitySettings
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionStatusRow(
                        icon = Icons.Rounded.Layers,
                        title = "Display Over Other Apps",
                        granted = perms.overlayAccess,
                        testTag = "settings_perm_overlay",
                        onClick = onOpenOverlaySettings
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    PermissionStatusRow(
                        icon = Icons.Rounded.BatteryChargingFull,
                        title = "Ignore Battery Optimization",
                        granted = perms.batteryOptimizationIgnored,
                        testTag = "settings_perm_battery",
                        onClick = onRequestBatteryOptimization
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        PermissionStatusRow(
                            icon = Icons.Rounded.NotificationsNone,
                            title = "Notifications (Android 13+)",
                            granted = perms.postNotificationsGranted,
                            testTag = "settings_perm_post_notif",
                            onClick = onRequestPostNotifications
                        )
                    }
                }
            }

            // Privacy Guarantee Card
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.privacy_badge_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = stringResource(R.string.privacy_badge_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            tonalElevation = 1.dp,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    testTag: String,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .testTag(testTag)
        )
    }
}

@Composable
private fun PermissionStatusRow(
    icon: ImageVector,
    title: String,
    granted: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
            contentDescription = if (granted) "Granted" else "Needed",
            tint = if (granted) MaterialTheme.colorScheme.primary else WarningAmber,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
