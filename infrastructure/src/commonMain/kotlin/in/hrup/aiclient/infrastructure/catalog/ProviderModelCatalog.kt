package `in`.hrup.aiclient.infrastructure.catalog

import `in`.hrup.aiclient.model.generation.AiModel

/**
 * Selected IDs from provider APIs and endpoint names for Black Forest Labs.
 * Catalog entries describe the external APIs. Availability can depend on the account and region.
 */
val providerModelCatalog: Map<String, List<AiModel>> = mapOf(
    "openai" to listOf(
        AiModel("gpt-6.2-astra"),
        AiModel("gpt-6.2-sol"),
        AiModel("gpt-6.1-terra"),
        AiModel("gpt-6-luna"),
        AiModel("gpt-5.4"),
        AiModel("gpt-5.4-mini"),
    ),
    "deepseek" to listOf(
        AiModel("deepseek-flash"),
        AiModel("deepseek-v4-pro"),
    ),
    "xai" to listOf(
        AiModel("grok-4.7"),
        AiModel("grok-4.6"),
    ),
    "zai" to listOf(
        AiModel("glm-5.3"),
        AiModel("glm-5.3-flash"),
        AiModel("glm-5.3-flashx"),
        AiModel("glm-5.2"),
        AiModel("glm-5.1"),
        AiModel("glm-5"),
        AiModel("glm-5v-turbo"),
        AiModel("glm-4.7"),
        AiModel("glm-4.6"),
    ),
    "kimi" to listOf(
        AiModel("kimi-k3.1-pro"),
        AiModel("kimi-k3.1-pro-highspeed"),
        AiModel("kimi-k3"),
    ),
    "perplexity" to listOf(
        AiModel("perplexity/sonar"),
    ),
    "bfl" to listOf(
        AiModel("flux-3-image"),
        AiModel("flux-3-image-klein"),
        AiModel("flux-2-max"),
        AiModel("flux-2-pro"),
        AiModel("flux-2-flex"),
        AiModel("flux-2-klein-9b"),
        AiModel("flux-2-pro-preview"),
        AiModel("flux-2-klein-9b-preview"),
    ),
)
