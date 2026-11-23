package `in`.hrup.aiclient

import `in`.hrup.aiclient.infrastructure.catalog.providerModelCatalog

data class AiCatalogModel(
    val modelId: String,
    val provider: AiProviderId,
)

/** Bundled model catalog available without credentials or network access. */
object AiCatalog {
    val modelsByProvider: Map<AiProviderId, List<AiCatalogModel>> =
        AiProviderId.entries.associateWith { provider ->
            providerModelCatalog[provider.name.lowercase()].orEmpty().map { model ->
                AiCatalogModel(modelId = model.id, provider = provider)
            }
        }
}
