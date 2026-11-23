package com.example.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.example.domain.AppCaseMode

data class InstalledBrowserInfo(
    val packageName: String,
    val appName: String,
    val isPriorityBrowser: Boolean
)

object BrowserHelper {
    const val SHOP2GAME_URL = "https://shop2game.com"
    const val GARENA_REWARD_URL = "https://reward.ff.garena.com"
    const val DEFAULT_REDEEM_URL = SHOP2GAME_URL

    fun defaultUrlFor(mode: AppCaseMode): String = mode.defaultUrl

    private val priorityPackages = listOf(
        "com.lemurbrowser.exts",
        "com.android.chrome",
        "com.chrome.beta",
        "com.brave.browser",
        "com.microsoft.emmx",
        "org.mozilla.firefox",
        "com.sec.android.app.sbrowser",
        "com.opera.browser"
    )

    fun getInstalledBrowsers(context: Context): List<InstalledBrowserInfo> {
        val pm = context.packageManager
        val probeIntent = Intent(Intent.ACTION_VIEW, Uri.parse(SHOP2GAME_URL)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
        }

        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(
                probeIntent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(probeIntent, PackageManager.MATCH_ALL)
        }

        val seen = HashSet<String>()
        val list = ArrayList<InstalledBrowserInfo>()

        for (info in resolved) {
            val activityInfo = info.activityInfo ?: continue
            val pkg = activityInfo.packageName ?: continue
            if (pkg == context.packageName || !seen.add(pkg)) continue
            val label = info.loadLabel(pm)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
            list.add(
                InstalledBrowserInfo(
                    packageName = pkg,
                    appName = label,
                    isPriorityBrowser = pkg in priorityPackages
                )
            )
        }

        return list.sortedWith(
            compareBy<InstalledBrowserInfo> {
                val idx = priorityPackages.indexOf(it.packageName)
                if (idx == -1) Int.MAX_VALUE else idx
            }.thenBy { it.appName.lowercase() }
        )
    }

    fun launchExternalBrowser(context: Context, packageName: String, url: String): Boolean {
        val normalizedUrl = normalizeUrl(url)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).apply {
            setPackage(packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun normalizeUrl(raw: String, fallbackUrl: String = SHOP2GAME_URL): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return fallbackUrl
        return if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }

    fun userAgentForProfile(packageName: String?): String {
        return when (packageName) {
            "com.lemurbrowser.exts" ->
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36 LemurBrowser"
            else ->
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
        }
    }
}
