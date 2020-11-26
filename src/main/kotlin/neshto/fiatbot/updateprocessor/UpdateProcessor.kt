package neshto.fiatbot.updateprocessor

import com.google.gson.Gson
import com.mongodb.reactivestreams.client.Success
import com.pengrad.telegrambot.model.CallbackQuery
import com.pengrad.telegrambot.model.Message
import com.pengrad.telegrambot.model.Update
import com.pengrad.telegrambot.model.User
import com.pengrad.telegrambot.model.request.InlineKeyboardButton
import com.pengrad.telegrambot.model.request.KeyboardButton
import com.pengrad.telegrambot.model.request.ParseMode
import com.pengrad.telegrambot.request.AnswerCallbackQuery
import com.pengrad.telegrambot.request.SendMessage
import neshto.fiatbot.*
import neshto.fiatbot.advert.Advert
import neshto.fiatbot.advert.AdvertType
import neshto.fiatbot.advert.PayWay
import neshto.fiatbot.cryptomanager.ManagerPool
import neshto.fiatbot.currency.Crypta
import neshto.fiatbot.rawmessage.RawMessage
import neshto.fiatbot.screen.InlineAction
import neshto.fiatbot.screen.menuaction.MenuAction
import neshto.fiatbot.screen.menuaction.MenuActionName
import neshto.fiatbot.screen.Screen
import neshto.fiatbot.screen.ScreenName
import neshto.fiatbot.trader.Role
import neshto.fiatbot.trader.Trader
import org.slf4j.LoggerFactory

fun String.checkJson(): String {
    if (this.length > 64) {
        throw FiatBotException("JSON string is longer than 64 chars: $this")
    } else {
        return this
    }
}

class UpdateProcessor(private val update: Update, private val bot: FiatBot) {

    private val log = LoggerFactory.getLogger(this::class.java.simpleName)

    private val updateType = when {
        update.message() != null -> UpdateType.MESSAGE
        update.callbackQuery() != null -> UpdateType.CALLBACK_QUERY
        else -> throw FiatBotException("Unknown update type!")
    }

    private val chatId = when (this.updateType) {
        UpdateType.MESSAGE -> update.message()!!.chat()?.id() ?: throw FiatBotException("No chat in message!")
        UpdateType.CALLBACK_QUERY -> update.callbackQuery()!!.message()?.chat()?.id() ?: throw FiatBotException(
            "No chat in callback query!"
        )
    }

    private val user: User = run {
        val user = when (this.updateType) {
            UpdateType.MESSAGE -> update.message()?.from() ?: throw FiatBotException(
                "No user in message!"
            )
            UpdateType.CALLBACK_QUERY -> update.callbackQuery().from()
                ?: throw FiatBotException("No user in callback query!")
        }
        if (user.isBot) throw FiatBotException("User is a bot! (${user.username()})")
        user
    }

    private val trader: Trader = Trader.getByUid(user.id()) ?: Trader.GUEST
    private val isGuest: Boolean
        get() = this.trader == Trader.GUEST
    private val isAdmin: Boolean
        get() = !this.isGuest && (this.trader).roles.contains(Role.ADMIN)

    private val message: Message? = when (this.updateType) {
        UpdateType.MESSAGE -> update.message()
        else -> null
    }

    private val callbackQuery: CallbackQuery? = when (this.updateType) {
        UpdateType.CALLBACK_QUERY -> update.callbackQuery()
        else -> null
    }

    private val config: Config = bot.config

    private val errorScreen =
        Screen(ScreenName.ERROR, "Произошла непредвиденная ошибка...")

    fun process() {
        when (this.updateType) {
            UpdateType.MESSAGE -> this.processMessage()
            UpdateType.CALLBACK_QUERY -> this.processCallbackQuery()
        }
    }

    private fun getScreen(screenName: ScreenName): Screen =
        this.config.screens.getOrDefault(screenName, errorScreen)

    private fun getMenuAction(menuActionName: MenuActionName): MenuAction {
        return this.config.menuActions.getOrElse(
            menuActionName,
            { throw FiatBotException("Wrong menu action name! ($menuActionName)") }
        )
    }

    private fun a(menuActionName: MenuActionName) = this.getMenuAction(menuActionName).text

    private fun checkRawInput(screenName: ScreenName, incomingText: String): Boolean =
        this.trader.currentScreenName == screenName
        && this.getScreen(screenName).rawMessage.menuActionNames.map {
            this.getMenuAction(it).text
        }.none { txt -> txt == incomingText }

//    private fun checkCodenameInput(incomingText: String)

    private fun processMessage() {
        this.message as Message
        // TODO: Если прислали не текст?
        val incomingText = this.message.text() as String
        log.debug("Incoming message: \"$incomingText\"")

        val reply = when (this.isGuest) {
            // GUEST
            true -> when (incomingText) {

                a(MenuActionName.REGISTRATION) -> this.getScreen(ScreenName.REGISTRATION).rawMessage
                a(MenuActionName.GOTO_BEGINNING) -> this.getScreen(ScreenName.GUEST_HELLO).rawMessage
                a(MenuActionName.CONFIRM_REGISTRATION) -> {
                    Trader(
                        uid = this.user.id(),
                        username = this.user.username(),
                        chatId = this.chatId,
                        currentScreenName = ScreenName.REGISTRATION_COMPLETED
                    ).register() as Success
                    this.getScreen(ScreenName.REGISTRATION_COMPLETED).rawMessage
                }

                a(MenuActionName.INFO) -> {
                    this.getScreen(ScreenName.INFO).rawMessage
                }

                else -> this.getScreen(ScreenName.GUEST_HELLO).rawMessage
            }

            // REGISTERED TRADER
            false -> {
                if (this.trader.currentScreenName != ScreenName.SEND_MESSAGE) {
                    this.trader.clearCurrentCollocutorUid()
                }

                when {
                    incomingText.matches(Regex("^/[abcdef0-9]{6,6}$")) ->
                        this.processTraderLinkInput(incomingText)
                    this.checkRawInput(ScreenName.CREATE_ADVERT_ENTER_COURSE, incomingText) ->
                        this.processAdvCourseInput(incomingText)
                    this.checkRawInput(ScreenName.CREATE_ADVERT_ENTER_LIMITS, incomingText) ->
                        this.processAdvLimitsInput(incomingText)
                    this.checkRawInput(ScreenName.DEAL_ENTER_AMOUNT, incomingText) ->
                        this.processDealAmountInput(incomingText)
                    this.checkRawInput(ScreenName.SEND_MESSAGE, incomingText) ->
                        this.processSendMessageInput(incomingText)

                    else -> when (incomingText) {
                        a(MenuActionName.GOTO_BEGINNING) -> this.getScreen(ScreenName.TRADER_MAIN).rawMessage
                        a(MenuActionName.INFO) -> this.getScreen(ScreenName.INFO).rawMessage
                        a(MenuActionName.WALLET) -> this.walletScreen()

                        a(MenuActionName.CHANGE) -> this.changeScreen()

                        // Создание объявления
                        a(MenuActionName.CREATE_ADVERT_SELECT_PURCHASE) ->
                            this.advCreateSelectCurrencyScreen(AdvertType.PURCHASE)
                        a(MenuActionName.CREATE_ADVERT_SELECT_SELL) ->
                            this.advCreateSelectCurrencyScreen(AdvertType.SELL)

                        a(MenuActionName.CREATE_ADVERT_SELECT_CURRENCY_BTC) ->
                            this.advCreateSelectPayWayScreen(Crypta.BTC)

                        a(MenuActionName.CREATE_ADVERT_SELECT_PAYWAY_SBER) ->
                            this.advCreateEnterCourseScreen(PayWay.SBER)
                        a(MenuActionName.CREATE_ADVERT_SELECT_PAYWAY_TINKOFF) ->
                            this.advCreateEnterCourseScreen(PayWay.TINKOFF)
                        a(MenuActionName.CREATE_ADVERT_SELECT_PAYWAY_QIWI) ->
                            this.advCreateEnterCourseScreen(PayWay.QIWI)

                        a(MenuActionName.CREATE_ADVERT_APPROVE) -> this.advCreateApproved()
                        a(MenuActionName.CREATE_ADVERT_CANCEL) -> this.advCreateCanceled()

                        // Сделки
                        a(MenuActionName.DEAL_APPROVE) -> this.dealApprove()
                        a(MenuActionName.DEAL_CANCEL)-> this.dealCancel()

                        // ADMIN
                        // TODO: helloScreen()
                        a(MenuActionName.ADMIN_RELOAD_CONFIG) -> if (this.isAdmin) {
                            this.bot.reloadConfig()
                            this.helloScreen("Обновлено!")
                        } else null

                        a(MenuActionName.ADMIN_VIEW_WALLETS) -> if (this.isAdmin) {
                            this.appWalletScreen()
                        } else null

                        else -> null
                    }
                }
            }
        }

        if (reply != null) {
            val response = this.bot.execute(reply.toSendMessage(this.chatId))
            if (!response.isOk) {
                log.error("Error in response on message \"$reply\":\t${response.description()}")
            } else if (reply.screenName != null) {
                this.trader.setCurrentState(reply)
            }
        }
    }

    private fun processCallbackQuery() {
        if (!this.isGuest) {

            this.callbackQuery as CallbackQuery
            val dataString = this.callbackQuery.data() ?: throw FiatBotException("No data in callback query!")

            val callbackData = Gson().fromJson(dataString, CallbackData::class.java)
            log.debug("Incoming callback query: \"$callbackData\"")

            val reply: RawMessage? = when (this.trader.currentScreenName) {

                else -> when (callbackData.a) {
                    InlineAction.GOTO_WALLET -> this.walletScreen()
                    InlineAction.CREATE_WALLET -> this.createWalletScreen()
                    InlineAction.CREATE_CERTAIN_WALLET -> this.createWallet(Crypta.valueOf(callbackData.d["curr"] as String))

                    InlineAction.FILL_UP_WALLET -> this.fillupWalletScreen()
                    InlineAction.FILL_UP_CERTAIN_WALLET -> this.fillupWallet(Crypta.valueOf(callbackData.d["curr"] as String))

                    InlineAction.GOTO_ADV_CREATE -> this.advCreateSelectTypeScreen()

                    InlineAction.CHANGE_PURCHASE -> this.changeSelectCurrencyScreen(AdvertType.PURCHASE)
                    InlineAction.CHANGE_SELL -> this.changeSelectCurrencyScreen(AdvertType.SELL)

                    InlineAction.CHANGE_SELECT_CURRENCY ->
                        this.changeSelectPayWayScreen(Crypta.valueOf(callbackData.d["curr"] as String))
                    InlineAction.CHANGE_SELECT_PAYWAY ->
                        this.changeAdvertsListScreen(PayWay.valueOf(callbackData.d["payWay"] as String))
                    InlineAction.CHANGE_GOTO_ADVERT ->
                        this.advertScreen(callbackData.d["id"] as String)
                    InlineAction.CHANGE_BACK -> this.changeBack()

                    // Сделки
                    InlineAction.DEAL_CREATE -> this.dealCreateScreen(Advert.getById(callbackData.d["id"] as String))
                    InlineAction.DEAL_REJECT -> this.dealCancel(callbackData.d["id"] as String)

                    InlineAction.DEAL_MONEY_SENT -> this.dealMoneySent(callbackData.d["id"] as String)
                    InlineAction.DEAL_MONEY_ADMIT -> this.dealMoneyReceived(callbackData.d["id"] as String)

                    // Сообщения
                    InlineAction.SEND_MESSAGE -> this.sendMessageScreen((callbackData.d["cuid"] as Double).toInt())
                    InlineAction.SEND_MESSAGE_CANCEL -> this.traderInfoScreen((callbackData.d["cuid"] as Double).toInt())

                    else -> null
                }
            }

            if (reply != null) {
                val response = this.bot.execute(reply.toSendMessage(this.chatId))
                if (!response.isOk) {
                    log.error("Error in response on callback query:\t${response.description()}")
                } else if (reply.screenName != null) {
                    this.trader.setCurrentState(reply)
                }
            }
            this.bot.execute(AnswerCallbackQuery(this.callbackQuery.id()))
        }
    }

    private fun helloScreen(alternativeText: String? = null): RawMessage {
        val unpreparedMessage = this.getScreen(ScreenName.TRADER_MAIN).rawMessage

        val adminButtons = if (this.isAdmin) {
            listOf(
                listOf(KeyboardButton(this.getMenuAction(MenuActionName.ADMIN_RELOAD_CONFIG).text)),
                listOf(KeyboardButton(this.getMenuAction(MenuActionName.ADMIN_VIEW_WALLETS).text))
            )
        } else emptyList()

        return unpreparedMessage.copy(
            text = alternativeText ?: unpreparedMessage.text,
            menuKeyboard = when (unpreparedMessage.menuKeyboard) {
                null -> adminButtons
                else -> unpreparedMessage.menuKeyboard + adminButtons
            }
        )
    }

    private fun createWalletScreen(): RawMessage {
        val rawMessage = this.getScreen(ScreenName.CREATE_WALLET).rawMessage
        val missedCurrencies = Crypta.values().filter {
            !this.trader.moneybags.containsKey(it)
        }

        val missedCurrenciesKeyboard: List<List<InlineKeyboardButton>> = missedCurrencies.map {
            val json = CallbackData(InlineAction.CREATE_CERTAIN_WALLET, mapOf("curr" to it)).json.checkJson()

            listOf(InlineKeyboardButton("${it.code} (${it.fullName})").callbackData(json))
        }

        return rawMessage.copy(inlineKeyboard = missedCurrenciesKeyboard + (rawMessage.inlineKeyboard?: emptyList()))
    }

    private fun createWallet(currency: Crypta): RawMessage {
        this.trader.addMoneybag(currency)
        return this.walletScreen()
    }

    private fun walletScreen(): RawMessage {
        val unpreparedMessage = this.getScreen(ScreenName.WALLET).rawMessage

        return unpreparedMessage.insertBalances(this.trader.moneybags, this.trader.uid).copy(
            inlineKeyboard = if (trader.moneybags.isNotEmpty()) {
                listOf<List<InlineKeyboardButton>>(
                    listOf(
                        InlineKeyboardButton("Пополнить")
                            .callbackData(CallbackData(InlineAction.FILL_UP_WALLET, emptyMap()).json)
                    )
                )
            } else emptyList<List<InlineKeyboardButton>>() +
                    if (trader.moneybags.size < Crypta.values().size) {
                        listOf<List<InlineKeyboardButton>>(
                            listOf(
                                InlineKeyboardButton("Создать")
                                    .callbackData(CallbackData(InlineAction.CREATE_WALLET, emptyMap()).json)
                            )
                        )
                    } else emptyList()
        )
    }

    private fun fillupWalletScreen(): RawMessage {
        val unpreparedMessage = this.getScreen(ScreenName.FILL_UP_WALLET).rawMessage

        val missedCurrenciesKeyboard: List<List<InlineKeyboardButton>> =
            this.trader.moneybags.keys.map {
                val json = CallbackData(
                    InlineAction.FILL_UP_CERTAIN_WALLET,
                    mapOf("curr" to it)
                ).json.checkJson()

                listOf(
                    InlineKeyboardButton("${it.code}   (${it.fullName})")
                        .callbackData(json)
                )
            }

        return unpreparedMessage.copy(
            inlineKeyboard = missedCurrenciesKeyboard + (unpreparedMessage.inlineKeyboard
                ?: emptyList())
        )
    }

    private fun fillupWallet(currency: Crypta): RawMessage {
        val address = this.trader.obtainFillUpAddress(currency)
        return RawMessage("Адрес пополнения:\n`$address`", null, null)
    }

    private fun appWalletScreen(): RawMessage {
        val unpreparedMessage = this.getScreen(ScreenName.APP_WALLET).rawMessage

        return unpreparedMessage.insertBalances(this.config.moneybags, BOT_UID).copy(
            inlineKeyboard = if (this.config.moneybags.isNotEmpty()) {
                listOf(
                    listOf(
                        InlineKeyboardButton("Вывести")
                            .callbackData(CallbackData(InlineAction.ADMIN_WITHDRAW, emptyMap()).json)
                    )
                )
            } else emptyList<List<InlineKeyboardButton>>()
        )
    }

    private fun dealCreateScreen(advert: Advert? = null): RawMessage {
        val dealAdvert = advert ?: this.trader.dealAdvert ?: throw FiatBotException("dealCreateScreen - adverts are null")
        if (advert != null) {
            this.trader.setDealAdvert(dealAdvert)
        }
        return this.getScreen(ScreenName.DEAL_ENTER_AMOUNT).rawMessage.insertAdvertInfo(dealAdvert)
    }

    private fun processDealAmountInput(incomingText: String): RawMessage =
        when (val amount = incomingText.toIntOrNull()) {
            is Int -> {
                log.debug("Amount input")
                val advert = this.trader.dealAdvert ?: throw FiatBotException("dealAdvert is null on amount input")
                val deal = this.trader.setDeal(amount)
                val errors: MutableList<String> = mutableListOf()
                if (amount < advert.min!!) errors.add("Сумма слишком маленькая")
                if (amount > advert.max!!) errors.add("Сумма слишком большая")
                // Проверка продавца
                val seller = Trader.getByUid(deal.sellerUid) ?: throw FiatBotException("Seller is null")
                val sellerFunds = seller.getFunds(deal.currency)
                log.debug("sellerFund >< deal.coinsTotal:\t$sellerFunds ${deal.coinsTotal}")
                if (sellerFunds < deal.coinsTotal) {
                    errors.add(
                        "У ${if (deal.sellerUid == this.trader.uid) "вас" else "продавца"} недостаточно ${deal.currency}"
                    )
                }
                if (errors.size > 0) {
                    this.trader.clearDeal()
                    this.bot.execute(SendMessage(this.chatId, errors.joinToString(";\n")))
                    this.dealCreateScreen()
                } else {
                    this.trader.clearDealAdvert()
                    val screen = if (this.trader.uid == deal.buyerUid) {
                        this.getScreen(ScreenName.DEAL_SELL_APPROVE_AMOUNT)
                    } else {
                        this.getScreen(ScreenName.DEAL_PURCHASE_APPROVE_AMOUNT)
                    }

                    screen.rawMessage.insertDealInfo(deal)
                }
            }
            else -> {
                this.bot.execute(SendMessage(this.chatId, "Некорректно введена сумма"))
                this.dealCreateScreen()
            }
        }

    private fun dealCancel(id: String? = null): RawMessage {
        if (id != null) {
            val deal = Deal.findById(id) ?: throw FiatBotException("Deal is null on late cancel")
            val seller = Trader.getByUid(deal.sellerUid)?.unblockCoins(deal.currency, deal.coinsTotal)
                ?: throw FiatBotException("No seller on deal canceling")
            val buyer = Trader.getByUid(deal.buyerUid)
                ?: throw FiatBotException("No buyer on deal canceling")
            val sellerRawMessage =
                this.getScreen(ScreenName.DEAL_CANCELED_SELLER)
                    .rawMessage
                    .insertDealInfo(deal)
            val buyerRawMessage =
                this.getScreen(ScreenName.DEAL_CANCELED_BUYER)
                    .rawMessage
                    .insertDealInfo(deal)
            return if (this.trader.uid == seller.uid) {
                val buyerSendMessage =
                    buyerRawMessage.toSendMessage(buyer.chatId).parseMode(ParseMode.Markdown)
                this.bot.execute(buyerSendMessage)
                sellerRawMessage
            } else {
                val sellerSendMessage =
                    sellerRawMessage.toSendMessage(seller.chatId).parseMode(ParseMode.Markdown)
                this.bot.execute(sellerSendMessage)
                buyerRawMessage
            }
        } else {
            this.trader.clearDeal()
            return this.helloScreen("␡ Вы отменили сделку")
        }
    }

    private fun dealApprove(): RawMessage {
        val deal = this.trader.deal ?: throw FiatBotException("Trader.deal is null on deal approve")

        val seller = Trader.getByUid(deal.sellerUid) ?: throw FiatBotException("Seller is null on deal approve")
        seller.blockCoins(deal.currency, deal.coinsTotal)

        Deal.save(deal)
        this.trader.clearDeal()

        val (receiverScreen, initiatorScreen) = if (deal.receiverIsBuyer) {
            Pair(this.getScreen(ScreenName.DEAL_MONEY_SEND), this.getScreen(ScreenName.DEAL_MONEY_WAIT))
        } else {
            Pair(this.getScreen(ScreenName.DEAL_MONEY_WAIT), this.getScreen(ScreenName.DEAL_MONEY_SEND))
        }

        val receiverSendMessage = receiverScreen.copy(
            inlineButtons = receiverScreen.inlineButtons?.map { buttonsRow ->
                buttonsRow.map { button ->
                    button.copy(inlineData = mapOf("id" to deal._id.toString()))
                }
            }
        ).rawMessage
            .insertDealInfo(deal)
            .toSendMessage(deal.receiverChatId)
            .parseMode(ParseMode.Markdown)

        val initiatorRawMessage = initiatorScreen.copy(
            inlineButtons = initiatorScreen.inlineButtons?.map { buttonsRow ->
                buttonsRow.map { button ->
                    button.copy(inlineData = mapOf("id" to deal._id.toString()))
                }
            }
        ).rawMessage.insertDealInfo(deal)

        this.bot.execute(receiverSendMessage)
        this.bot.execute(this.helloScreen("Сделка создана").toSendMessage(this.chatId))
        return initiatorRawMessage
    }

    private fun dealMoneySent(did: String): RawMessage {
        val deal = Deal.findById(did)?.setPaid() ?: throw FiatBotException("No deal at money sent")

        val currentTraderIsSeller = deal.sellerUid == this.trader.uid

        val sellerRawMessage =
            this.getScreen(ScreenName.DEAL_MONEY_SENT_SELLER).rawMessage.insertDealInfo(deal)
        val buyerRawMessage =
            this.getScreen(ScreenName.DEAL_MONEY_SENT_BUYER).rawMessage.insertDealInfo(deal)

        return if (currentTraderIsSeller) {
            this.bot.execute(buyerRawMessage.toSendMessage(deal.buyerChatId).parseMode(ParseMode.Markdown))
            sellerRawMessage
        } else {
            this.bot.execute(sellerRawMessage.toSendMessage(deal.sellerChatId).parseMode(ParseMode.Markdown))
            buyerRawMessage
        }
    }

    private fun dealMoneyReceived(did: String): RawMessage {
        val deal = Deal.findById(did) ?: throw FiatBotException("No deal at coins sent")
        val currentTraderIsSeller = deal.sellerUid == this.trader.uid

        val txId = ManagerPool.getManager(deal.currency).send(deal.sellerUid, deal.buyerUid, deal.coins)
        // TODO: Проверить успешность более элегантным способом
        if (txId != null) {
            val unblockedTrader = Trader.getByUid(deal.sellerUid)?.unblockCoins(deal.currency, deal.coinsTotal)
                ?: throw FiatBotException("Null seller on completed deal")
            if (currentTraderIsSeller) {
                this.trader.moneybags[deal.currency] =
                    unblockedTrader.moneybags[deal.currency] ?: throw FiatBotException("Null unblocked moneybag")
            }
            deal.delete()
        }

        val sellerRawMessage =
            this.getScreen(ScreenName.DEAL_COINS_SENT_SELLER).rawMessage.insertDealInfo(deal).insertTransactionId(txId)
        val buyerRawMessage =
            this.getScreen(ScreenName.DEAL_COINS_SENT_BUYER).rawMessage.insertDealInfo(deal).insertTransactionId(txId)

        return if (currentTraderIsSeller) {
            this.bot.execute(buyerRawMessage.toSendMessage(deal.buyerChatId).parseMode(ParseMode.Markdown))
            sellerRawMessage
        } else {
            this.bot.execute(sellerRawMessage.toSendMessage(deal.sellerChatId).parseMode(ParseMode.Markdown))
            buyerRawMessage
        }
    }

    private fun changeScreen(): RawMessage {
        return this.getScreen(ScreenName.CHANGE).rawMessage
    }

    private fun changeSelectCurrencyScreen(advType: AdvertType): RawMessage {
        val search = this.trader.updateCurrentSearch(advType, true)

        val rawMessage =
            this.getScreen(ScreenName.CHANGE_SELECT_CURRENCY).rawMessage.insertAdvertInfo(search)
        val currenciesButtons = this.trader.moneybags.keys.map {
            val json = CallbackData(
                InlineAction.CHANGE_SELECT_CURRENCY,
                mapOf("curr" to it)
            ).json.checkJson()

            listOf(InlineKeyboardButton("${it.code}   (${it.fullName})").callbackData(json))
        }

        return rawMessage.copy(inlineKeyboard = currenciesButtons + (rawMessage.inlineKeyboard ?: emptyList()))
    }

    private fun changeSelectPayWayScreen(currency: Crypta, back: Boolean = false): RawMessage {
        val search = this.trader.updateCurrentSearch(currency)

        val rawMessage =
            this.getScreen(ScreenName.CHANGE_SELECT_PAYWAY).rawMessage.insertAdvertInfo(search)
        val paywaysButtons = PayWay.values().map {
            val json = CallbackData(
                InlineAction.CHANGE_SELECT_PAYWAY,
                mapOf("payWay" to it)
            ).json.checkJson()

            listOf(InlineKeyboardButton(it.fullName).callbackData(json))
        }

        return rawMessage.copy(inlineKeyboard = paywaysButtons + (rawMessage.inlineKeyboard ?: emptyList()))
    }

    private fun changeAdvertsListScreen(payWay: PayWay): RawMessage {
        val search = this.trader.updateCurrentSearch(payWay)

        val rawMessage =
            this.getScreen(ScreenName.CHANGE_ADVERTS_LIST).rawMessage.insertAdvertInfo(search)
        val advertsButtons = Advert.getList(search).map {
            val json = CallbackData(
                InlineAction.CHANGE_GOTO_ADVERT,
                mapOf("id" to it._id.toString())
            ).json.checkJson()

            val icon = if (it.traderId == this.trader.uid) "✎" else "☑"
            listOf(InlineKeyboardButton("$icon ${it.course} RUB (${it.min} RUB — ${it.max} RUB)").callbackData(json))
        }

        return rawMessage.copy(inlineKeyboard = advertsButtons + (rawMessage.inlineKeyboard ?: emptyList()))
    }

    private fun changeBack() : RawMessage {
        val search = this.trader.currentSearch!!.copy()
        this.trader.currentSearchBack()

        return when {
            search.payWay != null -> this.changeSelectPayWayScreen(search.currency!!)
            search.currency != null -> this.changeSelectCurrencyScreen(search.type!!)
            else -> this.changeScreen()
        }
    }

    private fun advertScreen(aid: String):RawMessage {
        val advert = Advert.getById(aid) ?: throw FiatBotException("Null advert to go to (aid: $aid)")

        return if (advert.traderId == this.trader.uid) {
            RawMessage("Это твоё объявление")
        } else {
            val rawMessage = this.getScreen(ScreenName.ADVERT_VIEW).rawMessage.insertAdvertInfo(advert)

            val json = CallbackData(
                InlineAction.DEAL_CREATE,
                mapOf("id" to advert._id.toString())
            ).json.checkJson()

            rawMessage.copy(
                inlineKeyboard = listOf(
                    listOf(
                        InlineKeyboardButton("⁕ Создать сделку").callbackData(json)
                    )
                ) + (rawMessage.inlineKeyboard ?: emptyList())
            )
        }
    }

    private fun advCreateSelectTypeScreen(): RawMessage {
        this.trader.clearUncompletedAdvert()
        return this.getScreen(ScreenName.CREATE_ADVERT_SELECT_TYPE).rawMessage
    }

    private fun advCreateSelectCurrencyScreen(advType: AdvertType): RawMessage {
        this.trader.updateUncompletedAdvert(advType)

        val rawMessage = this.getScreen(ScreenName.CREATE_ADVERT_SELECT_CURRENCY).rawMessage
        val currenciesButtons = this.trader.moneybags.keys.map { currency ->
            listOf(
                KeyboardButton(
                    this.getMenuAction(
                        MenuActionName.valueOf("$CREATE_ADVERT_SELECT_CURRENCY_PREFIX${currency.code}")
                    ).text
                )
            )
        }

        return rawMessage.copy(
            menuKeyboard = currenciesButtons + (rawMessage.menuKeyboard ?: emptyList())
        )
    }

    private fun advCreateSelectPayWayScreen(currency: Crypta): RawMessage {
        this.trader.updateUncompletedAdvert(currency)

        return this.getScreen(ScreenName.CREATE_ADVERT_SELECT_PAYWAY).rawMessage
    }

    private fun advCreateEnterCourseScreen(payWay: PayWay? = null): RawMessage {
        val advert = if (payWay != null) {
            this.trader.updateUncompletedAdvert(payWay)
        } else {
            val adv = this.trader.uncompletedAdvert
            if (adv?.payWay != null) {
                adv
            } else {
                throw FiatBotException("advert is null or payWay is null when it is still not set!")
            }
        }

        val rawMessage = this.getScreen(ScreenName.CREATE_ADVERT_ENTER_COURSE).rawMessage
        return rawMessage.insertMarketCourse(advert.currency as Crypta)
    }

    private fun processAdvCourseInput(incomingText: String): RawMessage =
        when (val course = incomingText.toFloatOrNull()) {
            is Float -> this.advCreateEnterLimitsScreen(course)
            else -> {
                this.bot.execute(SendMessage(this.chatId, "Некорректно введён курс"))
                this.advCreateEnterCourseScreen()
            }
        }

    private fun advCreateEnterLimitsScreen(course: Float? = null): RawMessage {
        if (course != null) {
            this.trader.updateUncompletedAdvert(course)
        } else if (this.trader.uncompletedAdvert?.course == null) {
            throw FiatBotException("advert is null or course is null when it is still not set!")
        }

        return this.getScreen(ScreenName.CREATE_ADVERT_ENTER_LIMITS).rawMessage
    }

    private fun processAdvLimitsInput(incomingText: String): RawMessage {
        val limits = incomingText
            .trim()
            .replace(Regex("^(\\d*)(\\D*)(\\d*)$"), "$1:$3")
            .split(':')
            .mapNotNull { it.toIntOrNull() }

        return if (limits.size == 2 && limits[0] < limits[1]) {
            this.advCreateApproveScreen(Pair(limits[0], limits[1]))
        } else {
            this.advCreateEnterLimitsScreen()
        }
    }

    private fun advCreateApproveScreen(limits: Pair<Int, Int>): RawMessage {
        val advert = this.trader.updateUncompletedAdvert(limits)

        return this.getScreen(ScreenName.CREATE_ADVERT_APPROVE).rawMessage.insertAdvertInfo(advert)
    }

    private fun advCreateApproved(): RawMessage {
        val advert = this.trader.uncompletedAdvert?.copy() ?: throw FiatBotException("Null uncompleted adverts on approve stage!")
        this.trader.clearUncompletedAdvert()

        return if (advert.create()) {
            this.helloScreen("✔ Объявление создано")
        } else {
            this.helloScreen("К сожалению, что-то пошло не так...")
        }
    }

    private fun advCreateCanceled(): RawMessage {
        this.trader.clearUncompletedAdvert()
        return this.helloScreen("Вы отменили создание объявления")
    }

    private fun processTraderLinkInput(codename: String): RawMessage {
        return when (val trader = Trader.getByCodename(codename)) {
            null -> this.helloScreen("Такого трейдера не зарегистрировано")
            else -> this.traderInfoScreen(trader)
        }
    }

    private fun traderInfoScreen(traderUid: Int): RawMessage {
        val trader = Trader.getByUid(traderUid) ?: throw FiatBotException("Null trader on trader info screen")
        return this.traderInfoScreen(trader)
    }

    private fun traderInfoScreen(trader: Trader): RawMessage {
        val rawScreen = this.getScreen(ScreenName.TRADER_INFO)
        return rawScreen.insertInButtonsData("cuid", trader.uid).rawMessage.insertTraderInfo(trader)
    }

    private fun sendMessageScreen(collocutorUid: Int): RawMessage {
        val rawScreen = this.getScreen(ScreenName.SEND_MESSAGE)
        val collocutor = Trader.getByUid(collocutorUid)
            ?: throw FiatBotException("Null collocutor on message send screen")
        this.trader.setCurrentCollocutorUid(collocutorUid)
        return rawScreen.insertInButtonsData("cuid", collocutorUid).rawMessage.insertCollocutorInfo(collocutor)
    }

    private fun processSendMessageInput(message: String): RawMessage {
        val collocutor = Trader.getByUid(
            this.trader.currentCollocutorUid
                ?: throw FiatBotException("Null currentCollocutorId on message sending")
        ) ?: throw FiatBotException("Null collocutor on message sending")

        val rawMessageToCollocutor = this.getScreen(ScreenName.MESSAGE)
            .insertInButtonsData("cuid", this.trader.uid)
            .rawMessage
            .insertCollocutorInfo(this.trader)
            .insertMessage(message)

        this.bot.execute(rawMessageToCollocutor.toSendMessage(collocutor.chatId))
        return this.getScreen(ScreenName.MESSAGE_SENT).rawMessage
    }

    companion object {
        const val CREATE_ADVERT_SELECT_CURRENCY_PREFIX = "CREATE_ADVERT_SELECT_CURRENCY_"
        val ALWAYS_POSSIBLE_MENU_ACTION_NAMES = setOf(MenuActionName.GOTO_BEGINNING)
    }
}