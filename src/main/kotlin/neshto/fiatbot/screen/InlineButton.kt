package neshto.fiatbot.screen

data class InlineButton(
    val text: String,
    val inlineAction: InlineAction?,
    val inlineData: Map<String, Any>?,
    val url: String?
)