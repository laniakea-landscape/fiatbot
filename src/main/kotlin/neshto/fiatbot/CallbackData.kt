package neshto.fiatbot

import com.google.gson.Gson
import neshto.fiatbot.screen.InlineAction

data class CallbackData(val a: InlineAction, val d: Map<String, Any>) {
    val json: String = Gson().toJson(this)
}