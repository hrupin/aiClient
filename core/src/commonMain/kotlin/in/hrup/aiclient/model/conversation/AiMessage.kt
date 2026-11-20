package `in`.hrup.aiclient.model.conversation

sealed interface AiConversationItem

enum class AiRole {
    SYSTEM,
    DEVELOPER,
    USER,
    ASSISTANT,
    TOOL,
}

data class AiMessage(
    val role: AiRole,
    val content: List<AiContentPart>,
) : AiConversationItem {
    init {
        require(role != AiRole.TOOL) { "Use AiToolResult for messages linked to a tool call" }
    }
}

sealed interface AiContentPart {
    data class Text(val text: String) : AiContentPart

    data class Image(val source: AiImageSource) : AiContentPart
}

sealed interface AiImageSource {
    data class Url(val url: String) : AiImageSource

    data class Base64(
        val data: String,
        val mediaType: String,
    ) : AiImageSource
}
