package chat.keryx.app.domain.model

data class Session(
    val id: String,
    val roomId: String,
    val title: String,
    val timestamp: Long
)
