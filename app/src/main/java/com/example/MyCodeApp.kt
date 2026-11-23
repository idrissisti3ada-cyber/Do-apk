package com.example

import android.app.Application
import com.example.data.preferences.PreferencesRepository
import com.example.service.CodeCaptureEngine
import com.example.service.ScreenOcrProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(application: Application) {
    val preferencesRepository: PreferencesRepository by lazy {
        PreferencesRepository(application)
    }
    val captureEngine: CodeCaptureEngine by lazy {
        CodeCaptureEngine(
            appContext = application,
            preferencesRepository = preferencesRepository
        )
    }
}

class MyCodeApp : Application() {
    lateinit var container: AppContainer
        private set

    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Warm up preferences and ML Kit OCR model immediately on startup
        startupScope.launch {
            container.preferencesRepository.current()
        }
        ScreenOcrProcessor.warmup()
    }
}
