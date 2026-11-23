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
    fun resolvesAKeyToTheCatalogEntry() {
        val model = AiCatalog.modelsByProvider.getValue(AiProviderId.PERPLEXITY).first()
        assertSame(model, AiCatalog.getModel("PERPLEXITY:${model.modelId}"))
        assertEquals("perplexity:${model.modelId}", model.key)
        assertSame(model, AiCatalog.getModel(model.key))
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
        for (key in listOf("OPENAI", ":gpt-6-luna", "OPENAI:", "UNKNOWN:gpt-6-luna", "BFL:gpt-6-luna")) {
            assertNull(AiCatalog.getModel(key), key)
        }
    }
}
