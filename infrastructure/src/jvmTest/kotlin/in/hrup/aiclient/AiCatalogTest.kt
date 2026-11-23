package `in`.hrup.aiclient

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class AiCatalogTest {
    @Test
    fun returnsTheBuiltInDefaultModelForEachProvider() {
        for (provider in AiProviderId.entries) {
            val model = AiCatalog.getDefaultModel(provider)
            assertEquals(provider, model.provider)
            assertEquals(provider.defaultModelId, model.modelId)
            assertSame(model, AiCatalog.getModel(model.key))
        }
    }

    @Test
    fun resolvesAKeyToTheCatalogEntry() {
        val model = AiCatalog.modelsByProvider.getValue(AiProviderId.PERPLEXITY).first()
        assertEquals("perplexity:${model.modelId}", model.key)
        assertSame(model, AiCatalog.getModel(model.key))
    }

    @Test
    fun requiresAnExactKey() {
        val model = AiCatalog.modelsByProvider.getValue(AiProviderId.PERPLEXITY).first()
        assertNull(AiCatalog.getModel("PERPLEXITY:${model.modelId}"))
        assertNull(AiCatalog.getModel("perplexity:${model.modelId.uppercase()}"))
    }

    @Test
    fun includesKeysInAllCatalogJsonFormats() {
        val provider = AiProviderId.PERPLEXITY
        val models = AiCatalog.modelsByProvider.getValue(provider)
        val list = Json.parseToJsonElement(models.toJson()).jsonArray
        assertEquals(
            models.map { "perplexity:${it.modelId}" },
            list.map { it.jsonObject.getValue("key").jsonPrimitive.content },
        )
        val model = AiCatalog.getModel(models.first().key)
        assertEquals(list.first(), Json.parseToJsonElement(model.toJson()))
        val catalog = Json.parseToJsonElement(AiCatalog.modelsByProvider.toJson()).jsonObject
        assertEquals(list, catalog.getValue(provider.name))
    }

    @Test
    fun returnsNullForMalformedKeysAndUnknownEntries() {
        for (key in listOf("openai", ":gpt-6-luna", "openai:", "unknown:gpt-6-luna", "bfl:gpt-6-luna")) {
            assertNull(AiCatalog.getModel(key), key)
        }
    }
}
