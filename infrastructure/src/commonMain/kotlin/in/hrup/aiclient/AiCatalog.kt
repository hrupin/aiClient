package `in`.hrup.aiclient

import `in`.hrup.aiclient.infrastructure.catalog.providerModelCatalog
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AiCatalogModel(
    val modelId: String,
    val provider: AiProviderId,
) {
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault
    val key: String = "${provider.name.lowercase()}:$modelId"
}

/** Bundled model catalog available without credentials or network access. */
object AiCatalog {
    val modelsByProvider: Map<AiProviderId, List<AiCatalogModel>> =
        AiProviderId.entries.associateWith { provider ->
            providerModelCatalog[provider.name.lowercase()].orEmpty().map { model ->
                AiCatalogModel(modelId = model.id, provider = provider)
            }
        }

    /** Looks up a `provider:modelId` key. Provider names are case-insensitive and model IDs are exact. */
    fun getModel(key: String): AiCatalogModel? {
        val providerName = key.substringBefore(':', missingDelimiterValue = "")
        val provider = AiProviderId.entries.firstOrNull { it.name.equals(providerName, ignoreCase = true) }
            ?: return null
        val modelId = key.substringAfter(':')
        return modelsByProvider[provider]?.firstOrNull { it.modelId == modelId }
    }
}

fun Map<AiProviderId, List<AiCatalogModel>>.toJson(): String = Json.encodeToString(this)

fun List<AiCatalogModel>.toJson(): String = Json.encodeToString(this)

/** Encodes a missing catalog entry as the JSON literal `null`. */
fun AiCatalogModel?.toJson(): String = Json.encodeToString(this)
