package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.MyCodeApp
import com.example.service.CodeMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val app = context.applicationContext as? MyCodeApp ?: return
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = app.container.preferencesRepository.current()
                if (prefs.autoStartOnBoot && prefs.hasAnyAppEnabled && prefs.hasValidAccess()) {
                    CodeMonitorService.startOrSync(context.applicationContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
