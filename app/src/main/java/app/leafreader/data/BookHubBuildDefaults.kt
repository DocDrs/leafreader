package app.leafreader.data

/** Optional compile-time hint only. Never changes saved configuration, tokens or storage identity. */
object BookHubBuildDefaults {
    fun origin(): String = validated(CompiledBookHubDefault.ORIGIN)
    fun validated(value: String): String = runCatching { ServiceOrigin(value) }.getOrNull()
        ?.takeIf { it.uri.scheme == "https" }?.value.orEmpty()
    fun initial(savedOrigin: String, compiledOrigin: String = origin()): String =
        savedOrigin.takeIf { it.isNotBlank() } ?: validated(compiledOrigin)
}
