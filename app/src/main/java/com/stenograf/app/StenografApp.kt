package com.stenograf.app

import android.app.Application
import com.stenograf.app.asr.ModelManager
import com.stenograf.app.data.db.StenografDatabase
import com.stenograf.app.data.model.RecordingEntity
import com.stenograf.app.data.prefs.AppPreferences
import com.stenograf.app.data.repo.RecordingRepository
import com.stenograf.app.i18n.AppLocaleManager
import com.stenograf.app.service.ReTranscribeWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class StenografApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var database: StenografDatabase
        private set
    lateinit var preferences: AppPreferences
        private set
    lateinit var repository: RecordingRepository
        private set
    lateinit var modelManager: ModelManager
        private set
    lateinit var reTranscribeWorker: ReTranscribeWorker
        private set

    lateinit var recordingRecovery: Job
        private set

    private val _recoveredRecordings = MutableStateFlow<List<RecordingEntity>>(emptyList())
    val recoveredRecordings: StateFlow<List<RecordingEntity>> = _recoveredRecordings.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        AppLocaleManager.applyPreference("system")

        database = StenografDatabase.getInstance(this)
        preferences = AppPreferences(this)
        repository = RecordingRepository(this, database.dao())
        modelManager = ModelManager(this)
        reTranscribeWorker = ReTranscribeWorker(this, repository, modelManager)

        recordingRecovery = applicationScope.launch {
            val initialCode = runCatching { preferences.settingsFlow.first().uiLanguageCode }.getOrDefault("system")
            AppLocaleManager.applyPreference(initialCode)

            repository.ensureDefaultFolders()
            val recovered = repository.recoverUnfinishedRecordings()
            if (recovered.isNotEmpty()) {
                _recoveredRecordings.value = recovered
            }
            repository.cleanupOrphanedFiles(modelManager)
            modelManager.ensureVadInstalled()
        }

        applicationScope.launch {
            preferences.settingsFlow
                .map { it.uiLanguageCode }
                .distinctUntilChanged()
                .collect { code ->
                    AppLocaleManager.applyPreference(code)
                }
        }
    }

    fun dismissRecoveredNotice() {
        _recoveredRecordings.value = emptyList()
    }
}
