package `in`.hrup.aiclient.infrastructure.providers.openai

import `in`.hrup.aiclient.infrastructure.providers.liveSetting
import `in`.hrup.aiclient.infrastructure.providers.readLiveEnv

internal class OpenAiLiveSettings(val apiKey: String, val model: String) {
    operator fun component1(): String = apiKey
    operator fun component2(): String = model
}

internal fun loadOpenAiLiveSettings(): OpenAiLiveSettings {
    val env = readLiveEnv()
    val providerId = (liveSetting(System.getProperty("aiProvider"), System.getenv("AI_PROVIDER"), env["AI_PROVIDER"])
        ?: "openai").lowercase()
    require(providerId == "openai") { "Unsupported provider '$providerId'. Available provider: openai" }
    val model = liveSetting(
        System.getProperty("aiModel"),
        System.getenv("AI_MODEL"),
        System.getenv("OPENAI_MODEL"),
        env["AI_MODEL"],
    ) ?: "gpt-6-luna"
    val apiKey = checkNotNull(liveSetting(System.getenv("OPENAI_API_KEY"), env["OPENAI_API_KEY"])) {
        "Set OPENAI_API_KEY in .env or in the environment before running a live OpenAI test"
    }
    return OpenAiLiveSettings(apiKey, model)
}
