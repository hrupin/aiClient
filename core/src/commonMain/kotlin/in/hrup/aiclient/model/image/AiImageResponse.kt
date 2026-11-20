package `in`.hrup.aiclient.model.image

data class AiImageResponse(
    val provider: String,
    val model: String,
    val requestId: String,
    val imageUrl: String,
    val request: String,
    val response: String,
    /** Settled credits when available, otherwise the cost quoted on submission. */
    val costCredits: Double? = null,
)
