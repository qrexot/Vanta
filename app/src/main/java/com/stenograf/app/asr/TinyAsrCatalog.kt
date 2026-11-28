package com.stenograf.app.asr

import com.stenograf.app.i18n.AppStrings

data class OnnxDownload(val url: String, val bytes: Long, val sha256: String)

data class BundleFileSpec(
    val relativePath: String,
    val url: String,
    val bytes: Long,
    val sha256: String
)

data class TinyAsrModelSpec(
    val id: String,
    val fileName: String,
    val languages: List<String>,
    val sizeMb: Float,
    val bundledInApk: Boolean,
    val formatVersion: String = "TZF6 · INT4",
    val versionTag: String = "v6.0",
    val onnxDownload: OnnxDownload? = null,
    val bundleFiles: List<BundleFileSpec> = emptyList()
) {
    val unpackedName: String get() = fileName.removeSuffix(".gz")
    val assetName: String get() = "${unpackedName}.gzip"
    val isMultiFileBundle: Boolean get() = bundleFiles.isNotEmpty()
    val isOnnxModel: Boolean get() = onnxDownload != null || bundleFiles.isNotEmpty()
    val expectedTotalBytes: Long
        get() = onnxDownload?.bytes
            ?: bundleFiles.sumOf { it.bytes }.takeIf { it > 0L }
            ?: (sizeMb * 1_048_576f).toLong()
    val downloadUrl: String get() = onnxDownload?.url ?: "https://github.com/dkaraush/tinyasr/raw/main/models/$fileName"

    fun localizedTitle(strings: AppStrings): String = when (id) {
        TinyAsrCatalog.GIGAAM_V4 -> "GigaAM v4 · Русский + English"
        TinyAsrCatalog.GIGAAM -> "GigaAM v3 INT8"
        TinyAsrCatalog.MOSSFORMER -> "MossFormer2 SS INT8"
        TinyAsrCatalog.DEEPFILTER2 -> "DeepFilterNet2"
        TinyAsrCatalog.DEEPFILTER3 -> "DeepFilterNet3"
        TinyAsrCatalog.FRCRN -> "FRCRN"
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
        TinyAsrCatalog.GIGAAM_V4 -> "GigaAM v4 · Русский + English"
        TinyAsrCatalog.GIGAAM -> "GigaAM v3 INT8 · ${strings.presetRuOnly}"
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
    const val GIGAAM = "gigaam-v3"
    const val GIGAAM_V4 = "gigaam-v4"
    const val GIGAAM_V4_REVISION = "f2766b1dddd1e4cdc59d0d1d44d93201435064a8"
    const val MOSSFORMER = "mossformer2-ss"
    const val DEEPFILTER2 = "deepfilternet2"
    const val DEEPFILTER3 = "deepfilternet3"
    const val FRCRN = "frcrn-se"
    const val VAD_ASSET_NAME = "silero_vad.onnx.gzip"
    const val VAD_UNPACKED_NAME = "silero_vad.onnx"

    private fun gigaV4File(relativePath: String, bytes: Long, sha256: String) = BundleFileSpec(
        relativePath = relativePath,
        url = "https://huggingface.co/ai-babai/gigaam-v4-onnx/resolve/$GIGAAM_V4_REVISION/$relativePath",
        bytes = bytes,
        sha256 = sha256
    )

    val GIGAAM_V4_FILES: List<BundleFileSpec> = listOf(
        gigaV4File(
            "rnnt-streaming-640ms/v4_rnnt_640ms_stream_frontend.onnx",
            3_228_803L,
            "ace0f495c4f57cf8c09e1c961e7b811693b26066627bd7e2dddb46548fe2a570"
        ),
        gigaV4File(
            "rnnt-streaming-640ms/v4_rnnt_640ms_stream_step.int8.onnx",
            241_144_602L,
            "c0eb2185b884ceaef5f0ab79d01f6032591577f1aabbf04b85e7e157436ed69c"
        ),
        gigaV4File(
            "rnnt-streaming-640ms/v4_rnnt_640ms_e2e_decoder.onnx",
            4_599_971L,
            "9d670e5d65660991c9b4bc634ebbbb1833b7ae2e591bcdf02b64087742fc9012"
        ),
        gigaV4File(
            "rnnt-streaming-640ms/v4_rnnt_640ms_e2e_joint.onnx",
            2_712_939L,
            "2562c6928dddd340910e8fec75094417d7cbadea7dcea7d4df99892397a19cff"
        ),
        gigaV4File(
            "rnnt-streaming-640ms/tokens.e2e.txt",
            9_982L,
            "4af4f59269ff664ea6a55838b12fa0cb5e07bd5e019202b4d38b3fbc9275e923"
        ),
        gigaV4File(
            "rnnt-streaming-640ms/streaming.json",
            6_203L,
            "f2f4f3013a96a30064a6d784ef9dff72769c578f6818565c090c3f712ba96204"
        ),
        gigaV4File(
            "rnnt/v4_rnnt_encoder.int8.onnx",
            242_532_777L,
            "4cc7dd3e2a5f1503239df920976d419287f67df87f1849fff0c34ce4b3d58e20"
        ),
        gigaV4File(
            "rnnt/v4_rnnt_advance.onnx",
            12_753_376L,
            "d992302d83f03367202d4200cbdf2f74e65cb36d1e553f5ce330e73f26949b45"
        ),
        gigaV4File(
            "rnnt/v4_rnnt_mel.npz",
            43_014L,
            "8c6fe983296b38324dc41bff809d65dd9199d064f7873936d0b9e71f2e53784d"
        ),
        gigaV4File(
            "rnnt/manifest.json",
            2_474L,
            "794651f25912afc17dc0f2d883f99da886bb8a3e65585c71690d8f3f43e968cf"
        ),
        gigaV4File(
            "LICENSE",
            1_061L,
            "d02ed0afc408cb4ed5eb34b233096ad8c3db8b6751cd9385276a6725dbf92925"
        ),
        gigaV4File(
            "LICENSE-APACHE-2.0",
            11_358L,
            "cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30"
        ),
        gigaV4File(
            "NOTICE",
            208L,
            "3c8a3a8477970a4306a9af076b85fd2f0064a3bb59466a3fe7f9114fbba7f412"
        )
    )

    private fun onnxModel(
        id: String, url: String, bytes: Long, sha256: String, format: String, languages: List<String> = emptyList()
    ) = TinyAsrModelSpec(
        id = id, fileName = "$id.onnx", languages = languages, sizeMb = bytes / 1_048_576f,
        bundledInApk = false, formatVersion = format, versionTag = "1",
        onnxDownload = OnnxDownload(url, bytes, sha256)
    )

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

    val DOWNLOAD_MODELS = MODELS + listOf(
        TinyAsrModelSpec(
            id = GIGAAM_V4,
            fileName = GIGAAM_V4,
            languages = listOf("ru", "en"),
            sizeMb = GIGAAM_V4_FILES.sumOf { it.bytes } / 1_048_576f,
            bundledInApk = false,
            formatVersion = "ONNX · INT8 · RNN-T",
            versionTag = "v4",
            bundleFiles = GIGAAM_V4_FILES
        ),
        onnxModel(
            GIGAAM,
            "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-punct-giga-am-v3-russian-2025-12-16/resolve/360529f65d9687a7b1c2177130262819e51557da/model.int8.onnx",
            224_893_661L, "d5fea8df94263c285e54b21e5774b707c707192d3bdbeffd7b1eb07fb6743b35",
            "ONNX · INT8 · CTC", listOf("ru")
        ),
        onnxModel(
            MOSSFORMER,
            "https://huggingface.co/ipsilondev/MossFormer2-SS-16K-ONNX/resolve/abd8b1268dfded6b0e972ddae4c2dd6a3faf4f8a/model.int8.onnx",
            90_943_046L, "1776c854784a2400feb7582ac4e9f922073cd706cb4fd216c98abad937d642b1",
            "ONNX · INT8 · 16 kHz"
        ),
        onnxModel(
            FRCRN,
            "https://huggingface.co/TigreGotico/audiosronnx-frcrn/resolve/8d80ae92591dce122c8d59b02886095cd4192c29/frcrn.onnx",
            57_480_770L, "9a3582e61901724706301ed489a9fb73ac4cecb511ff40396bd48187fbb5c7f6",
            "ONNX · 16 kHz"
        ),
        onnxModel(
            DEEPFILTER2,
            "https://raw.githubusercontent.com/shimondoodkin/deepfilter-rt/f1a1b27be767a5e62ccc8625017f1737c33a3bf9/models/dfn2/combined.onnx",
            9_273_190L, "669b69ee5e26417d2a91b80586731d25aeb9d7bb73da249d4742f9eb9fd7f69d",
            "ONNX · 48 kHz"
        ),
        onnxModel(
            DEEPFILTER3,
            "https://raw.githubusercontent.com/shimondoodkin/deepfilter-rt/f1a1b27be767a5e62ccc8625017f1737c33a3bf9/models/dfn3/combined.onnx",
            8_591_923L, "cccd6bfc8b1ededfb0e759997d9a4cd06e07dd9ba539822cdb8ee637a05d77e8",
            "ONNX · 48 kHz"
        )
    )

    val PRESETS: List<LanguagePreset> = listOf(
        LanguagePreset("ru_en", "en-ru-uk", "ru,en", "ru", "RU + EN"),
        LanguagePreset("ru_only", "en-ru-uk", "ru", "ru", "RU"),
        LanguagePreset(GIGAAM_V4, GIGAAM_V4, "ru,en", "ru", "GigaAM v4 · RU+EN"),
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

    val FILE_PRESETS = PRESETS.take(3) +
        LanguagePreset(GIGAAM, GIGAAM, "ru", "ru", "GigaAM · RU") + PRESETS.drop(3)

    fun findModel(id: String): TinyAsrModelSpec =
        DOWNLOAD_MODELS.firstOrNull { it.id == id } ?: MODELS.first()

    fun findPreset(id: String): LanguagePreset =
        FILE_PRESETS.firstOrNull { it.id == id } ?: PRESETS.first()

    fun languageNativeName(code: String): String = when (code.lowercase()) {
        "ru" -> "Русский"
        "en" -> "English"
        "ru,en", "en,ru" -> "Русский + English"
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
