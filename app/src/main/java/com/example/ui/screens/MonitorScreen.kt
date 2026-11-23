package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.domain.AppCaseMode
import com.example.ui.MainUiState
import com.example.ui.theme.InstagramAmber
import com.example.ui.theme.InstagramRose
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.WhatsAppGreen

@Composable
fun MonitorScreen(
    pageMode: AppCaseMode,
    uiState: MainUiState,
    onActivateCaseMode: () -> Unit,
    onToggleWhatsApp: () -> Unit,
    onToggleInstagram: () -> Unit,
    onToggleMonitoringActive: () -> Unit,
    onOpenSetupGuide: () -> Unit,
    onOpenModeFloatingBrowser: () -> Unit,
    onOpenBrowsersTab: () -> Unit,
    modifier: Modifier = Modifier
) {
    val prefs = uiState.preferences
    val perms = uiState.permissionStatus
    val isCaseSelected = prefs.activeCaseMode == pageMode

    val accentPrimary = when (pageMode) {
        AppCaseMode.MYCODE_G7F -> MaterialTheme.colorScheme.primary
        AppCaseMode.GARENA_CODE -> MaterialTheme.colorScheme.secondary
    }
    val accentSecondary = when (pageMode) {
        AppCaseMode.MYCODE_G7F -> MaterialTheme.colorScheme.secondary
        AppCaseMode.GARENA_CODE -> MaterialTheme.colorScheme.primary
    }

    val matchingOpenTabs = uiState.openBrowserTabs.count { tab ->
        when (pageMode) {
            AppCaseMode.MYCODE_G7F -> tab.url.contains("shop2game", ignoreCase = true)
            AppCaseMode.GARENA_CODE -> tab.url.contains("ff.garena", ignoreCase = true)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentPrimary.copy(alpha = 0.16f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.85f, size.height * 0.08f),
                        radius = size.width * 0.72f
                    ),
                    radius = size.width * 0.72f,
                    center = Offset(size.width * 0.85f, size.height * 0.08f)
                )
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            accentSecondary.copy(alpha = 0.09f),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.12f, size.height * 0.25f),
                        radius = size.width * 0.5f
                    ),
                    radius = size.width * 0.5f,
                    center = Offset(size.width * 0.12f, size.height * 0.25f)
                )
            },
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = 680.dp)
                .fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Dedicated Case Hero Header
            item {
                MonitorHeroHeader(
                    pageMode = pageMode,
                    isCaseSelected = isCaseSelected,
                    accentColor = accentPrimary,
                    hasAnyAppEnabled = prefs.hasAnyAppEnabled,
                    monitoringActive = prefs.monitoringActive,
                    allPermissionsGranted = perms.allCoreGranted,
                    onActivateCaseMode = onActivateCaseMode,
                    onToggleMonitoringActive = onToggleMonitoringActive
                )
            }

            // Guided Setup Banner when any permission is missing
            if (!perms.allCoreGranted) {
                item {
                    AnimatedVisibility(visible = true) {
                        SetupMissingBanner(
                            missingCount = perms.missingCoreCount,
                            onOpenSetupGuide = onOpenSetupGuide
                        )
                    }
                }
            }

            // Target Apps Header
            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.section_apps_title),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = stringResource(R.string.section_apps_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Two Toggle Cards: WhatsApp and Instagram
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AppToggleCard(
                        title = stringResource(R.string.app_whatsapp_title),
                        subtitle = stringResource(R.string.app_whatsapp_packages),
                        icon = Icons.Rounded.ChatBubble,
                        accentColor = WhatsAppGreen,
                        isActive = prefs.whatsAppEnabled,
                        isInstalled = uiState.whatsAppInstalled,
                        testTag = "toggle_whatsapp_card_${pageMode.id}",
                        onToggle = onToggleWhatsApp
                    )

                    AppToggleCard(
                        title = stringResource(R.string.app_instagram_title),
                        subtitle = stringResource(R.string.app_instagram_packages),
                        icon = Icons.Rounded.CameraAlt,
                        accentColor = InstagramRose,
                        secondaryAccent = InstagramAmber,
                        isActive = prefs.instagramEnabled,
                        isInstalled = uiState.instagramInstalled,
                        testTag = "toggle_instagram_card_${pageMode.id}",
                        onToggle = onToggleInstagram
                    )
                }
            }

            // Floating Browser Quick Launcher tailored for this page's redeem site
            item {
                FloatingBrowserQuickCard(
                    pageMode = pageMode,
                    accentColor = accentPrimary,
                    matchingOpenTabs = matchingOpenTabs,
                    totalOpenTabs = uiState.openBrowserTabs.size,
                    onOpenFloating = onOpenModeFloatingBrowser,
                    onOpenBrowsersTab = onOpenBrowsersTab
                )
            }

            // Mode-specific Capture & OCR Guide (zero history)
            item {
                BubbleGuideCard(pageMode = pageMode, accentColor = accentPrimary)
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun MonitorHeroHeader(
    pageMode: AppCaseMode,
    isCaseSelected: Boolean,
    accentColor: Color,
    hasAnyAppEnabled: Boolean,
    monitoringActive: Boolean,
    allPermissionsGranted: Boolean,
    onActivateCaseMode: () -> Unit,
    onToggleMonitoringActive: () -> Unit
) {
    val statusColor = when {
        !allPermissionsGranted -> WarningAmber
        !isCaseSelected -> MaterialTheme.colorScheme.onSurfaceVariant
        hasAnyAppEnabled && monitoringActive -> accentColor
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val statusText = when {
        !allPermissionsGranted -> stringResource(R.string.status_setup_needed)
        !isCaseSelected -> "Standby • Tap to Activate"
        hasAnyAppEnabled && monitoringActive -> stringResource(R.string.status_active)
        hasAnyAppEnabled -> stringResource(R.string.status_paused)
        else -> stringResource(R.string.status_idle)
    }

    val heroIcon = when (pageMode) {
        AppCaseMode.MYCODE_G7F -> Icons.Rounded.Bolt
        AppCaseMode.GARENA_CODE -> Icons.Rounded.SportsEsports
    }

    val tagline = when (pageMode) {
        AppCaseMode.MYCODE_G7F -> stringResource(R.string.mycode_g7f_tagline)
        AppCaseMode.GARENA_CODE -> stringResource(R.string.garena_code_tagline)
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(
            width = if (isCaseSelected) 1.5.dp else 1.dp,
            color = if (isCaseSelected) accentColor.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant
        ),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(accentColor.copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = heroIcon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = pageMode.displayName,
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Surface(
                                shape = CircleShape,
                                color = if (isCaseSelected) {
                                    accentColor.copy(alpha = 0.18f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            ) {
                                Text(
                                    text = if (isCaseSelected) "ACTIVE CASE" else "INACTIVE",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isCaseSelected) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Text(
                            text = tagline,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = statusColor.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor
                        )
                    }
                }

                if (!isCaseSelected) {
                    Button(
                        onClick = onActivateCaseMode,
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        modifier = Modifier.testTag("activate_case_${pageMode.id}")
                    ) {
                        Text("Use ${pageMode.displayName}")
                    }
                } else if (hasAnyAppEnabled) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .clip(CircleShape)
                            .minimumInteractiveComponentSize()
                            .clickable(onClick = onToggleMonitoringActive)
                            .testTag("hero_pause_resume_chip_${pageMode.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = if (monitoringActive) {
                                    Icons.Rounded.PauseCircle
                                } else {
                                    Icons.Rounded.PlayCircle
                                },
                                contentDescription = if (monitoringActive) "Pause bubble" else "Resume bubble",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = if (monitoringActive) "Pause" else "Resume",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupMissingBanner(
    missingCount: Int,
    onOpenSetupGuide: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = WarningAmber.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.WarningAmber,
                    contentDescription = null,
                    tint = WarningAmber
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.setup_banner_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(R.string.setup_banner_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(
                onClick = onOpenSetupGuide,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("open_setup_guide_button")
            ) {
                Text("${stringResource(R.string.setup_guide_button)} ($missingCount left)")
            }
        }
    }
}

@Composable
private fun AppToggleCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    secondaryAccent: Color = accentColor,
    isActive: Boolean,
    isInstalled: Boolean,
    testTag: String,
    onToggle: () -> Unit
) {
    val borderColor by animateColorAsState(
        targetValue = if (isActive) accentColor else MaterialTheme.colorScheme.outlineVariant,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "card_border"
    )
    val containerColor by animateColorAsState(
        targetValue = if (isActive) {
            accentColor.copy(alpha = 0.11f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "card_bg"
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = BorderStroke(if (isActive) 1.5.dp else 1.dp, borderColor),
        tonalElevation = if (isActive) 4.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        Brush.linearGradient(
                            colors = listOf(
                                accentColor.copy(alpha = if (isActive) 0.95f else 0.22f),
                                secondaryAccent.copy(alpha = if (isActive) 0.85f else 0.16f)
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = if (isActive) Color.White else accentColor,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        shape = CircleShape,
                        color = if (isActive) {
                            accentColor.copy(alpha = 0.18f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = if (isActive) {
                                stringResource(R.string.badge_active)
                            } else {
                                stringResource(R.string.badge_off)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isInstalled) {
                        Icon(
                            imageVector = Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = "Installed on device",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = "Ready when installed",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(
                checked = isActive,
                onCheckedChange = { onToggle() },
                modifier = Modifier.minimumInteractiveComponentSize()
            )
        }
    }
}

@Composable
private fun FloatingBrowserQuickCard(
    pageMode: AppCaseMode,
    accentColor: Color,
    matchingOpenTabs: Int,
    totalOpenTabs: Int,
    onOpenFloating: () -> Unit,
    onOpenBrowsersTab: () -> Unit
) {
    val hostLabel = when (pageMode) {
        AppCaseMode.MYCODE_G7F -> "shop2game.com Floating"
        AppCaseMode.GARENA_CODE -> "reward.ff.garena.com Floating"
    }
    val openButtonText = when (pageMode) {
        AppCaseMode.MYCODE_G7F -> "Open shop2game"
        AppCaseMode.GARENA_CODE -> "Open reward.ff"
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.42f)),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.PictureInPictureAlt,
                    contentDescription = null,
                    tint = accentColor
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = hostLabel,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (matchingOpenTabs > 0) {
                            "$matchingOpenTabs matching page(s) active • $totalOpenTabs total open"
                        } else {
                            "Multi-page resizable windows • Drag to edge for bubble"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = onOpenFloating,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("quick_open_floating_${pageMode.id}")
                ) {
                    Text(openButtonText)
                }
                OutlinedButton(
                    onClick = onOpenBrowsersTab,
                    modifier = Modifier.testTag("quick_open_browsers_tab_${pageMode.id}")
                ) {
                    Text("Browsers")
                }
            }
        }
    }
}

@Composable
private fun BubbleGuideCard(
    pageMode: AppCaseMode,
    accentColor: Color
) {
    val title = when (pageMode) {
        AppCaseMode.MYCODE_G7F -> "MyCodeG7f • 15–16 Digit Rules & OCR"
        AppCaseMode.GARENA_CODE -> "garenaCode • 12-Char Rules & OCR"
    }
    val details = when (pageMode) {
        AppCaseMode.MYCODE_G7F ->
            "• Format: 15–16 digits (numbers only), strips letters, emojis, dots, dashes & prefixes like 1. / 5-.\n" +
                "• Single-tap bubble: Pause / Resume • Double-tap bubble: Instant Screen OCR.\n" +
                "• Auto-Redeem: Distributes codes across shop2game.com floating pages & clicks Confirm ✅."
        AppCaseMode.GARENA_CODE ->
            "• Format: 12 chars A–Z & 0–9 (e.g. SH19JD73HDOS), auto-cleans spaces, dots & symbols like ;#('.\n" +
                "• Single-tap bubble: Pause / Resume • Double-tap bubble: Instant Screen OCR.\n" +
                "• Auto-Redeem: Pastes into reward.ff.garena.com boxes, unfocuses input, clicks Confirm ✅."
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.DocumentScanner,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(28.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
