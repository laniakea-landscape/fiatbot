package neshto.fiatbot

import neshto.fiatbot.currency.Crypta
import neshto.fiatbot.screen.menuaction.MenuAction
import neshto.fiatbot.screen.menuaction.MenuActionName
import neshto.fiatbot.screen.Screen
import neshto.fiatbot.screen.ScreenName

data class Config(
    val screens: Map<ScreenName, Screen>,
    val menuActions: Map<MenuActionName, MenuAction>,
    val moneybags: Map<Crypta, Moneybag> = emptyMap()
)
