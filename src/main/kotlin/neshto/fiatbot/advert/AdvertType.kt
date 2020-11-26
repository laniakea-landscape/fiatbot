package neshto.fiatbot.advert

enum class AdvertType(val text: String, val toDo: String) {
    PURCHASE("покупка", "продать"),
    SELL("продажа", "купить")
}