package `in`.hrup.aiclient.model.generation

data class AiModel(val id: String) {
    init {
        require(id.isNotBlank()) { "Model ID must not be blank" }
    }
}
