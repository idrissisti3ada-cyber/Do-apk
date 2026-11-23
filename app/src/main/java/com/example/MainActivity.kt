package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.domain.AppCaseMode
import com.example.ui.MainViewModel
import com.example.ui.components.SetupWizardDialog
import com.example.ui.screens.AdminScreen
import com.example.ui.screens.BrowsersScreen
import com.example.ui.screens.KeyGateScreen
import com.example.ui.screens.MonitorScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyCodeG7fTheme
import com.example.util.BrowserHelper
import com.example.util.PermissionHelper

const val ROUTE_MYCODE_G7F = "mycode_g7f"
const val ROUTE_GARENA_CODE = "garena_code"
const val ROUTE_BROWSERS = "browsers"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_ADMIN = "admin"

private data class NavItem(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String,
    val caseMode: AppCaseMode? = null
)

private val baseNavItems = listOf(
    NavItem(
        route = ROUTE_MYCODE_G7F,
        labelRes = R.string.nav_mycode_g7f,
        selectedIcon = Icons.Rounded.Bolt,
        unselectedIcon = Icons.Outlined.Bolt,
        testTag = "nav_mycode_g7f",
        caseMode = AppCaseMode.MYCODE_G7F
    ),
    NavItem(
        route = ROUTE_GARENA_CODE,
        labelRes = R.string.nav_garena_code,
        selectedIcon = Icons.Rounded.SportsEsports,
        unselectedIcon = Icons.Outlined.SportsEsports,
        testTag = "nav_garena_code",
        caseMode = AppCaseMode.GARENA_CODE
    ),
    NavItem(
        route = ROUTE_BROWSERS,
        labelRes = R.string.nav_browsers,
        selectedIcon = Icons.Rounded.Language,
        unselectedIcon = Icons.Outlined.Language,
        testTag = "nav_browsers"
    ),
    NavItem(
        route = ROUTE_SETTINGS,
        labelRes = R.string.nav_settings,
        selectedIcon = Icons.Rounded.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_settings"
    )
)

private val adminNavItem = NavItem(
    route = ROUTE_ADMIN,
    labelRes = R.string.nav_admin,
    selectedIcon = Icons.Rounded.AdminPanelSettings,
    unselectedIcon = Icons.Outlined.AdminPanelSettings,
    testTag = "nav_admin"
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyCodeG7fTheme {
                MyCodeHubRoot()
            }
        }
    }
}

@Composable
fun MyCodeHubRoot(
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    if (!uiState.hasValidAccess) {
        KeyGateScreen(
            isExpired = uiState.preferences.lockReasonExpired,
            errorMessage = uiState.keyInputError,
            onKeyChange = { viewModel.clearKeyInputError() },
            onSubmitKey = { viewModel.submitAccessKey(it) }
        )
        return
    }

    val navController = rememberNavController()
    val visibleNavItems = if (uiState.preferences.isAdmin) {
        baseNavItems + adminNavItem
    } else {
        baseNavItems
    }

    val postNotificationsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        viewModel.refreshPermissions()
    }

    val requestPostNotifications: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            postNotificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.refreshPermissions()
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: ROUTE_MYCODE_G7F

    LaunchedEffect(uiState.preferences.isAdmin, currentRoute) {
        if (!uiState.preferences.isAdmin && currentRoute == ROUTE_ADMIN) {
            navController.navigate(ROUTE_MYCODE_G7F) {
                popUpTo(navController.graph.findStartDestination().id) {
                    inclusive = false
                }
                launchSingleTop = true
            }
        }
    }

    val navigateTo: (String, AppCaseMode?) -> Unit = { route, caseMode ->
        if (caseMode != null) {
            viewModel.selectCaseMode(caseMode)
        }
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useNavRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            bottomBar = {
                if (!useNavRail) {
                    NavigationBar {
                        visibleNavItems.forEach { item ->
                            val selected = currentRoute == item.route
                            val label = stringResource(item.labelRes)
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTo(item.route, item.caseMode) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useNavRail) {
                    NavigationRail {
                        visibleNavItems.forEach { item ->
                            val selected = currentRoute == item.route
                            val label = stringResource(item.labelRes)
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTo(item.route, item.caseMode) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = label
                                    )
                                },
                                label = { Text(label) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_MYCODE_G7F,
                    modifier = Modifier.weight(1f)
                ) {
                    composable(ROUTE_MYCODE_G7F) {
                        MonitorScreen(
                            pageMode = AppCaseMode.MYCODE_G7F,
                            uiState = uiState,
                            onActivateCaseMode = { viewModel.selectCaseMode(AppCaseMode.MYCODE_G7F) },
                            onToggleWhatsApp = { viewModel.toggleWhatsApp() },
                            onToggleInstagram = { viewModel.toggleInstagram() },
                            onToggleMonitoringActive = { viewModel.toggleMonitoringActive() },
                            onOpenSetupGuide = { viewModel.openSetupWizard() },
                            onOpenModeFloatingBrowser = {
                                viewModel.selectCaseMode(AppCaseMode.MYCODE_G7F)
                                viewModel.openFloatingBrowser(
                                    url = BrowserHelper.SHOP2GAME_URL,
                                    profilePackage = null,
                                    profileLabel = "shop2game"
                                )
                            },
                            onOpenBrowsersTab = { navigateTo(ROUTE_BROWSERS, null) }
                        )
                    }

                    composable(ROUTE_GARENA_CODE) {
                        MonitorScreen(
                            pageMode = AppCaseMode.GARENA_CODE,
                            uiState = uiState,
                            onActivateCaseMode = { viewModel.selectCaseMode(AppCaseMode.GARENA_CODE) },
                            onToggleWhatsApp = { viewModel.toggleWhatsApp() },
                            onToggleInstagram = { viewModel.toggleInstagram() },
                            onToggleMonitoringActive = { viewModel.toggleMonitoringActive() },
                            onOpenSetupGuide = { viewModel.openSetupWizard() },
                            onOpenModeFloatingBrowser = {
                                viewModel.selectCaseMode(AppCaseMode.GARENA_CODE)
                                viewModel.openFloatingBrowser(
                                    url = BrowserHelper.GARENA_REWARD_URL,
                                    profilePackage = null,
                                    profileLabel = "reward.ff"
                                )
                            },
                            onOpenBrowsersTab = { navigateTo(ROUTE_BROWSERS, null) }
                        )
                    }

                    composable(ROUTE_BROWSERS) {
                        BrowsersScreen(
                            uiState = uiState,
                            onOpenFloatingBrowser = { url, pkg, label ->
                                viewModel.openFloatingBrowser(url, pkg, label)
                            },
                            onLaunchPhoneBrowser = { pkg, url ->
                                viewModel.launchPhoneBrowser(pkg, url)
                            },
                            onRestoreTab = { viewModel.restoreBrowserTab(it) },
                            onMinimizeTab = { viewModel.minimizeBrowserTab(it) },
                            onCloseTab = { viewModel.closeBrowserTab(it) },
                            onCloseAllTabs = { viewModel.closeAllBrowserTabs() },
                            onAutoRedeemChange = { viewModel.setAutoRedeemShop2Game(it) }
                        )
                    }

                    composable(ROUTE_SETTINGS) {
                        SettingsScreen(
                            uiState = uiState,
                            onVibrateChange = { viewModel.setVibrateOnCopy(it) },
                            onSoundChange = { viewModel.setSoundOnCopy(it) },
                            onAutoStartBootChange = { viewModel.setAutoStartOnBoot(it) },
                            onAutoRedeemChange = { viewModel.setAutoRedeemShop2Game(it) },
                            onOpenNotificationAccess = {
                                PermissionHelper.openNotificationListenerSettings(context)
                            },
                            onOpenAccessibilitySettings = {
                                PermissionHelper.openAccessibilitySettings(context)
                            },
                            onOpenOverlaySettings = {
                                PermissionHelper.openOverlaySettings(context)
                            },
                            onRequestBatteryOptimization = {
                                PermissionHelper.requestIgnoreBatteryOptimizations(context)
                            },
                            onRequestPostNotifications = requestPostNotifications,
                            onLockApp = { viewModel.lockApp() }
                        )
                    }

                    composable(ROUTE_ADMIN) {
                        AdminScreen(
                            uiState = uiState,
                            onGenerateKey = { durationSeconds, label ->
                                viewModel.generateUserKey(durationSeconds, label)
                            },
                            onCopyKey = { viewModel.copyKeyToClipboard(it) },
                            onDeleteKey = { viewModel.deleteGeneratedKey(it) },
                            onLockApp = { viewModel.lockApp() }
                        )
                    }
                }
            }
        }
    }

    if (uiState.showSetupWizard) {
        SetupWizardDialog(
            permissionStatus = uiState.permissionStatus,
            onGrantNotificationAccess = {
                PermissionHelper.openNotificationListenerSettings(context)
            },
            onGrantAccessibilityAccess = {
                PermissionHelper.openAccessibilitySettings(context)
            },
            onGrantOverlay = {
                PermissionHelper.openOverlaySettings(context)
            },
            onGrantBatteryOptimization = {
                PermissionHelper.requestIgnoreBatteryOptimizations(context)
            },
            onGrantPostNotifications = requestPostNotifications,
            onDismiss = { viewModel.dismissSetupWizard() }
        )
    }
}
