package com.ishaan.essentialvoice.engine

internal object ParakeetLib {

    val isSupported: Boolean by lazy {
        runCatching {
            val flags = java.io.File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("Features") }
                ?.substringAfter(':')
                ?.split(' ')
                ?.map { it.trim() }
                ?: return@runCatching false
            "asimd" in flags
        }.getOrDefault(false)
    }

    private val loaded: Boolean by lazy {
        if (!isSupported) false
        else runCatching { System.loadLibrary("essentialparakeet") }.isSuccess
    }

    fun ensureLoaded(): Boolean = loaded

    @JvmStatic external fun nativeInit(
        encoderPath: String,
        decoderPath: String,
        joinerPath: String,
        tokensPath: String,
        numThreads: Int,
    ): Long

    @JvmStatic external fun nativeFree(ptr: Long)
    @JvmStatic external fun nativeAbort(on: Boolean)
    @JvmStatic external fun nativeTranscribe(
        ptr: Long,
        audio: FloatArray,
        sampleRate: Int,
    ): String
}
