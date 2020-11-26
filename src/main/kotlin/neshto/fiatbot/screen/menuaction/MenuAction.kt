package neshto.fiatbot.screen.menuaction

import kotlinx.coroutines.runBlocking
import neshto.fiatbot.DBA
import org.litote.kmongo.eq

data class MenuAction(
    val name: MenuActionName,
    val text: String
) {
    companion object {
        private val mongodbCollection = DBA.getCollection<MenuAction>()

        fun getByName(name: MenuActionName): MenuAction? = runBlocking {
            mongodbCollection.findOne(MenuAction::name eq name)
        }
        fun getTextByName(name: MenuActionName): String = getByName(name)?.text ?: ""

        fun getAll(): List<MenuAction> = runBlocking {
            mongodbCollection.find().toList()
        }
    }
}