package com.stenograf.app.i18n

import android.content.res.Resources
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class AppLanguage(
    val code: String,
    val nativeName: String,
    val isRtl: Boolean = false
) {
    SYSTEM("system", "System", false),
    RU("ru", "Русский"),
    EN("en", "English"),
    UK("uk", "Українська"),
    AB("ab", "Аԥсшәа"),
    KA("ka", "ქართული"),
    HY("hy", "Հայերեն"),
    AZ("az", "Azərbaycan dili"),
    BE("be", "Беларуская"),
    DE("de", "Deutsch"),
    FR("fr", "Français"),
    ES("es", "Español"),
    IT("it", "Italiano"),
    PT("pt", "Português"),
    PL("pl", "Polski"),
    CS("cs", "Čeština"),
    TR("tr", "Türkçe"),
    AR("ar", "العربية", isRtl = true),
    FA("fa", "فارسی", isRtl = true),
    HE("he", "עברית", isRtl = true),
    ZH_HANS("zh-Hans", "简体中文"),
    ZH_HANT("zh-Hant", "繁體中文"),
    JA("ja", "日本語"),
    KO("ko", "한국어"),
    KK("kk", "Қазақша"),
    UZ("uz", "Oʻzbekcha"),
    ID("id", "Bahasa Indonesia"),
    VI("vi", "Tiếng Việt"),
    TH("th", "ไทย"),
    HI("hi", "हिन्दी"),
    BN("bn", "বাংলা"),
    TA("ta", "தமிழ்"),
    TE("te", "తెలుగు"),
    MR("mr", "मराठी"),
    GU("gu", "ગુજરાતી"),
    KN("kn", "ಕನ್ನಡ"),
    ML("ml", "മലയാളം"),
    PA("pa", "ਪੰਜਾਬੀ"),
    UR("ur", "اردو", isRtl = true),
    OR("or", "ଓଡ଼ିଆ"),
    AS("as", "অসমীয়া");

    companion object {
        val selectableLanguages: List<AppLanguage> = entries.filter { it != SYSTEM }

        fun fromCode(code: String?): AppLanguage {
            if (code.isNullOrBlank() || code == "system") return SYSTEM
            return entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: SYSTEM
        }
    }
}

data class AppStrings(
    val appName: String,
    // Navigation & Common
    val back: String,
    val cancel: String,
    val close: String,
    val save: String,
    val delete: String,
    val rename: String,
    val copy: String,
    val share: String,
    val ok: String,
    val retry: String,
    val search: String,
    val done: String,
    val menu: String,
    val wordsShort: String,

    // Library
    val recordingsTitle: String,
    val searchHint: String,
    val sortTitle: String,
    val sortDateDesc: String,
    val sortDateAsc: String,
    val sortDurationDesc: String,
    val sortTitleAsc: String,
    val allFolders: String,
    val noFolder: String,
    val newFolder: String,
    val folderNameLabel: String,
    val create: String,
    val deleteFolder: String,
    val defaultFolderLectures: String,
    val defaultFolderMeetings: String,
    val defaultFolderNotes: String,
    val moveToFolder: String,
    val newRecording: String,
    val importAudio: String,
    val settings: String,
    val emptyLibraryTitle: String,
    val emptyLibrarySubtitle: String,
    val emptySearchTitle: String,
    val emptySearchSubtitle: String,
    val dateToday: String,
    val dateYesterday: String,
    val dateThisWeek: String,
    val statusHasTranscript: String,
    val statusNoTranscript: String,
    val statusRecovered: String,
    val statusUnprocessedTail: String,
    val recoveredBannerTitle: String,
    val recoveredBannerBody: String,
    val activeRecordingBanner: String,
    val returnToRecording: String,
    val renameRecordingTitle: String,
    val deleteRecordingTitle: String,
    val deleteRecordingMessage: String,
    val defaultRecordingPrefix: String,
    val importedPrefix: String,

    // Active Recording
    val preparingModel: String,
    val loadingModelProgress: String,
    val switchingModel: String,
    val recordingPaused: String,
    val recordingLocalStatus: String,
    val threadsWord: String,
    val queueLabel: String,
    val unclearSpeaker: String = "Unclear speaker",
    val speakerDiarizationTitle: String = "Separate speakers",
    val speakerDiarizationSubtitle: String = "Identify voices on your device with TinyASR or Groq.",
    val speakerDiarizationUnavailable: String = "Speaker identification is unavailable. Text transcription continues.",
    val groqRetryStatus: String = "Retrying Groq",
    val groqRetryBody: String = "Audio is being saved. Transcription resumes from the first pending chunk.",
    val groqCatchUpStatus: String = "Catching up",
    val speakPrompt: String,
    val pausedPrompt: String,
    val jumpToLiveText: String,
    val bookmarkButton: String,
    val pauseButton: String,
    val resumeButton: String,
    val stopButton: String,
    val bookmarkDialogTitle: String,
    val bookmarkNoteLabel: String,
    val addButton: String,
    val bookmarkAddedNotice: String,
    val bookmarkDefaultLabel: String,
    val micPermissionRequired: String,
    val micAccessFailed: String,
    val micLostNotice: String,
    val audioFocusLostNotice: String,
    val lowStorageWarn: String,
    val criticalStorageStop: String,
    val finishingTranscript: String,
    val asrPausedSavingAudio: String,
    val asrSessionError: String,
    val audioWriteError: String,

    // Notifications
    val notifChannelName: String,
    val notifChannelDesc: String,
    val notifRecordingActive: String,
    val notifRecordingPaused: String,
    val notifSaving: String,

    // Detail & Player
    val noTranscriptTitle: String,
    val noTranscriptSubtitle: String,
    val transcribeNow: String,
    val reTranscribe: String,
    val reTranscribeDesc: String,
    val reTranscribeModelLang: String,
    val accuracyBeam4: String,
    val fastGreedy1: String,
    val startAction: String,
    val reTranscribeDone: String,
    val unprocessedTailTitle: String,
    val unprocessedTailSubtitle: String,
    val processTailButton: String,
    val searchInTextHint: String,
    val editFragmentTitle: String,
    val copiedParagraph: String,
    val copiedAllText: String,
    val copyAll: String,
    val editAction: String,
    val shareText: String,
    val shareAudio: String,
    val exportTitle: String,
    val saveFileAs: String,
    val exportSavedSuccess: String,
    val exportError: String,
    val audioFileNotFound: String,
    val playerOpenError: String,
    val playbackError: String,

    // Export Formats & Document Metadata
    val formatTxt: String,
    val formatDocx: String,
    val formatPdf: String,
    val formatSrt: String,
    val docMetaDate: String,
    val docMetaDuration: String,
    val docMetaLanguage: String,
    val docPageShort: String,

    // Import & Re-transcribe Progress
    val decodingAudio: String,
    val recognizingProgress: String,
    val importError: String,
    val audioTrackNotFound: String,

    // Settings
    val settingsTitle: String,
    val sectionInterface: String,
    val uiLanguageTitle: String,
    val uiLanguageSystem: String,
    val uiLanguageSubtitle: String,
    val themeTitle: String,
    val themeSystem: String,
    val themeDark: String,
    val themeLight: String,
    val sectionAsr: String,
    val asrLanguageSeparationNote: String,
    val defaultAsrPresetTitle: String,
    val decodingAccuracyTitle: String,
    val decodingAccuracyBeam: String,
    val decodingAccuracyGreedy: String,
    val asrThreadsTitle: String,
    val threadsAuto: String,
    val threadsAutoLong: String,
    val threadsCount: String,
    val threadsSingleLowHeat: String,
    val autoParagraphTitle: String,
    val autoParagraphSubtitle: String,
    val showTimestampsTitle: String,
    val showTimestampsSubtitle: String,
    val sectionAudio: String,
    val audioQualityTitle: String,
    val formatWav16k: String,
    val formatM4a48k: String,
    val formatWav48k: String,
    val rawMicTitle: String,
    val rawMicSubtitle: String,
    val keepScreenOnTitle: String,
    val keepScreenOnSubtitle: String,
    val sectionModels: String,
    val manageModelsTitle: String,
    val manageModelsSubtitle: String,
    val sectionStorage: String,
    val manageStorageTitle: String,
    val storageBreakdownAudio: String,
    val storageBreakdownModels: String,
    val storageBreakdownCache: String,
    val storageBreakdownDb: String,
    val storageBreakdownFree: String,
    val clearCacheButton: String,
    val cacheClearedNotice: String,
    val resetAllDataButton: String,
    val resetAllDataTitle: String,
    val resetAllDataMessage: String,
    val resetAllDataConfirm: String,
    val resetAllDataDone: String,
    val sectionAbout: String,
    val aboutOfflineText: String,
    val aboutEngineText: String,
    val aboutVadText: String,

    // Model Manager
    val modelManagerTitle: String,
    val modelSearchHint: String,
    val modelFilterAll: String,
    val modelFilterInstalled: String,
    val modelFilterAvailable: String,
    val modelAsrNote: String,
    val modelInstalledLabel: String,
    val modelBuiltInLabel: String,
    val modelAvailableLabel: String,
    val modelDownloadingLabel: String,
    val modelPausedLabel: String,
    val modelVerifyingLabel: String,
    val modelDownloadButton: String,
    val modelPauseButton: String,
    val modelResumeButton: String,
    val modelUpdateButton: String,
    val modelImportButton: String,
    val modelDeleteButton: String,
    val modelDeleteTitle: String,
    val modelDeleteMessage: String,
    val modelSupportedLangsLabel: String,
    val modelVersionLabel: String,
    val modelDownloadedNotice: String,
    val modelImportedNotice: String,
    val modelDeletedNotice: String,
    val modelDownloadError: String,
    val modelCorruptError: String,
    val modelCannotDeleteActive: String,
    val modelRequiredInstallPrompt: String,

    // ASR Language Presets & Names
    val presetRuEn: String,
    val presetRuOnly: String,
    val presetRuEnUk: String,
    val presetEnOnly: String,
    val presetUkEn: String,
    val presetDeOnly: String,
    val presetFrOnly: String,
    val presetDeFr: String,
    val presetEsEn: String,
    val presetItEn: String,
    val presetPtEn: String,
    val presetZhHans: String,
    val presetZhHant: String,
    val presetAr: String,
    val presetUzLatn: String,
    val presetUzCyrl: String,
    val presetFaEn: String,
    val presetKmEn: String,
    val presetIdMs: String,
    val expandLiveTranscript: String = "Full-screen transcript",
    val exitFullScreenTranscript: String = "Exit full screen"
)

data class VantaExtraStrings(
    val renameFolder: String = "Edit folder",
    val folderColorLabel: String = "Color",
    val modelsShort: String = "Models",
    val selectedCountLabel: String = "Selected",
    val editNoteAction: String = "Add note",
    val autoScrollLabel: String = "Auto-scroll",
    val jumpToCurrentPosition: String = "Current position",
    val fontStyleLabel: String = "Font",
    val fontSansLabel: String = "Sans",
    val fontSerifLabel: String = "Serif",
    val copyAsText: String = "Copy text",
    val cacheEmptyLabel: String = "Cache empty",
    val offlineBadgeLabel: String = "100% Offline",
    val selectPresetAction: String = "Use preset",
    val totalModelsStorageLabel: String = "Models on device",
    val listeningPrompt: String = "Listening…",
    val removeBookmarkAction: String = "Remove bookmark",
    val addBookmarkAction: String = "Add bookmark",
    val prevBookmark: String = "Previous bookmark",
    val nextBookmark: String = "Next bookmark",
    val uncategorizedFolder: String = "Unsorted",
    val copySnippetAction: String = "Copy fragment",
    val playFromHereAction: String = "Play from here",
    val distantSpeechBoostTitle: String = "Boost quiet & distant speech",
    val distantSpeechBoostSubtitle: String = "Adaptive speech leveling for distant lecturers and quiet files",
    val developerRoleLabel: String = "Developer",
    val developerTelegramSubtitle: String = "Telegram · feedback & updates",
    val requiredFieldMessage: String = "This field is required"
)

val VantaExtraStringsRu = VantaExtraStrings(
    renameFolder = "Изменить папку",
    folderColorLabel = "Цвет",
    modelsShort = "Модели",
    selectedCountLabel = "Выбрано",
    editNoteAction = "Заметка",
    autoScrollLabel = "Автопрокрутка",
    jumpToCurrentPosition = "К текущей позиции",
    fontStyleLabel = "Шрифт",
    fontSansLabel = "Гротеск",
    fontSerifLabel = "Антиква",
    copyAsText = "Копировать текст",
    cacheEmptyLabel = "Кэш пуст",
    offlineBadgeLabel = "100% Оффлайн",
    selectPresetAction = "Выбрать пресет",
    totalModelsStorageLabel = "Модели на устройстве",
    listeningPrompt = "Слушаю…",
    removeBookmarkAction = "Убрать метку",
    addBookmarkAction = "Поставить метку",
    prevBookmark = "Предыдущая метка",
    nextBookmark = "Следующая метка",
    uncategorizedFolder = "Без папки",
    copySnippetAction = "Копировать фрагмент",
    playFromHereAction = "Слушать с этого места",
    distantSpeechBoostTitle = "Усиление тихой речи",
    distantSpeechBoostSubtitle = "Автоуровень для лекторов издалека и тихих аудиофайлов",
    developerRoleLabel = "Разработчик",
    developerTelegramSubtitle = "Telegram · связь и обновления",
    requiredFieldMessage = "Заполните это поле"
)

val VantaExtraStringsUk = VantaExtraStrings(
    renameFolder = "Змінити папку",
    folderColorLabel = "Колір",
    modelsShort = "Моделі",
    selectedCountLabel = "Вибрано",
    editNoteAction = "Нотатка",
    autoScrollLabel = "Автопрокрутка",
    jumpToCurrentPosition = "До поточної позиції",
    fontStyleLabel = "Шрифт",
    fontSansLabel = "Гротеск",
    fontSerifLabel = "Антиква",
    copyAsText = "Копіювати текст",
    cacheEmptyLabel = "Кеш порожній",
    offlineBadgeLabel = "100% Офлайн",
    selectPresetAction = "Обрати пресет",
    totalModelsStorageLabel = "Моделі на пристрої",
    listeningPrompt = "Слухаю…",
    removeBookmarkAction = "Прибрати мітку",
    addBookmarkAction = "Поставити мітку",
    prevBookmark = "Попередня мітка",
    nextBookmark = "Наступна мітка",
    uncategorizedFolder = "Без папки",
    copySnippetAction = "Копіювати фрагмент",
    playFromHereAction = "Слухати звідси",
    distantSpeechBoostTitle = "Підсилення тихого мовлення",
    distantSpeechBoostSubtitle = "Авторівень для лекторів здалеку та тихих аудіофайлів",
    developerRoleLabel = "Розробник",
    developerTelegramSubtitle = "Telegram · зв'язок та оновлення",
    requiredFieldMessage = "Заповніть це поле"
)

val LocalExtraStrings = staticCompositionLocalOf { VantaExtraStringsRu }

val LocalStrings = staticCompositionLocalOf<AppStrings> {
    error("AppStrings not provided")
}

object AppLocaleManager {
    private val _currentLanguage = MutableStateFlow(AppLanguage.RU)
    val currentLanguage: StateFlow<AppLanguage> = _currentLanguage.asStateFlow()

    private val _strings = MutableStateFlow(stringsForLanguage(AppLanguage.RU))
    val strings: StateFlow<AppStrings> = _strings.asStateFlow()

    private val _extraStrings = MutableStateFlow(extraStringsForLanguage(AppLanguage.RU))
    val extraStrings: StateFlow<VantaExtraStrings> = _extraStrings.asStateFlow()

    fun currentStrings(): AppStrings = _strings.value

    fun applyPreference(prefCode: String) {
        val resolved = resolveLanguage(prefCode)
        _currentLanguage.value = resolved
        _strings.value = stringsForLanguage(resolved)
        _extraStrings.value = extraStringsForLanguage(resolved)
    }

    fun extraStringsForLanguage(language: AppLanguage): VantaExtraStrings = when (language) {
        AppLanguage.RU, AppLanguage.BE, AppLanguage.AB, AppLanguage.KK -> VantaExtraStringsRu
        AppLanguage.UK -> VantaExtraStringsUk
        AppLanguage.SYSTEM -> extraStringsForLanguage(resolveLanguage("system"))
        else -> VantaExtraStrings()
    }

    fun resolveLanguage(prefCode: String): AppLanguage {
        val pref = AppLanguage.fromCode(prefCode)
        if (pref != AppLanguage.SYSTEM) return pref
        val locales = Resources.getSystem().configuration.locales
        for (i in 0 until locales.size()) {
            val loc = locales.get(i)
            matchLocale(loc)?.let { return it }
        }
        return matchLocale(Locale.getDefault()) ?: AppLanguage.EN
    }

    private fun matchLocale(locale: Locale): AppLanguage? {
        val lang = locale.language.lowercase()
        val script = locale.script.lowercase()
        val country = locale.country.uppercase()
        if (lang == "zh") {
            return if (script == "hant" || country in setOf("TW", "HK", "MO")) {
                AppLanguage.ZH_HANT
            } else {
                AppLanguage.ZH_HANS
            }
        }
        val normalized = when (lang) {
            "iw" -> "he"
            "in" -> "id"
            else -> lang
        }
        return AppLanguage.selectableLanguages.firstOrNull { it.code.equals(normalized, ignoreCase = true) }
    }

    fun stringsForLanguage(language: AppLanguage): AppStrings {
        return when (language) {
            AppLanguage.RU -> StringsRu
            AppLanguage.EN -> StringsEn
            AppLanguage.UK -> StringsUk
            AppLanguage.AB -> StringsAb
            AppLanguage.KA -> StringsKa
            AppLanguage.HY -> StringsHy
            AppLanguage.AZ -> StringsAz
            AppLanguage.BE -> StringsBe
            AppLanguage.DE -> StringsDe
            AppLanguage.FR -> StringsFr
            AppLanguage.ES -> StringsEs
            AppLanguage.IT -> StringsIt
            AppLanguage.PT -> StringsPt
            AppLanguage.PL -> StringsPl
            AppLanguage.CS -> StringsCs
            AppLanguage.TR -> StringsTr
            AppLanguage.AR -> StringsAr
            AppLanguage.FA -> StringsFa
            AppLanguage.HE -> StringsHe
            AppLanguage.ZH_HANS -> StringsZhHans
            AppLanguage.ZH_HANT -> StringsZhHant
            AppLanguage.JA -> StringsJa
            AppLanguage.KO -> StringsKo
            AppLanguage.KK -> StringsKk
            AppLanguage.UZ -> StringsUz
            AppLanguage.ID -> StringsId
            AppLanguage.VI -> StringsVi
            AppLanguage.TH -> StringsTh
            AppLanguage.HI -> StringsHi
            AppLanguage.BN -> StringsBn
            AppLanguage.TA -> StringsTa
            AppLanguage.TE -> StringsTe
            AppLanguage.MR -> StringsMr
            AppLanguage.GU -> StringsGu
            AppLanguage.KN -> StringsKn
            AppLanguage.ML -> StringsMl
            AppLanguage.PA -> StringsPa
            AppLanguage.UR -> StringsUr
            AppLanguage.OR -> StringsOr
            AppLanguage.AS -> StringsAs
            AppLanguage.SYSTEM -> stringsForLanguage(resolveLanguage("system"))
        }
    }
}
