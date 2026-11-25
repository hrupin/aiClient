package `in`.hrup.aiclient.infrastructure.mcp

import `in`.hrup.aiclient.infrastructure.structured.parseJsonResponseFormat
import `in`.hrup.aiclient.model.conversation.AiContentPart
import `in`.hrup.aiclient.model.conversation.AiMessage
import `in`.hrup.aiclient.model.conversation.AiRole
import `in`.hrup.aiclient.model.conversation.AiToolCall
import `in`.hrup.aiclient.model.conversation.AiToolResult
import `in`.hrup.aiclient.model.generation.AiModel
import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import `in`.hrup.aiclient.model.generation.AiUsage
import `in`.hrup.aiclient.model.generation.StructuredOutputException
import `in`.hrup.aiclient.model.tool.AiTool
import `in`.hrup.aiclient.model.value.AiValue
import `in`.hrup.aiclient.provider.AiToolCapableProvider
import `in`.hrup.aiclient.provider.AiToolCapabilities
import `in`.hrup.aiclient.provider.StreamingAiProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class McpToolLoopTest {
    private val original = AiRequest(AiModel("fake"), listOf(message(AiRole.USER, "Sum the formula.")))
    private val native = AiToolCapabilities(structuredOutput = true, nativeToolCalling = true, streamingToolCalling = true)
    private val jsonOnly = AiToolCapabilities(structuredOutput = true)
    private val options = McpToolOptions()
    private val empty = AiValue.Object(emptyMap())
    private val one = AiValue.Object(mapOf("value" to AiValue.Number("41")))
    private val finalFormat = parseJsonResponseFormat("sum", """{"type":"object"}""")

    @Test
    fun nativeModelCanFinishWithoutChoosingTools() = runBlocking {
        val server = FakeServer("lookup", "unused")
        val provider = Script(native, { request ->
            assertEquals(listOf("lookup", "unused"), request.tools.map { it.name })
            answer()
        })
        assertEquals("Done", provider.runMcpConversation(original, server.routes(), options).content)
        assertEquals(emptyList(), server.invoked)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun nativeCallsAreLinkedAndIndependentToolsCanRunInOneRoundAcrossServers() = runBlocking {
        val first = FakeServer("lookup")
        val second = FakeServer("lookup")
        lateinit var advertised: List<String>
        val provider = Script(native, { request ->
            advertised = request.tools.map { it.name }
            assertEquals(2, advertised.distinct().size)
            batch(call("first", advertised[0]), call("second", advertised[1]))
        }, { request ->
            assertEquals(advertised, request.tools.map { it.name })
            assertEquals(listOf("first", "second"), request.input.filterIsInstance<AiToolCall>().map { it.id })
            assertEquals(listOf("first", "second"), request.results().map { it.callId })
            answer()
        })
        provider.runMcpConversation(original, first.routes() + second.routes(), options)
        assertEquals(listOf("lookup" to empty), first.invoked)
        assertEquals(listOf("lookup" to empty), second.invoked)
    }

    @Test
    fun iterativeJsonSupportsDependentCallsAndRepeatCallsAndKeepsTheFinalFormat() = runBlocking {
        val server = FakeServer("test_get_formula", "test_summ_formula")
        server.result = { _, _ -> one }
        val provider = Script(jsonOnly, { request ->
            assertEquals("mcp_tool_selection", request.responseFormat?.name)
            assertTrue("test_summ_formula" in request.input.toString())
            plan("test_get_formula" to "{}")
        }, { request ->
            assertTrue("test_get_formula" in request.input.toString())
            assertTrue("41" in request.input.toString())
            plan("test_summ_formula" to """{"value":41}""")
        }, { request ->
            assertTrue("test_summ_formula" in request.input.toString())
            assertEquals(2, request.resultMessages().size)
            plan("test_get_formula" to "{}")
        }, { request ->
            assertEquals(3, request.resultMessages().size)
            plan()
        }, { request ->
            assertSame(finalFormat, request.responseFormat)
            assertEquals(3, request.resultMessages().size)
            assertFalse("Available tools" in request.input.toString())
            answer(structured = one)
        })
        val result = provider.runMcpConversation(original.copy(responseFormat = finalFormat), server.routes(), options)
        assertEquals(listOf("test_get_formula" to empty, "test_summ_formula" to one, "test_get_formula" to empty), server.invoked)
        assertEquals(one, result.structuredOutput)
        assertEquals(AiUsage(7, 11, 18), result.usage)
    }

    @Test
    fun nativeStreamingHidesIntermediateDeltasAndUsesSeparateFinalFormatWhenRequired() = runBlocking {
        val server = FakeServer("lookup")
        val provider = Script(native, { request ->
            assertEquals(null, request.responseFormat)
            AiResponse(listOf(message(AiRole.ASSISTANT, "Internal draft"), call("lookup-1", "lookup")), AiUsage(2, 3))
        }, { request ->
            assertEquals(1, request.results().size)
            assertEquals(null, request.responseFormat)
            answer("Unformatted intermediate")
        }, { request ->
            assertEquals(emptyList(), request.tools)
            assertEquals(1, request.results().size)
            assertSame(finalFormat, request.responseFormat)
            answer("""{"value":41}""", structured = one)
        })
        val events = provider.streamMcpConversation(original.copy(responseFormat = finalFormat), options, server::routes).toList()
        assertEquals(listOf("""{"value":41}"""), events.filterIsInstance<AiStreamEvent.TextDelta>().map { it.text })
        assertEquals(1, events.filterIsInstance<AiStreamEvent.OutputItemCompleted>().size)
        val completed = events.filterIsInstance<AiStreamEvent.Completed>().single()
        assertSame(events.last(), completed)
        assertEquals(AiUsage(8, 9, 17), completed.response.usage)
        assertEquals(1, server.invoked.size)
    }

    @Test
    fun deniedOrInvalidBatchNeverInvokesAnEarlierValidCall() = runBlocking {
        val server = FakeServer("lookup")
        val schema = parseJsonResponseFormat("lookup", """{"type":"object","properties":{"value":{"type":"integer"}}}""").schema
        val routes = server.routes(schema)
        val valid = call("valid", "lookup")
        assertFailsWith<McpException> {
            Script(native, { batch(valid, call("bad", "private")) }).runMcpConversation(original, routes, options)
        }
        assertFailsWith<StructuredOutputException> {
            val bad = AiValue.Object(mapOf("value" to AiValue.Text("wrong")))
            Script(native, { batch(valid, call("bad", "lookup", bad)) }).runMcpConversation(original, routes, options)
        }
        assertTrue(server.invoked.isEmpty())
    }

    @Test
    fun toolPermissionIsCheckedAgainImmediatelyBeforeExecution() = runBlocking {
        val server = FakeServer("lookup", "summarize")
        server.result = { _, _ ->
            server.allowed.remove("summarize")
            one
        }
        val provider = Script(native, { batch(call("a", "lookup"), call("b", "summarize")) })
        assertFailsWith<IllegalArgumentException> {
            provider.runMcpConversation(original, server.routes(), options)
        }
        assertEquals(listOf("lookup" to empty), server.invoked)
    }

    @Test
    fun mcpErrorStopsGenerationWithoutRetryOrRawResultInError() = runBlocking {
        val server = FakeServer("update")
        server.result = { _, _ ->
            AiValue.Object(mapOf("isError" to AiValue.Boolean(true), "secret" to AiValue.Text("RAW_SECRET")))
        }
        val provider = Script(native, { batch(call("a", "update")) })
        val error = assertFailsWith<McpException> { provider.runMcpConversation(original, server.routes(), options) }
        assertFalse("RAW_SECRET" in error.toString())
        assertEquals(1, server.invoked.size)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun iterationLimitFailsWithoutCompletionAndCallLimitFailsBeforeBatch() = runBlocking {
        val server = FakeServer("lookup")
        val repeated = Script(native, { batch(call("a", "lookup")) }, { batch(call("b", "lookup")) })
        val events = mutableListOf<AiStreamEvent>()
        assertFailsWith<McpException> {
            repeated.streamMcpConversation(original, options.copy(maxToolIterations = 1), server::routes).toList(events)
        }
        assertTrue(events.isEmpty())
        assertEquals(1, server.invoked.size)

        val batch = Script(native, { batch(call("a", "lookup"), call("b", "lookup")) })
        assertFailsWith<McpException> {
            batch.runMcpConversation(original, server.routes(), options.copy(maxToolCalls = 1))
        }
        assertEquals(1, server.invoked.size)
    }

    @Test
    fun nativeMayFinishAfterLastAllowedBatch() = runBlocking {
        val server = FakeServer("lookup")
        val provider = Script(native, { batch(call("a", "lookup")) }, { answer() })
        val response = provider.runMcpConversation(original, server.routes(), options.copy(maxToolIterations = 1))
        assertEquals("Done", response.content)
        assertEquals(1, server.invoked.size)
    }

    @Test
    fun cancellationStopsTheInFlightToolAndDoesNotCompleteTheStream() = runBlocking {
        withTimeout(5_000) {
            val server = FakeServer("update")
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            server.result = { _, _ ->
                entered.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
            val provider = Script(native, { batch(call("a", "update")) })
            val events = mutableListOf<AiStreamEvent>()
            val job = launch { provider.streamMcpConversation(original, options, server::routes).toList(events) }
            entered.await()
            job.cancelAndJoin()
            cancelled.await()
            assertTrue(events.isEmpty())
            assertEquals(1, server.invoked.size)
        }
    }

    @Test
    fun timeoutAndSizeLimitsAbortWithoutRetry() = runBlocking {
        val server = FakeServer("update")
        server.result = { _, _ -> awaitCancellation() }
        val timeout = Script(native, { batch(call("a", "update")) })
        assertTrue("timed out" in assertFailsWith<McpException> {
            timeout.runMcpConversation(original, server.routes(), options.copy(requestTimeoutMillis = 100))
        }.message.orEmpty())
        assertEquals(1, server.invoked.size)

        server.result = { _, _ -> AiValue.Object(mapOf("value" to AiValue.Text("x".repeat(100)))) }
        val oversized = Script(native, { batch(call("b", "update")) })
        assertFailsWith<McpException> {
            oversized.runMcpConversation(original, server.routes(), options.copy(maxToolResultBytes = 32))
        }
        assertEquals(2, server.invoked.size)
    }

    @Test
    fun forcedUnsupportedStrategyFailsBeforeCallingTheModel() = runBlocking {
        val server = FakeServer("lookup")
        val unsupported = Script(AiToolCapabilities())
        assertTrue("no MCP execution mode" in assertFailsWith<McpException> {
            unsupported.runMcpConversation(original, server.routes(), options)
        }.message.orEmpty())
        val json = Script(jsonOnly)
        assertFailsWith<McpException> {
            json.runMcpConversation(original, server.routes(), options.copy(strategy = McpToolStrategy.NATIVE_TOOL_CALLING))
        }
        assertTrue(unsupported.requests.isEmpty() && json.requests.isEmpty() && server.invoked.isEmpty())
    }

    @Test
    fun autoUsesJsonWhenNativeCallingIsOnlyAvailableForBufferedGeneration() = runBlocking {
        val server = FakeServer("lookup")
        val provider = Script(native.copy(streamingToolCalling = false), { request ->
            assertEquals("mcp_tool_selection", request.responseFormat?.name)
            assertTrue(request.tools.isEmpty())
            plan("lookup" to "{}")
        }, { request ->
            assertEquals(1, request.resultMessages().size)
            plan()
        }, { request ->
            assertEquals(1, request.resultMessages().size)
            answer()
        })

        val events = provider.streamMcpConversation(original, options, server::routes).toList()
        assertEquals(listOf("lookup" to empty), server.invoked)
        assertEquals(listOf("Done"), events.filterIsInstance<AiStreamEvent.TextDelta>().map { it.text })
        assertEquals(AiUsage(5, 7, 12), events.filterIsInstance<AiStreamEvent.Completed>().single().response.usage)
    }

    @Test
    fun rejectsReusedCallIdBeforeExecutingAnyCallFromTheNewBatch() = runBlocking {
        val server = FakeServer("lookup", "summarize")
        val provider = Script(native, { batch(call("used-id", "lookup")) }, { request ->
            assertEquals(listOf("used-id"), request.results().map { it.callId })
            batch(call("fresh-id", "summarize"), call("used-id", "lookup"))
        })

        val error = assertFailsWith<McpException> { provider.runMcpConversation(original, server.routes(), options) }
        assertTrue("repeated MCP tool call ID" in error.message.orEmpty())
        assertEquals(listOf("lookup" to empty), server.invoked)
    }

    @Test
    fun exceedingContextBudgetStopsTheBatchBeforeTheNextToolRuns() = runBlocking {
        val server = FakeServer("lookup", "update")
        server.result = { _, _ -> AiValue.Object(mapOf("value" to AiValue.Text("x".repeat(1_024)))) }
        val provider = Script(native, { batch(call("a", "lookup"), call("b", "update")) })

        val error = assertFailsWith<McpException> {
            provider.runMcpConversation(original, server.routes(), options.copy(maxContextBytes = 1_024))
        }
        assertTrue("MCP model context exceeds the size limit" in error.message.orEmpty())
        assertEquals(listOf("lookup" to empty), server.invoked)
        assertEquals(1, provider.requests.size)
    }

    @Test
    fun collectingTheSameFlowStartsAnIndependentToolConversation() = runBlocking {
        val server = FakeServer("lookup")
        val requestCall: (AiRequest) -> AiResponse = { request ->
            assertTrue(request.results().isEmpty())
            batch(call("same-id", "lookup"))
        }
        val finish: (AiRequest) -> AiResponse = { request ->
            assertEquals(listOf("same-id"), request.results().map { it.callId })
            answer()
        }
        val provider = Script(native, requestCall, finish, requestCall, finish)
        val stream = provider.streamMcpConversation(original, options, server::routes)

        assertTrue(server.invoked.isEmpty())
        repeat(2) {
            val events = stream.toList()
            val response = events.filterIsInstance<AiStreamEvent.Completed>().single().response
            assertEquals("Done", response.content)
            assertEquals(AiUsage(4, 5, 9), response.usage)
        }
        assertEquals(listOf("lookup" to empty, "lookup" to empty), server.invoked)
    }

    private fun AiRequest.results() = input.filterIsInstance<AiToolResult>()

    private fun AiRequest.resultMessages() =
        input.filterIsInstance<AiMessage>().filter { "MCP tool result (JSON data)" in it.content.toString() }

    private fun call(id: String, name: String, arguments: AiValue.Object = empty) = AiToolCall(id, name, arguments)

    private fun batch(vararg calls: AiToolCall) = AiResponse(calls.toList(), AiUsage(1, 2))

    private fun plan(vararg calls: Pair<String, String>) = AiResponse(
        emptyList(), AiUsage(1, 2),
        AiValue.Object(mapOf("calls" to AiValue.Array(calls.map { (name, args) ->
            AiValue.Object(mapOf("name" to AiValue.Text(name), "arguments" to AiValue.Text(args)))
        }))),
    )

    private fun answer(text: String = "Done", structured: AiValue.Object? = null) =
        AiResponse(listOf(message(AiRole.ASSISTANT, text)), AiUsage(3, 3), structured)

    private fun message(role: AiRole, text: String) = AiMessage(role, listOf(AiContentPart.Text(text)))

    private class Script(
        private val capabilities: AiToolCapabilities,
        vararg steps: (AiRequest) -> AiResponse,
    ) : StreamingAiProvider, AiToolCapableProvider {
        private val script = steps.toList()
        val requests = mutableListOf<AiRequest>()

        override fun toolCapabilities(model: AiModel) = capabilities

        override suspend fun generate(request: AiRequest): AiResponse =
            script[requests.size](request).also { requests.add(request) }

        override fun stream(request: AiRequest) = flow {
            val response = generate(request)
            response.output.forEachIndexed { index, item ->
                if (item is AiMessage) emit(AiStreamEvent.TextDelta(index, 0, (item.content.single() as AiContentPart.Text).text))
                emit(AiStreamEvent.OutputItemCompleted(index, item))
            }
            emit(AiStreamEvent.Completed(response))
        }
    }

    private class FakeServer(vararg names: String) {
        val allowed = names.toMutableSet()
        val invoked = mutableListOf<Pair<String, AiValue.Object>>()
        var result: suspend (String, AiValue.Object) -> AiValue.Object = { _, args -> args }

        fun routes(): List<McpToolRoute> = routes(parseJsonResponseFormat("tool", """{"type":"object"}""").schema)

        fun routes(schema: AiValue.Object): List<McpToolRoute> = allowed.map { name ->
            McpToolRoute(this, AiTool(name, "Test tool", schema), { require(it in allowed) }) { actual, args ->
                invoked.add(actual to args)
                result(actual, args)
            }
        }
    }
}
