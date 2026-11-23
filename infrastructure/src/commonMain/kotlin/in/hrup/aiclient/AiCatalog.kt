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

    private val modelsByKey = modelsByProvider.values.flatten().associateBy(AiCatalogModel::key)

    /** Looks up an exact catalog key in `provider:modelId` format with a lowercase provider name. */
    fun getModel(key: String): AiCatalogModel? = modelsByKey[key]

    /** Returns the provider's built-in default model. */
    fun getDefaultModel(provider: AiProviderId): AiCatalogModel =
        modelsByProvider.getValue(provider).first { it.modelId == provider.defaultModelId }
}

fun Map<AiProviderId, List<AiCatalogModel>>.toJson(): String = Json.encodeToString(this)

fun List<AiCatalogModel>.toJson(): String = Json.encodeToString(this)

/** Encodes a missing catalog entry as the JSON literal `null`. */
fun AiCatalogModel?.toJson(): String = Json.encodeToString(this)
