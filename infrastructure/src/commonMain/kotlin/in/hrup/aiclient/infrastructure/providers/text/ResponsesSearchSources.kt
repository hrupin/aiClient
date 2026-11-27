package `in`.hrup.aiclient.infrastructure.providers.text

import `in`.hrup.aiclient.model.generation.AiSearchSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

internal fun JsonElement?.toSearchSources(): List<AiSearchSource> =
    (this as? JsonArray).orEmpty().mapNotNull { entry ->
        val item = entry as? JsonObject ?: return@mapNotNull null
        val id = (item["id"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
        val url = item.text("url")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        AiSearchSource(id, url, item.text("title"), item.text("snippet"))
    }
