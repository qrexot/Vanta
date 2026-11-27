package com.stenograf.app.asr

object TinyAsrNative {
    init {
        System.loadLibrary("tinyasr_jni")
    }

    external fun nativeVersion(): Int
    external fun nativePinToBigCores(): Int
    external fun nativeLoadModels(
        asrPath: String,
        vadPath: String,
        requestedThreads: Int,
        errOut: ByteArray
    ): Long

    external fun nativeFreeModels(handle: Long)
    external fun nativeChunkMs(handle: Long): Int
    external fun nativeGetLangs(handle: Long): Array<String>

    external fun nativeSessionCreate(
        modelHandle: Long,
        langs: String,
        startLang: String?,
        beam: Int
    ): Long

    external fun nativeSessionPushPcm16(
        sessionHandle: Long,
        pcm: ShortArray,
        offset: Int,
        length: Int
    ): String?

    external fun nativeSessionPushFloat(
        sessionHandle: Long,
        pcm: FloatArray,
        length: Int
    ): String?

    external fun nativeSessionFinish(sessionHandle: Long): String?
    external fun nativeSessionLang(sessionHandle: Long): String?
    external fun nativeSessionFree(sessionHandle: Long)

    external fun nativeFileCreateFromPcm16(
        modelHandle: Long,
        pcmPath: String,
        byteOffset: Long,
        sampleCount: Long,
        langs: String,
        beam: Int
    ): Long

    external fun nativeFileCreateFromFloat(
        modelHandle: Long,
        pcm: FloatArray,
        length: Int,
        langs: String,
        beam: Int
    ): Long

    external fun nativeFileNext(fileHandle: Long): String?
    external fun nativeFileLang(fileHandle: Long): String?
    external fun nativeFileProgress(fileHandle: Long): Float
    external fun nativeFileFree(fileHandle: Long)
}
