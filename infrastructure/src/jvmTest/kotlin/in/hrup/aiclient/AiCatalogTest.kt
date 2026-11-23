package `in`.hrup.aiclient

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame

class AiCatalogTest {
    @Test
    fun resolvesAKeyToTheCatalogEntry() {
        val model = AiCatalog.modelsByProvider.getValue(AiProviderId.PERPLEXITY).first()
        assertSame(model, AiCatalog.getModel("PERPLEXITY:${model.modelId}"))
        assertSame(model, AiCatalog.getModel("perplexity:${model.modelId}"))
    }

    @Test
    fun returnsNullForMalformedKeysAndUnknownEntries() {
        for (key in listOf("OPENAI", ":gpt-6-luna", "OPENAI:", "UNKNOWN:gpt-6-luna", "BFL:gpt-6-luna")) {
            assertNull(AiCatalog.getModel(key), key)
        }
    }
}
