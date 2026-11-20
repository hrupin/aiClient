package `in`.hrup.aiclient.infrastructure.providers

import java.io.File

internal fun readLiveEnv(): Map<String, String> =
    File(".env").takeIf { it.isFile }?.readLines().orEmpty()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") && '=' in it }
        .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }

internal fun liveSetting(vararg values: String?): String? =
    values.firstNotNullOfOrNull { it?.trim()?.removeSurrounding("\"")?.removeSurrounding("'")?.takeIf(String::isNotBlank) }
