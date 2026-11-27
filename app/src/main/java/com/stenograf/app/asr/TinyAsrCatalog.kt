package com.stenograf.app.asr

import com.stenograf.app.i18n.AppStrings

data class TinyAsrModelSpec(
    val id: String,
    val fileName: String,
    val languages: List<String>,
    val sizeMb: Float,
    val bundledInApk: Boolean,
    val formatVersion: String = "TZF6 · INT4",
    val versionTag: String = "v6.0"
) {
    val unpackedName: String get() = fileName.removeSuffix(".gz")
    val assetName: String get() = "${unpackedName}.gzip"
    val downloadUrl: String get() = "https://github.com/dkaraush/tinyasr/raw/main/models/$fileName"

    fun localizedTitle(strings: AppStrings): String = when (id) {
        "en-ru-uk" -> strings.presetRuEnUk
        "fr-de" -> strings.presetDeFr
        "en-es-it-pt" -> "${strings.presetEsEn} / Italiano / Português"
        "zh" -> "${strings.presetZhHans} / ${strings.presetZhHant}"
        "ar" -> strings.presetAr
        "uz" -> "${strings.presetUzLatn} / ${strings.presetUzCyrl}"
        "en-fa" -> strings.presetFaEn
        "en-km" -> strings.presetKmEn
        "id-ms" -> strings.presetIdMs
        else -> id.uppercase()
    }

    fun matchesQuery(query: String, strings: AppStrings): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        if (id.lowercase().contains(q)) return true
        if (localizedTitle(strings).lowercase().contains(q)) return true
        return languages.any { code ->
            code.lowercase().contains(q) || TinyAsrCatalog.languageNativeName(code).lowercase().contains(q)
        }
    }
}

data class LanguagePreset(
    val id: String,
    val modelId: String,
    val langsCsv: String,
    val startLang: String,
    val shortLabel: String
) {
    fun label(strings: AppStrings): String = when (id) {
        "ru_en" -> strings.presetRuEn
        "ru_only" -> strings.presetRuOnly
        "ru_en_uk" -> strings.presetRuEnUk
        "en_only" -> strings.presetEnOnly
        "uk_en" -> strings.presetUkEn
        "de_only" -> strings.presetDeOnly
        "fr_only" -> strings.presetFrOnly
        "de_fr" -> strings.presetDeFr
        "es_en" -> strings.presetEsEn
        "it_en" -> strings.presetItEn
        "pt_en" -> strings.presetPtEn
        "zh_hans" -> strings.presetZhHans
        "zh_hant" -> strings.presetZhHant
        "ar" -> strings.presetAr
        "uz_latn" -> strings.presetUzLatn
        "uz_cyrl" -> strings.presetUzCyrl
        "fa_en" -> strings.presetFaEn
        "km_en" -> strings.presetKmEn
        "id_ms" -> strings.presetIdMs
        else -> shortLabel
    }
}

object TinyAsrCatalog {
    const val VAD_ASSET_NAME = "silero_vad.onnx.gzip"
    const val VAD_UNPACKED_NAME = "silero_vad.onnx"

    val MODELS: List<TinyAsrModelSpec> = listOf(
        TinyAsrModelSpec(
            id = "en-ru-uk",
            fileName = "en-ru-uk.tzf.gz",
            languages = listOf("en", "ru", "uk"),
            sizeMb = 31.9f,
            bundledInApk = true,
            formatVersion = "TZF6 · INT4 · Zipformer2 63M"
        ),
        TinyAsrModelSpec(
            id = "fr-de",
            fileName = "fr-de.tzf.gz",
            languages = listOf("fr", "de"),
            sizeMb = 30.8f,
            bundledInApk = true,
            formatVersion = "TZF6 · INT4 · Zipformer2 62M"
        ),
        TinyAsrModelSpec(
            id = "en-es-it-pt",
            fileName = "en-es-it-pt.tzf.gz",
            languages = listOf("en", "es", "it", "pt"),
            sizeMb = 30.7f,
            bundledInApk = false,
            formatVersion = "TZF6 · INT4 · Zipformer2"
        ),
        TinyAsrModelSpec(
            id = "zh",
            fileName = "zh.tzf.gz",
            languages = listOf("zh-Hans", "zh-Hant"),
            sizeMb = 35.5f,
            bundledInApk = false,
            formatVersion = "TZF6 · INT4 · Zipformer2"
        ),
        TinyAsrModelSpec(
            id = "ar",
            fileName = "ar.tzf.gz",
            languages = listOf(
                "ar", "ar-EG", "ar-Levantine", "ar-Gulf", "ar-IQ", "ar-MA",
                "ar-DZ", "ar-TN", "ar-LY", "ar-SD", "ar-YE", "ar-MR"
            ),
            sizeMb = 29.9f,
            bundledInApk = false,
            formatVersion = "TZF6 · INT4 · Zipformer2"
        ),
        TinyAsrModelSpec(
            id = "uz",
            fileName = "uz.tzf.gz",
            languages = listOf("uz-Latn", "uz-Latin-Russian", "uz-Cyrl"),
            sizeMb = 32.6f,
            bundledInApk = false,
            formatVersion = "TZF6 · INT4 · Zipformer2"
        ),
        TinyAsrModelSpec(
            id = "en-fa",
            fileName = "en-fa.tzf.gz",
            languages = listOf("en", "fa"),
            sizeMb = 28.7f,
            bundledInApk = false,
            formatVersion = "TZF6 · INT4 · Zipformer2"
        ),
        TinyAsrModelSpec(
            id = "en-km",
            fileName = "en-km.tzf.gz",
            languages = listOf("en", "km"),
            sizeMb = 28.7f,
            bundledInApk = false,
            formatVersion = "TZF6 · INT4 · Zipformer2"
        ),
        TinyAsrModelSpec(
            id = "id-ms",
            fileName = "id-ms.tzf.gz",
            languages = listOf("id", "ms"),
            sizeMb = 29.9f,
            bundledInApk = false,
            formatVersion = "TZF6 · INT4 · Zipformer2"
        )
    )

    val PRESETS: List<LanguagePreset> = listOf(
        LanguagePreset("ru_en", "en-ru-uk", "ru,en", "ru", "RU + EN"),
        LanguagePreset("ru_only", "en-ru-uk", "ru", "ru", "RU"),
        LanguagePreset("ru_en_uk", "en-ru-uk", "ru,en,uk", "ru", "RU/EN/UK"),
        LanguagePreset("en_only", "en-ru-uk", "en", "en", "EN"),
        LanguagePreset("uk_en", "en-ru-uk", "uk,en", "uk", "UK + EN"),
        LanguagePreset("de_only", "fr-de", "de", "de", "DE"),
        LanguagePreset("fr_only", "fr-de", "fr", "fr", "FR"),
        LanguagePreset("de_fr", "fr-de", "de,fr", "de", "DE / FR"),
        LanguagePreset("es_en", "en-es-it-pt", "es,en", "es", "ES + EN"),
        LanguagePreset("it_en", "en-es-it-pt", "it,en", "it", "IT + EN"),
        LanguagePreset("pt_en", "en-es-it-pt", "pt,en", "pt", "PT + EN"),
        LanguagePreset("zh_hans", "zh", "zh-Hans", "zh-Hans", "ZH-Hans"),
        LanguagePreset("zh_hant", "zh", "zh-Hant", "zh-Hant", "ZH-Hant"),
        LanguagePreset("ar", "ar", "ar", "ar", "AR"),
        LanguagePreset("uz_latn", "uz", "uz-Latn", "uz-Latn", "UZ-Latn"),
        LanguagePreset("uz_cyrl", "uz", "uz-Cyrl", "uz-Cyrl", "UZ-Cyrl"),
        LanguagePreset("fa_en", "en-fa", "fa,en", "fa", "FA + EN"),
        LanguagePreset("km_en", "en-km", "km,en", "km", "KM + EN"),
        LanguagePreset("id_ms", "id-ms", "id,ms", "id", "ID / MS")
    )

    fun findModel(id: String): TinyAsrModelSpec =
        MODELS.firstOrNull { it.id == id } ?: MODELS.first()

    fun findPreset(id: String): LanguagePreset =
        PRESETS.firstOrNull { it.id == id } ?: PRESETS.first()

    fun languageNativeName(code: String): String = when (code.lowercase()) {
        "ru" -> "Русский"
        "en" -> "English"
        "uk" -> "Українська"
        "de" -> "Deutsch"
        "fr" -> "Français"
        "es" -> "Español"
        "it" -> "Italiano"
        "pt" -> "Português"
        "zh-hans" -> "简体中文"
        "zh-hant" -> "繁體中文"
        "ar" -> "العربية"
        "fa" -> "فارسی"
        "km" -> "ភាសាខ្មែរ"
        "id" -> "Bahasa Indonesia"
        "ms" -> "Bahasa Melayu"
        "uz-latn" -> "Oʻzbek (lotin)"
        "uz-cyrl" -> "Ўзбек (кирилл)"
        "uz-latin-russian" -> "Oʻzbek + Рус"
        else -> code.uppercase()
    }
}
