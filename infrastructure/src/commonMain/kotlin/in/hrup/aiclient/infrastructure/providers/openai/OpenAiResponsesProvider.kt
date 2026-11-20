package `in`.hrup.aiclient.infrastructure.providers.openai

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.http.sse.SseResponseException
import `in`.hrup.aiclient.infrastructure.http.sse.sse
import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.infrastructure.providers.text.responsesContent
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.model.generation.AiUsage
import `in`.hrup.aiclient.model.generation.StructuredOutputException
import `in`.hrup.aiclient.provider.StreamingAiProvider
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class OpenAiResponsesException(
    message: String,
    val httpStatus: Int,
    val requestId: String?,
    val code: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class OpenAiResponsesProvider(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val endpoint: String = "https://api.openai.com/v1/responses",
) : StreamingAiProvider {
    private val json = Json { ignoreUnknownKeys = true }

    init {
        require(apiKey.isNotBlank()) { "OpenAI API key must not be blank" }
    }

    override suspend fun generate(request: AiRequest): AiResponse {
        val structured = request.responseFormat?.let(::JsonStructuredOutput)
        val payload = request.toResponsesRequest(structured)
        val response = transport.execute(endpoint) {
            configureRequest(payload)
            accept(ContentType.Application.Json)
        }
        val status = response.head.status.value
        val requestId = response.head.headers["x-request-id"]
        val body = response.body.decodeToString()
        if (status !in 200..299) {
            val error = try {
                json.decodeFromString<ResponsesErrorEnvelope>(body).error
            } catch (_: SerializationException) {
                null
            }
            throw OpenAiResponsesException(error?.message ?: "OpenAI returned HTTP $status", status, requestId, error?.code)
        }

        val decoded = try {
            json.decodeFromString<ResponsesResponse>(body)
        } catch (cause: SerializationException) {
            throw OpenAiResponsesException("Invalid OpenAI Responses payload", status, requestId, cause = cause)
        }
        return decoded.toAiResponse(status, requestId).withStructuredOutput(structured)
    }

    override fun stream(request: AiRequest): Flow<AiStreamEvent> = flow {
        val structured = request.responseFormat?.let(::JsonStructuredOutput)
        val payload = request.toResponsesRequest(structured).copy(stream = true)
        val indices = mutableMapOf<Int, Int>()
        var completed = false
        try {
            transport.sse(endpoint) { configureRequest(payload) }
                .transformWhile { frame ->
                    val event = try {
                        json.decodeFromString<ResponsesStreamEvent>(frame.data)
                    } catch (cause: SerializationException) {
                        throw OpenAiResponsesException("Invalid OpenAI streaming event", 200, null, cause = cause)
                    }
                    fun missing(field: String): Nothing =
                        throw OpenAiResponsesException("OpenAI streaming event ${event.type} is missing $field", 200, null)
                    fun outputIndex(): Int = event.outputIndex ?: missing("output_index")
                    // Reasoning items are omitted from the domain response, so compact the provider's indices.
                    fun messageIndex(): Int = indices.getOrPut(outputIndex()) { indices.size }

                    when (event.type) {
                        "response.output_item.added" -> {
                            if ((event.item ?: missing("item")).type == "message") messageIndex()
                        }
                        "response.output_text.delta" -> emit(AiStreamEvent.TextDelta(
                            outputIndex = messageIndex(),
                            contentIndex = event.contentIndex ?: missing("content_index"),
                            text = event.delta ?: missing("delta"),
                        ))
                        "response.output_item.done" -> {
                            val item = (event.item ?: missing("item")).toAiMessage(200, null)
                            if (item != null) emit(AiStreamEvent.OutputItemCompleted(messageIndex(), item))
                        }
                        "response.completed" -> {
                            val response = (event.response ?: missing("response")).toAiResponse(200, null)
                                .withStructuredOutput(structured)
                            completed = true
                            emit(AiStreamEvent.Completed(response))
                        }
                        "response.failed", "response.incomplete" -> {
                            val response = event.response ?: missing("response")
                            throw response.toException(200, null)
                        }
                        "error" -> throw OpenAiResponsesException(
                            event.message ?: "OpenAI streaming error", 200, null, event.code,
                        )
                    }
                    !completed
                }.collect { emit(it) }
        } catch (cause: SseResponseException) {
            throw OpenAiResponsesException(
                cause.message ?: "Invalid OpenAI SSE response",
                cause.head.status.value,
                cause.head.headers["x-request-id"],
                cause = cause,
            )
        }
        if (!completed) {
            throw OpenAiResponsesException("OpenAI stream ended before response.completed", 200, null)
        }
    }

    private fun HttpRequestBuilder.configureRequest(payload: ResponsesRequest) {
        method = HttpMethod.Post
        bearerAuth(apiKey)
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(payload))
    }

    private fun ResponsesResponse.toAiResponse(status: Int, requestId: String?): AiResponse {
        if (this.status != "completed" || error != null) throw toException(status, requestId)
        return AiResponse(
            output.mapNotNull { it.toAiMessage(status, requestId) },
            usage?.let { AiUsage(it.inputTokens, it.outputTokens, it.totalTokens) },
        )
    }

    private fun ResponsesResponse.toException(status: Int, requestId: String?) = OpenAiResponsesException(
        error?.message ?: "OpenAI response status: ${this.status}",
        status,
        requestId,
        error?.code ?: incompleteDetails?.reason,
    )

    private fun ResponsesOutputItem.toAiMessage(status: Int, requestId: String?): AiMessage? {
        fun failure(message: String, code: String? = null): Nothing =
            throw OpenAiResponsesException(message, status, requestId, code)

        return when (type) {
            "reasoning" -> null
            "message" -> AiMessage(
                AiRole.ASSISTANT,
                (content ?: failure("OpenAI response message is missing content")).map { part ->
                    when (part.type) {
                        "output_text" -> AiContentPart.Text(part.text ?: failure("OpenAI response is missing output text"))
                        "refusal" -> failure(part.refusal ?: "OpenAI refused the request", "refusal")
                        else -> failure("Unsupported OpenAI response content type: ${part.type}")
                    }
                },
            )
            else -> failure("Unsupported OpenAI response item type: $type")
        }
    }

    private fun AiResponse.withStructuredOutput(structured: JsonStructuredOutput?): AiResponse {
        if (structured == null) return this
        val message = output.singleOrNull() as? AiMessage
            ?: throw StructuredOutputException("Expected one structured output message")
        val text = message.content.joinToString("") { (it as AiContentPart.Text).text }
        return copy(structuredOutput = structured.validate(text))
    }

    private fun AiRequest.toResponsesRequest(structured: JsonStructuredOutput?): ResponsesRequest {
        require(tools.isEmpty()) { "This OpenAI Responses adapter does not support tool declarations" }
        require(input.isNotEmpty()) { "OpenAI Responses input must contain a message" }
        return ResponsesRequest(model.id, input.map { item ->
            require(item is AiMessage) { "OpenAI Responses input must contain only messages" }
            require(item.content.isNotEmpty()) { "OpenAI Responses messages must contain at least one content part" }
            ResponsesInputMessage(item.role.name.lowercase(), responsesContent(item.content))
        }, text = structured?.let {
            ResponsesText(ResponsesJsonFormat("json_schema", requireNotNull(responseFormat).name, true, it.definition))
        })
    }
}
