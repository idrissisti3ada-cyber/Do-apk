package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.MyCodeApp
import com.example.data.preferences.MonitorPreferences
import com.example.domain.AccessKeyManager
import com.example.domain.AppCaseMode
import com.example.domain.GeneratedKeyRecord
import com.example.domain.KeyValidationResult
import com.example.domain.SourceApp
import com.example.service.CodeMonitorService
import com.example.service.FloatingBrowserService
import com.example.service.FloatingBrowserTabInfo
import com.example.util.BrowserHelper
import com.example.util.ClipboardHelper
import com.example.util.InstalledBrowserInfo
import com.example.util.PermissionHelper
import com.example.util.PermissionStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val preferences: MonitorPreferences = MonitorPreferences(),
    val permissionStatus: PermissionStatus = PermissionStatus(),
    val whatsAppInstalled: Boolean = false,
    val instagramInstalled: Boolean = false,
    val showSetupWizard: Boolean = false,
    val openBrowserTabs: List<FloatingBrowserTabInfo> = emptyList(),
    val installedBrowsers: List<InstalledBrowserInfo> = emptyList(),
    val keyInputError: String? = null,
    val latestGeneratedKey: GeneratedKeyRecord? = null,
    val nowMillis: Long = System.currentTimeMillis()
) {
    val hasValidAccess: Boolean
        get() = preferences.hasValidAccess(nowMillis)

    val remainingMillis: Long?
        get() = preferences.remainingMillis(nowMillis)
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val container = (application as MyCodeApp).container
    private val preferencesRepo = container.preferencesRepository

    private val permissionStatusFlow = MutableStateFlow(PermissionHelper.getStatus(application))
    private val showWizardFlow = MutableStateFlow(false)
    private val installedAppsFlow = MutableStateFlow(checkInstalledApps(application))
    private val installedBrowsersFlow = MutableStateFlow(BrowserHelper.getInstalledBrowsers(application))
    private val keyInputErrorFlow = MutableStateFlow<String?>(null)
    private val latestGeneratedKeyFlow = MutableStateFlow<GeneratedKeyRecord?>(null)
    private val nowMillisFlow = MutableStateFlow(System.currentTimeMillis())

    private var expiryTickerJob: Job? = null

    val uiState: StateFlow<MainUiState> = combine(
        preferencesRepo.preferencesFlow,
        permissionStatusFlow,
        installedAppsFlow,
        showWizardFlow,
        FloatingBrowserService.openTabs,
        installedBrowsersFlow,
        keyInputErrorFlow,
        latestGeneratedKeyFlow,
        nowMillisFlow
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        MainUiState(
            preferences = values[0] as MonitorPreferences,
            permissionStatus = values[1] as PermissionStatus,
            whatsAppInstalled = (values[2] as Pair<Boolean, Boolean>).first,
            instagramInstalled = (values[2] as Pair<Boolean, Boolean>).second,
            showSetupWizard = values[3] as Boolean,
            openBrowserTabs = values[4] as List<FloatingBrowserTabInfo>,
            installedBrowsers = values[5] as List<InstalledBrowserInfo>,
            keyInputError = values[6] as String?,
            latestGeneratedKey = values[7] as GeneratedKeyRecord?,
            nowMillis = values[8] as Long
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = MainUiState(
            preferences = preferencesRepo.currentFast(),
            permissionStatus = PermissionHelper.getStatus(application),
            installedBrowsers = installedBrowsersFlow.value
        )
    )

    init {
        viewModelScope.launch {
            val prefs = preferencesRepo.current()
            val now = System.currentTimeMillis()
            nowMillisFlow.value = now

            if (prefs.isUnlocked && !prefs.hasValidAccess(now)) {
                handleSessionExpired()
            } else {
                val perms = PermissionHelper.getStatus(getApplication())
                if (prefs.hasValidAccess(now) && !prefs.setupGuideSeen && !perms.allCoreGranted) {
                    showWizardFlow.value = true
                }
                syncServiceWithPreferences(prefs)
            }
        }

        viewModelScope.launch {
            preferencesRepo.preferencesFlow.collect { prefs ->
                updateExpiryTicker(prefs)
            }
        }
    }

    private fun updateExpiryTicker(prefs: MonitorPreferences) {
        expiryTickerJob?.cancel()
        if (!prefs.isUnlocked || prefs.isAdmin || prefs.isUnlimitedLicense || prefs.expiresAtMillis == null) {
            return
        }
        expiryTickerJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                nowMillisFlow.value = now
                if (!prefs.hasValidAccess(now)) {
                    handleSessionExpired()
                    break
                }
                delay(1000L)
            }
        }
    }

    private suspend fun handleSessionExpired() {
        val context = getApplication<Application>()
        preferencesRepo.lockSession(expired = true)
        CodeMonitorService.stop(context)
        FloatingBrowserService.closeAll(context)
    }

    fun submitAccessKey(rawKey: String) {
        viewModelScope.launch {
            val trimmed = rawKey.trim()
            if (trimmed.isEmpty()) {
                keyInputErrorFlow.value = "Please enter an access key."
                return@launch
            }

            val current = preferencesRepo.current()
            when (val result = AccessKeyManager.validateKey(trimmed, current.usedKeyIds)) {
                KeyValidationResult.AdminSuccess -> {
                    keyInputErrorFlow.value = null
                    preferencesRepo.redeemAdminKey()
                    val updated = preferencesRepo.current()
                    nowMillisFlow.value = System.currentTimeMillis()
                    if (!updated.setupGuideSeen && !permissionStatusFlow.value.allCoreGranted) {
                        showWizardFlow.value = true
                    }
                    syncServiceWithPreferences(updated)
                }

                is KeyValidationResult.UserSuccess -> {
                    keyInputErrorFlow.value = null
                    val now = System.currentTimeMillis()
                    nowMillisFlow.value = now
                    preferencesRepo.redeemUserKey(result.parsed, now)
                    val updated = preferencesRepo.current()
                    if (!updated.setupGuideSeen && !permissionStatusFlow.value.allCoreGranted) {
                        showWizardFlow.value = true
                    }
                    syncServiceWithPreferences(updated)
                }

                KeyValidationResult.AlreadyUsed -> {
                    keyInputErrorFlow.value =
                        "This user key has already been used once. Ask the admin for a new key."
                }

                KeyValidationResult.InvalidFormat -> {
                    keyInputErrorFlow.value = "Invalid key. Check the key and try again."
                }
            }
        }
    }

    fun clearKeyInputError() {
        if (keyInputErrorFlow.value != null) {
            keyInputErrorFlow.value = null
        }
    }

    fun lockApp() {
        viewModelScope.launch {
            val context = getApplication<Application>()
            keyInputErrorFlow.value = null
            preferencesRepo.lockSession(expired = false)
            CodeMonitorService.stop(context)
            FloatingBrowserService.closeAll(context)
        }
    }

    fun generateUserKey(durationSeconds: Long, label: String) {
        viewModelScope.launch {
            val record = AccessKeyManager.generateUserKey(
                durationSeconds = durationSeconds,
                label = label
            )
            latestGeneratedKeyFlow.value = record
            preferencesRepo.saveGeneratedKeyRecord(record)
            ClipboardHelper.copyToClipboard(getApplication(), record.key)
        }
    }

    fun copyKeyToClipboard(key: String) {
        ClipboardHelper.copyToClipboard(getApplication(), key)
    }

    fun deleteGeneratedKey(keyId: String) {
        viewModelScope.launch {
            if (latestGeneratedKeyFlow.value?.keyId == keyId) {
                latestGeneratedKeyFlow.value = null
            }
            preferencesRepo.deleteGeneratedKeyRecord(keyId)
        }
    }

    fun refreshPermissions() {
        val context = getApplication<Application>()
        val status = PermissionHelper.getStatus(context)
        permissionStatusFlow.value = status
        installedAppsFlow.value = checkInstalledApps(context)
        installedBrowsersFlow.value = BrowserHelper.getInstalledBrowsers(context)

        viewModelScope.launch {
            val prefs = preferencesRepo.current()
            val now = System.currentTimeMillis()
            nowMillisFlow.value = now
            if (prefs.isUnlocked && !prefs.hasValidAccess(now)) {
                handleSessionExpired()
            } else {
                syncServiceWithPreferences(prefs)
            }
        }
    }

    fun selectCaseMode(mode: AppCaseMode) {
        viewModelScope.launch {
            val current = preferencesRepo.current()
            if (current.activeCaseMode != mode) {
                preferencesRepo.setActiveCaseMode(mode)
                container.captureEngine.activateWaitingSession(mode)
            }
        }
    }

    fun toggleWhatsApp() {
        viewModelScope.launch {
            val current = preferencesRepo.current()
            val next = !current.whatsAppEnabled
            if (next) {
                container.captureEngine.activateWaitingSession(current.activeCaseMode)
            }
            preferencesRepo.setWhatsAppEnabled(next)
            val updated = preferencesRepo.current()
            syncServiceWithPreferences(updated)
            if (next && !permissionStatusFlow.value.allCoreGranted) {
                showWizardFlow.value = true
            }
        }
    }

    fun toggleInstagram() {
        viewModelScope.launch {
            val current = preferencesRepo.current()
            val next = !current.instagramEnabled
            if (next) {
                container.captureEngine.activateWaitingSession(current.activeCaseMode)
            }
            preferencesRepo.setInstagramEnabled(next)
            val updated = preferencesRepo.current()
            syncServiceWithPreferences(updated)
            if (next && !permissionStatusFlow.value.allCoreGranted) {
                showWizardFlow.value = true
            }
        }
    }

    fun toggleMonitoringActive() {
        viewModelScope.launch {
            val current = preferencesRepo.current()
            val nextActive = !current.monitoringActive
            if (nextActive) {
                container.captureEngine.activateWaitingSession(current.activeCaseMode)
            }
            preferencesRepo.setMonitoringActive(nextActive)
        }
    }

    fun openFloatingBrowser(
        url: String = BrowserHelper.DEFAULT_REDEEM_URL,
        profilePackage: String? = null,
        profileLabel: String = "Floating"
    ) {
        val context = getApplication<Application>()
        if (!PermissionHelper.hasOverlayPermission(context)) {
            showWizardFlow.value = true
            return
        }
        FloatingBrowserService.openFloatingBrowser(
            context = context,
            url = url,
            profilePackage = profilePackage,
            profileLabel = profileLabel
        )
    }

    fun launchPhoneBrowser(packageName: String, url: String) {
        BrowserHelper.launchExternalBrowser(getApplication(), packageName, url)
    }

    fun restoreBrowserTab(tabId: Int) {
        FloatingBrowserService.restoreTab(getApplication(), tabId)
    }

    fun minimizeBrowserTab(tabId: Int) {
        FloatingBrowserService.minimizeTab(getApplication(), tabId)
    }

    fun closeBrowserTab(tabId: Int) {
        FloatingBrowserService.closeTab(getApplication(), tabId)
    }

    fun closeAllBrowserTabs() {
        FloatingBrowserService.closeAll(getApplication())
    }

    fun setVibrateOnCopy(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepo.setVibrateOnCopy(enabled)
        }
    }

    fun setSoundOnCopy(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepo.setSoundOnCopy(enabled)
        }
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepo.setAutoStartOnBoot(enabled)
        }
    }

    fun setAutoRedeemShop2Game(enabled: Boolean) {
        viewModelScope.launch {
            preferencesRepo.setAutoRedeemShop2Game(enabled)
        }
    }

    fun openSetupWizard() {
        showWizardFlow.value = true
    }

    fun dismissSetupWizard() {
        showWizardFlow.value = false
        viewModelScope.launch {
            preferencesRepo.markSetupGuideSeen()
        }
    }

    private fun syncServiceWithPreferences(prefs: MonitorPreferences) {
        val context = getApplication<Application>()
        if (prefs.hasValidAccess(nowMillisFlow.value) && prefs.hasAnyAppEnabled) {
            CodeMonitorService.startOrSync(context)
        } else {
            CodeMonitorService.stop(context)
        }
    }

    private fun checkInstalledApps(context: Application): Pair<Boolean, Boolean> {
        val hasWa = SourceApp.WHATSAPP.packages.any { PermissionHelper.isPackageInstalled(context, it) }
        val hasIg = SourceApp.INSTAGRAM.packages.any { PermissionHelper.isPackageInstalled(context, it) }
        return hasWa to hasIg
    }
}
