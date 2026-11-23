package com.example.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsNone
import androidx.compose.material.icons.rounded.RemoveRedEye
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.util.PermissionStatus

@Composable
fun SetupWizardDialog(
    permissionStatus: PermissionStatus,
    onGrantNotificationAccess: () -> Unit,
    onGrantAccessibilityAccess: () -> Unit,
    onGrantOverlay: () -> Unit,
    onGrantBatteryOptimization: () -> Unit,
    onGrantPostNotifications: () -> Unit,
    onDismiss: () -> Unit
) {
    val needsPostNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val totalSteps = if (needsPostNotif) 5 else 4
    val completedSteps = listOfNotNull(
        permissionStatus.notificationAccess,
        permissionStatus.accessibilityAccess,
        permissionStatus.overlayAccess,
        permissionStatus.batteryOptimizationIgnored,
        if (needsPostNotif) permissionStatus.postNotificationsGranted else null
    ).count { it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.setup_dialog_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.setup_dialog_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "$completedSteps of $totalSteps ready",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    LinearProgressIndicator(
                        progress = { completedSteps.toFloat() / totalSteps.toFloat() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                }

                PermissionStepCard(
                    icon = Icons.Rounded.NotificationsActive,
                    title = stringResource(R.string.setup_step_notif_access),
                    description = stringResource(R.string.setup_step_notif_access_desc),
                    granted = permissionStatus.notificationAccess,
                    testTag = "grant_notif_access_button",
                    onClick = onGrantNotificationAccess
                )

                PermissionStepCard(
                    icon = Icons.Rounded.RemoveRedEye,
                    title = stringResource(R.string.setup_step_accessibility),
                    description = stringResource(R.string.setup_step_accessibility_desc),
                    granted = permissionStatus.accessibilityAccess,
                    testTag = "grant_accessibility_button",
                    onClick = onGrantAccessibilityAccess
                )

                PermissionStepCard(
                    icon = Icons.Rounded.Layers,
                    title = stringResource(R.string.setup_step_overlay),
                    description = stringResource(R.string.setup_step_overlay_desc),
                    granted = permissionStatus.overlayAccess,
                    testTag = "grant_overlay_button",
                    onClick = onGrantOverlay
                )

                PermissionStepCard(
                    icon = Icons.Rounded.BatteryChargingFull,
                    title = stringResource(R.string.setup_step_battery),
                    description = stringResource(R.string.setup_step_battery_desc),
                    granted = permissionStatus.batteryOptimizationIgnored,
                    testTag = "grant_battery_button",
                    onClick = onGrantBatteryOptimization
                )

                if (needsPostNotif) {
                    PermissionStepCard(
                        icon = Icons.Rounded.NotificationsNone,
                        title = stringResource(R.string.setup_step_post_notif),
                        description = stringResource(R.string.setup_step_post_notif_desc),
                        granted = permissionStatus.postNotificationsGranted,
                        testTag = "grant_post_notif_button",
                        onClick = onGrantPostNotifications
                    )
                }
            }
        },
        confirmButton = {
            if (permissionStatus.allCoreGranted) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("setup_done_button")
                ) {
                    Text(stringResource(R.string.action_done))
                }
            } else {
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("setup_later_button")
                ) {
                    Text("Close")
                }
            }
        }
    )
}

@Composable
private fun PermissionStepCard(
    icon: ImageVector,
    title: String,
    description: String,
    granted: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (granted) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (granted) Icons.Rounded.CheckCircle else icon,
                contentDescription = null,
                tint = if (granted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            if (granted) {
                Text(
                    text = stringResource(R.string.action_granted),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                FilledTonalButton(
                    onClick = onClick,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    modifier = Modifier.testTag(testTag)
                ) {
                    Text(stringResource(R.string.action_grant))
                }
            }
        }
    }
}
