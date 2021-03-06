package neshto.fiatbot.screen

enum class InlineAction {
    GOTO_WALLET,
    FILL_UP_WALLET,
    FILL_UP_CERTAIN_WALLET,
    CREATE_WALLET,
    CREATE_CERTAIN_WALLET,

    CHANGE_SELL,
    CHANGE_PURCHASE,
    CHANGE_SELECT_CURRENCY,
    CHANGE_SELECT_PAYWAY,
    CHANGE_GOTO_ADVERT,
    CHANGE_BACK,

    DEAL_CREATE,
    DEAL_REJECT,
    DEAL_MONEY_SENT,
    DEAL_MONEY_ADMIT,

    GOTO_ADV_CREATE,

    SEND_MESSAGE,
    SEND_MESSAGE_CANCEL,

    ADMIN_WITHDRAW
}
