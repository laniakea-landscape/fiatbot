package neshto.fiatbot.screen

import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.KeyboardButton
import kotlinx.coroutines.runBlocking
import neshto.fiatbot.CallbackData
import neshto.fiatbot.DBA
import neshto.fiatbot.rawmessage.RawMessage
import neshto.fiatbot.screen.menuaction.MenuAction
import neshto.fiatbot.updateprocessor.checkJson
import org.litote.kmongo.eq

data class Screen(
    val name: ScreenName,
    val text: String,
    val menuButtons: List<List<MenuButton>>? = null,
    val inlineButtons: List<List<InlineButton>>? = null
) {
    val rawMessage: RawMessage by lazy {
        RawMessage(
            text = text,
            menuKeyboard = this.menuButtons?.map { buttonsRow ->
                buttonsRow.map { button ->
                    KeyboardButton(MenuAction.getTextByName(button.menuActionName))
                }
            },
            inlineKeyboard = this.inlineButtons?.map { buttonsRow ->
                buttonsRow.map { button ->
                    val inlineKeyboardButton = InlineKeyboardButton(button.text)
                    when {
                        button.inlineAction != null ->
                            inlineKeyboardButton.callbackData(
                                CallbackData(button.inlineAction, button.inlineData ?: emptyMap()).json.checkJson()
                            )
                        button.url != null -> inlineKeyboardButton.url(button.url)
                    }
                    inlineKeyboardButton
                }
            },
            screenName = this.name,
            menuActionNames = this.menuButtons?.flatten()?.map { it.menuActionName }?.toSet() ?: emptySet(),
            inlineActions = this.inlineButtons?.flatten()?.map { it.inlineAction }?.filterNotNull()?.toSet() ?: emptySet()
        )
    }

    fun insertInButtonsData(key: String, data: Any): Screen {
        return this.copy(
            inlineButtons = this.inlineButtons?.map { inlineButtonsRow ->
                inlineButtonsRow.map { inlineButton ->
                    val inlineData = (inlineButton.inlineData ?: emptyMap()).toMutableMap()
                    inlineData[key] = data
                    inlineButton.copy(inlineData = inlineData)
                }
            }
        )
    }

    companion object {
        private val mongodbCollection = DBA.getCollection<Screen>()

        fun getByName(name: ScreenName): Screen? = runBlocking {
            mongodbCollection.findOne(Screen::name eq name)
        }

        fun getAll(): List<Screen> = runBlocking {
            mongodbCollection.find().toList()
        }
    }
}
