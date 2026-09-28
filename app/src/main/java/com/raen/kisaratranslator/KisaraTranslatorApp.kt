package com.raen.kisaratranslator

import android.app.Application
import com.raen.kisaratranslator.core.wakelock.WakeLockManager
import com.raen.kisaratranslator.data.download.TranslationModelManager
import com.raen.kisaratranslator.data.service.BatchTranslationService
import com.raen.kisaratranslator.data.service.TranslationService

class KisaraTranslatorApp : Application() {

    lateinit var modelManager: TranslationModelManager
        private set

    lateinit var wakeLockManager: WakeLockManager
        private set

    lateinit var translationService: TranslationService
        private set

    lateinit var batchTranslationService: BatchTranslationService
        private set

    override fun onCreate() {
        super.onCreate()
        com.raen.kisaratranslator.data.logger.AppLogger.init(this)
        com.raen.kisaratranslator.data.monitor.ResourceMonitor.start()
        modelManager = TranslationModelManager(this)
        wakeLockManager = WakeLockManager(this)
        translationService = TranslationService(this, modelManager, wakeLockManager)
        batchTranslationService = BatchTranslationService(this, translationService, wakeLockManager)
    }

    override fun onTerminate() {
        super.onTerminate()
        com.raen.kisaratranslator.data.monitor.ResourceMonitor.stop()
        translationService.close()
    }
}
