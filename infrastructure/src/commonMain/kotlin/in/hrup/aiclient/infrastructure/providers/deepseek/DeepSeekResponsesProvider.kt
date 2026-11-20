package `in`.hrup.aiclient.infrastructure.providers.deepseek

import `in`.hrup.aiclient.infrastructure.http.HttpTransport
import `in`.hrup.aiclient.infrastructure.providers.text.ProviderResponseException
import `in`.hrup.aiclient.infrastructure.providers.text.TextProviderHttp
import `in`.hrup.aiclient.infrastructure.providers.text.streamResponses
import `in`.hrup.aiclient.infrastructure.structured.JsonStructuredOutput
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.model.generation.AiUsage
import `in`.hrup.aiclient.model.generation.StructuredOutputException
import `in`.hrup.aiclient.provider.StreamingAiProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.serialization.SerializationException

class DeepSeekResponsesException(
    message: String,
    val httpStatus: Int,
    val requestId: String?,
    val code: String? = null,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

class DeepSeekResponsesProvider(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val endpoint: String = "https://api.deepseek.com/responses",
) : StreamingAiProvider {
    private val streamingHttp = TextProviderHttp("DeepSeek", transport, endpoint, apiKey)
    private val json get() = streamingHttp.json
    private val metadataTypes = setOf("reasoning")

    override suspend fun generate(request: AiRequest): AiResponse {
        val structured = request.responseFormat?.let(::JsonStructuredOutput)
        val payload = request.toPayload(structured)
        val response = transport.execute(endpoint) {
            method = HttpMethod.Post
            bearerAuth(apiKey)
            contentType(ContentType.Application.Json)
            accept(ContentType.Application.Json)
            setBody(json.encodeToString(payload))
        }
        val status = response.head.status.value
        val requestId = response.head.headers["x-request-id"] ?: response.head.headers["x-ds-request-id"]
        val body = response.body.decodeToString()

        fun fail(message: String, code: String? = null, cause: Throwable? = null): Nothing =
            throw DeepSeekResponsesException(message, status, requestId, code, cause)

        if (status !in 200..299) {
            val error = try {
                json.decodeFromString<DeepSeekErrorEnvelope>(body).error
            } catch (_: SerializationException) {
                null
            }
            fail(error?.message ?: "DeepSeek returned HTTP $status", error?.code)
        }
        val decoded = try {
            json.decodeFromString<DeepSeekResponse>(body)
        } catch (cause: SerializationException) {
            fail("Invalid DeepSeek Responses payload", cause = cause)
        }
        if (decoded.status != "completed" || decoded.error != null) {
            fail(
                decoded.error?.message ?: "DeepSeek response status: ${decoded.status}",
                decoded.error?.code ?: decoded.incompleteDetails?.reason,
            )
        }

        val messages = decoded.output.mapNotNull { item ->
            when (item.type) {
                "reasoning" -> null
                "message" -> AiMessage(
                    AiRole.ASSISTANT,
                    (item.content ?: fail("DeepSeek response message is missing content")).map { part ->
                        when (part.type) {
                            "output_text" -> AiContentPart.Text(part.text ?: fail("DeepSeek response is missing output text"))
                            "refusal" -> fail(part.refusal ?: "DeepSeek refused the request", "refusal")
                            else -> fail("Unsupported DeepSeek response content type: ${part.type}")
                        }
                    },
                )
                else -> fail("Unsupported DeepSeek response item type: ${item.type}")
            }
        }
        if (messages.isEmpty()) fail("DeepSeek response is missing an output message")

        val output = structured?.let { validator ->
            val message = messages.singleOrNull()
                ?: throw StructuredOutputException("Expected one DeepSeek structured output message")
            validator.validate(message.content.joinToString("") { (it as AiContentPart.Text).text })
        }
        return AiResponse(
            output = messages,
            usage = decoded.usage?.let { AiUsage(it.inputTokens, it.outputTokens, it.totalTokens) },
            structuredOutput = output,
        )
    }

    override fun stream(request: AiRequest): Flow<AiStreamEvent> = flow {
        val structured = request.responseFormat?.let(::JsonStructuredOutput)
        val payload = request.toPayload(structured).copy(stream = true)
        emitAll(streamingHttp.streamResponses(json.encodeToString(payload), structured, metadataTypes).catch { cause ->
            if (cause !is ProviderResponseException) throw cause
            throw DeepSeekResponsesException(
                cause.message ?: "DeepSeek streaming request failed",
                cause.httpStatus,
                cause.requestId,
                cause.code,
                cause,
            )
        })
    }

    private fun AiRequest.toPayload(structured: JsonStructuredOutput?): DeepSeekRequest {
        val model = model.id
        require(model == "deepseek-flash" || model == "deepseek-v4-pro") { "Unsupported DeepSeek model: $model" }
        require(tools.isEmpty()) { "DeepSeek tool calling is unavailable in this adapter" }
        require(input.isNotEmpty()) { "DeepSeek input must contain a message" }

        return DeepSeekRequest(
            model = model,
            input = input.map { item ->
                require(item is AiMessage) { "DeepSeek input must contain only messages" }
                require(item.role != AiRole.DEVELOPER || model == "deepseek-flash") {
                    "DeepSeek V4 Pro treats developer messages as user messages. Use an explicit system message."
                }
                require(item.content.isNotEmpty()) { "DeepSeek messages must contain text" }
                val content = item.content.joinToString("") { part ->
                    require(part is AiContentPart.Text) { "This DeepSeek adapter accepts text content only" }
                    part.text
                }
                DeepSeekMessage(item.role.name.lowercase(), content)
            },
            text = structured?.let {
                DeepSeekText(DeepSeekFormat("json_schema", requireNotNull(responseFormat).name, it.definition))
            },
        )
    }
}
