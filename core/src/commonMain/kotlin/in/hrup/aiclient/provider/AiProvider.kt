package `in`.hrup.aiclient.provider

import `in`.hrup.aiclient.model.generation.AiRequest
import `in`.hrup.aiclient.model.generation.AiResponse
import `in`.hrup.aiclient.model.generation.AiStreamEvent
import kotlinx.coroutines.flow.Flow

interface AiProvider {
    /** Performs one generation step and returns any requested tool calls as output items. */
    suspend fun generate(request: AiRequest): AiResponse
}

interface StreamingAiProvider : AiProvider {
    /**
     * Returns a cold flow. Each collection performs one generation step.
     * Success emits exactly one [AiStreamEvent.Completed] as the final event.
     * Failures propagate through the flow, and cancellation stops generation.
     */
    fun stream(request: AiRequest): Flow<AiStreamEvent>
}
