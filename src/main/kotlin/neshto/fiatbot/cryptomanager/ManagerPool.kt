package neshto.fiatbot.cryptomanager

import neshto.fiatbot.currency.Crypta

object ManagerPool {

    private val managers: Map<Crypta, Manager> = mapOf(
        Crypta.BTC to BTCManager()
    )

    init {
        this.validate()
    }

    private fun validate() = Crypta.values().forEach {
        this.managers.getOrElse(
            it
        ) { throw ManagerException("Not implemented market provider for $it in pool!") }
    }

    fun getManager(currency: Crypta) = this.managers[currency] as Manager
}