package neshto.fiatbot.rawmessage

object Macros {
    const val MARKET_COURSE: String = "\${market_course}"

    const val BALANCES: String = "\${balances}"

    const val MESSAGE: String = "\${message}"

    object Transaction {
        const val ID: String = "\${tx_id}"
    }

    object Advert {
        const val TYPE: String = "\${adv_info_type}"
        const val TYPE_C: String = "\${adv_info_type_c}"
        const val CURRENCY_FULL: String = "\${adv_info_currency_full}"
        const val CURRENCY: String = "\${adv_info_currency_short}"
        const val PAYWAY: String = "\${adv_info_payway}"
        const val COURSE: String = "\${adv_info_course}"
        const val MIN: String = "\${adv_info_min}"
        const val MAX: String = "\${adv_info_max}"
        const val DESCRIPTION: String = "\${adv_info_description}"
        const val ACTIVE: String = "\${adv_info_active}"
        const val TODO: String = "\${adv_info_todo}"
        const val TODO_C: String = "\${adv_info_todo_c}"
        const val AUTHOR_LINK: String = "\${adv_info_author_link}"
    }

    object Deal {
        const val CURRENCY: String = "\${deal_currency}"
        const val AMOUNT: String = "\${deal_amount}"
        const val COINS: String = "\${deal_coins}"
        const val COINS_TOTAL: String = "\${deal_coins_total}"
        const val INITIATOR_LINK: String = "\${deal_initiator_link}"
        const val RECEIVER_LINK: String = "\${deal_receiver_link}"
        const val SELLER_LINK: String = "\${deal_seller_link}"
        const val BUYER_LINK: String = "\${deal_buyer_link}"
    }

    object Trader {
        const val CODENAME: String = "\${trader_codename}"
    }

    object Collocutor {
        const val CODENAME: String = "\${collocutor_codename}"
    }
}