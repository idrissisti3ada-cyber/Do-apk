package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Minimize
import androidx.compose.material.icons.rounded.OpenInBrowser
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.service.FloatingBrowserTabInfo
import com.example.ui.MainUiState
import com.example.util.BrowserHelper
import com.example.util.InstalledBrowserInfo

@Composable
fun BrowsersScreen(
    uiState: MainUiState,
    onOpenFloatingBrowser: (url: String, profilePkg: String?, profileLabel: String) -> Unit,
    onLaunchPhoneBrowser: (packageName: String, url: String) -> Unit,
    onRestoreTab: (Int) -> Unit,
    onMinimizeTab: (Int) -> Unit,
    onCloseTab: (Int) -> Unit,
    onCloseAllTabs: () -> Unit,
    onAutoRedeemChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val activeDefaultUrl = BrowserHelper.defaultUrlFor(uiState.preferences.activeCaseMode)
    var targetUrl by remember { mutableStateOf(activeDefaultUrl) }

    LaunchedEffect(activeDefaultUrl) {
        if (targetUrl == BrowserHelper.SHOP2GAME_URL || targetUrl == BrowserHelper.GARENA_REWARD_URL) {
            targetUrl = activeDefaultUrl
        }
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
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.browsers_title),
                        style = MaterialTheme.typography.headlineMedium
                    )
                    Text(
                        text = stringResource(R.string.browsers_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // URL Launcher & New Floating Window Card
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = targetUrl.contains("shop2game", ignoreCase = true),
                                onClick = { targetUrl = BrowserHelper.SHOP2GAME_URL },
                                label = { Text("shop2game.com") },
                                modifier = Modifier.testTag("preset_shop2game")
                            )
                            FilterChip(
                                selected = targetUrl.contains("ff.garena", ignoreCase = true),
                                onClick = { targetUrl = BrowserHelper.GARENA_REWARD_URL },
                                label = { Text("reward.ff.garena.com") },
                                modifier = Modifier.testTag("preset_reward_ff")
                            )
                        }

                        OutlinedTextField(
                            value = targetUrl,
                            onValueChange = { targetUrl = it },
                            label = { Text("Target URL") },
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Language,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("browser_url_input")
                        )

                        Button(
                            onClick = {
                                val label = if (targetUrl.contains("ff.garena", ignoreCase = true)) {
                                    "reward.ff"
                                } else {
                                    "shop2game"
                                }
                                onOpenFloatingBrowser(targetUrl, null, label)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .minimumInteractiveComponentSize()
                                .testTag("open_new_floating_browser_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.open_floating_browser))
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.setting_autoredeem_title),
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = stringResource(R.string.setting_autoredeem_desc),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Switch(
                                checked = uiState.preferences.autoRedeemShop2Game,
                                onCheckedChange = onAutoRedeemChange,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .testTag("switch_auto_redeem_shop2game")
                            )
                        }
                    }
                }
            }

            // Active Floating Pages
            if (uiState.openBrowserTabs.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Active Floating Pages (${uiState.openBrowserTabs.size})",
                            style = MaterialTheme.typography.titleLarge
                        )
                        TextButton(
                            onClick = onCloseAllTabs,
                            modifier = Modifier.testTag("close_all_browsers_button")
                        ) {
                            Text("Close All", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }

                items(
                    items = uiState.openBrowserTabs,
                    key = { it.id }
                ) { tab ->
                    ActiveBrowserTabCard(
                        tab = tab,
                        onRestore = { onRestoreTab(tab.id) },
                        onMinimize = { onMinimizeTab(tab.id) },
                        onClose = { onCloseTab(tab.id) }
                    )
                }
            }

            // Installed Phone Browsers (Lemur, Chrome, etc.)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Your Phone Browsers",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Open in a resizable Floating Window or launch directly in your installed browser.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (uiState.installedBrowsers.isEmpty()) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No external browsers detected. Use the Floating Browser button above.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(
                    items = uiState.installedBrowsers,
                    key = { it.packageName }
                ) { browser ->
                    InstalledBrowserCard(
                        browser = browser,
                        onOpenFloating = {
                            onOpenFloatingBrowser(targetUrl, browser.packageName, browser.appName)
                        },
                        onLaunchExternal = {
                            onLaunchPhoneBrowser(browser.packageName, targetUrl)
                        }
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
private fun ActiveBrowserTabCard(
    tab: FloatingBrowserTabInfo,
    onRestore: () -> Unit,
    onMinimize: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.45f)),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.PictureInPictureAlt,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Page #${tab.id} • ${if (tab.isMinimizedToBubble) "Bubble" else "Open"}${if (tab.isConfirmed) " ✅" else ""}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = tab.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (tab.isMinimizedToBubble) {
                FilledTonalButton(onClick = onRestore) {
                    Text("Restore")
                }
            } else {
                IconButton(onClick = onMinimize) {
                    Icon(
                        imageVector = Icons.Rounded.Minimize,
                        contentDescription = "Minimize to bubble"
                    )
                }
            }
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Close browser",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun InstalledBrowserCard(
    browser: InstalledBrowserInfo,
    onOpenFloating: () -> Unit,
    onLaunchExternal: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.OpenInBrowser,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = browser.appName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = browser.packageName,
                        style = MaterialTheme.typography.labelSmall,
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
                        .testTag("floating_browser_${browser.packageName}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PictureInPictureAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Floating")
                }

                OutlinedButton(
                    onClick = onLaunchExternal,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("launch_browser_${browser.packageName}")
                ) {
                    Icon(
                        imageVector = Icons.Rounded.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open App")
                }
            }
        }
    }
}
