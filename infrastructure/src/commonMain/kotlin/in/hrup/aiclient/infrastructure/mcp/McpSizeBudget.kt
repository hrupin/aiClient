package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiConversationItem
import `in`.hrup.aiclient.model.conversation.AiImageSource
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiToolCall
import `in`.hrup.aiclient.model.conversation.AiToolResult
import `in`.hrup.aiclient.model.value.AiValue

/** Bounds the local logical payload. Provider-specific wire framing can add overhead. */
internal class McpSizeBudget(private var remaining: Int, private val label: String) {
    fun item(item: AiConversationItem) {
        spend(1)
        when (item) {
            is AiMessage -> parts(item.content)
            is AiToolCall -> {
                text(item.id)
                text(item.name)
                value(item.arguments)
            }
            is AiToolResult -> {
                text(item.callId)
                parts(item.content)
                item.structuredContent?.let { value(it) }
            }
        }
    }

    private fun parts(parts: List<AiContentPart>) {
        for (part in parts) {
            spend(1)
            when (part) {
                is AiContentPart.Text -> text(part.text)
                is AiContentPart.Image -> when (val source = part.source) {
                    is AiImageSource.Url -> text(source.url)
                    is AiImageSource.Base64 -> {
                        text(source.data)
                        text(source.mediaType)
                    }
                }
            }
        }
    }

    fun value(value: AiValue, depth: Int = 0) {
        if (depth > 64) throw McpException("$label exceeds the nesting limit")
        spend(1)
        when (value) {
            is AiValue.Array -> value.values.forEach { value(it, depth + 1) }
            is AiValue.Object -> value.fields.forEach { (key, child) ->
                text(key)
                value(child, depth + 1)
            }
            is AiValue.Text -> text(value.value)
            is AiValue.Number -> text(value.literal)
            is AiValue.Boolean, AiValue.Null -> Unit
        }
    }

    fun text(text: String) {
        if (text.length > remaining) tooLarge()
        spend(text.encodeToByteArray().size)
    }

    fun spend(size: Int) {
        if (size > remaining) tooLarge()
        remaining -= size
    }

    private fun tooLarge(): Nothing = throw McpException("$label exceeds the size limit")
}
