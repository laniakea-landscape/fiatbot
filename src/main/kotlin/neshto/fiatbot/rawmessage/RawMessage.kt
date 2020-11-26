package neshto.fiatbot.rawmessage

import com.pengrad.telegrambot.model.request.*
import com.pengrad.telegrambot.request.SendMessage
import neshto.fiatbot.Deal
import neshto.fiatbot.ExchangeRate
import neshto.fiatbot.Moneybag
import neshto.fiatbot.advert.Advert
import neshto.fiatbot.cryptomanager.ManagerPool
import neshto.fiatbot.currency.Crypta
import neshto.fiatbot.screen.InlineAction
import neshto.fiatbot.screen.ScreenName
import neshto.fiatbot.screen.menuaction.MenuActionName
import neshto.fiatbot.trader.*

data class RawMessage(
    val text: String,
    val menuKeyboard: List<List<KeyboardButton>>? = null,
    val inlineKeyboard: List<List<InlineKeyboardButton>>? = null,
    val screenName: ScreenName? = null,
    val menuActionNames: Set<MenuActionName> = emptySet(),
    val inlineActions: Set<InlineAction> = emptySet()
) {
    fun toSendMessage(chatId: Long): SendMessage {
        val sendMessage = SendMessage(chatId, text).parseMode(ParseMode.Markdown)

        val menuKeyboard = this.menuKeyboard?.map { it.toTypedArray() }?.toTypedArray()
        val inlineKeyboard = this.inlineKeyboard?.map { it.toTypedArray() }?.toTypedArray()

        if (menuKeyboard != null) {
            val replyMarkup = if (menuKeyboard.isEmpty() || menuKeyboard.all { it.isEmpty() }) {
                ReplyKeyboardRemove()
            } else {
                ReplyKeyboardMarkup(* menuKeyboard).resizeKeyboard(true)
            }
            return sendMessage.replyMarkup(replyMarkup)
        }

        if (inlineKeyboard != null) {
            return sendMessage.replyMarkup(InlineKeyboardMarkup(* inlineKeyboard))
        }

        return sendMessage
    }

    fun insertMarketCourse(currency: Crypta): RawMessage {
        val course = ExchangeRate.getCourse(currency)

        return this.copy(text = this.text.replace(Macros.MARKET_COURSE, course.toString()))
    }

    fun insertAdvertInfo(advert: Advert): RawMessage {
        val rawText = this.text

        val text = rawText
            .replace(Macros.Advert.TYPE, advert.type?.text ?: "<type>")
            .replace(Macros.Advert.TYPE_C, advert.type?.text?.capitalize() ?: "<type>")
            .replace(Macros.Advert.CURRENCY_FULL, advert.currency?.fullName ?: "<currency_full>")
            .replace(Macros.Advert.CURRENCY, advert.currency?.code ?: "<currency>")
            .replace(Macros.Advert.PAYWAY, advert.payWay?.fullName ?: "<payway>")
            .replace(Macros.Advert.COURSE, advert.course?.toString() ?: "<course>")
            .replace(Macros.Advert.MIN, advert.min?.toString() ?: "<min>")
            .replace(Macros.Advert.MAX, advert.max?.toString() ?: "<max>")
            .replace(
                Macros.Advert.DESCRIPTION,
                if (advert.description.isNullOrEmpty()) "<description>" else "*Подробности:*\n${advert.description}"
            )
            .replace(Macros.Advert.ACTIVE, if (advert.active) "✔" else "❎")
            .replace(Macros.Advert.TODO, advert.type?.toDo ?: "")
            .replace(Macros.Advert.TODO_C, advert.type?.toDo?.capitalize() ?: "")
            .replace(
                Macros.Advert.AUTHOR_LINK,
                Trader.getByUid(advert.traderId ?: Trader.GUEST.uid)?.link ?: "<author_link>"
            )

        return this.copy(text = text)
    }

    fun insertDealInfo(deal: Deal): RawMessage {
        val rawText = this.text

        val text = rawText
            .replace(Macros.Deal.AMOUNT, deal.amount.toString())
            .replace(Macros.Deal.COINS, deal.coins.toString())
            .replace(Macros.Deal.COINS_TOTAL, deal.coinsTotal.toString())
            .replace(Macros.Deal.CURRENCY, deal.currency.toString())
            .replace(Macros.Deal.INITIATOR_LINK, Trader.getByUid(deal.initiatorUid)?.link ?: "<deal_initiator_link>")
            .replace(Macros.Deal.RECEIVER_LINK, Trader.getByUid(deal.receiverUid)?.link ?: "<deal_receiver_link>")
            .replace(Macros.Deal.SELLER_LINK, Trader.getByUid(deal.sellerUid)?.link ?: "<deal_seller_link>")
            .replace(Macros.Deal.BUYER_LINK, Trader.getByUid(deal.buyerUid)?.link ?: "<deal_buyer_link>")

        return this.copy(text = text)
    }

    fun insertTransactionId(txId: String?): RawMessage {
        val rawText = this.text

        val text = rawText
            .replace(Macros.Transaction.ID, txId ?: "<txId>")

        return this.copy(text = text)
    }

    fun insertBalances(moneybags: Map<Crypta, Moneybag>, uid: Int): RawMessage {
        val trader = Trader.getByUid(uid)

        val text = this.text.replace(
            Macros.BALANCES,
            run {
                val output = moneybags.map { (currency, moneybag) ->
                    val balance = ManagerPool.getManager(currency).getBalance(uid)
                    val stringBalance = ManagerPool.getManager(currency).getStringBalance(uid)
                    val blockedBalance = if (moneybag.blocked > 0) " (${"%.8f".format(moneybag.blocked)} заблокировано)" else ""
                    "$currency: $stringBalance$blockedBalance\n(~${balance * ExchangeRate.getCourse(currency)} RUB)\n"
                }.joinToString("\n")


                if (output.isEmpty()) "...пока нет кошельков..." else output
            }
        )

        return this.copy(text = text)
    }

    fun insertTraderInfo(trader: Trader): RawMessage {
        val text = this.text.replace(Macros.Trader.CODENAME, trader.link)

        return this.copy(text = text)
    }

    fun insertCollocutorInfo(collocutor: Trader): RawMessage {
        val text = this.text.replace(Macros.Collocutor.CODENAME, collocutor.link)

        return this.copy(text = text)
    }

    fun insertMessage(message: String): RawMessage {
        val text = this.text.replace(Macros.MESSAGE, message)

        return this.copy(text = text)
    }
}
