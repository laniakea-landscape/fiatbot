package neshto.fiatbot.cryptomanager

import neshto.fiatbot.WALLETS_DIR
import neshto.fiatbot.currency.Crypta
import java.io.File

abstract class Manager(val currency: Crypta) {

    fun getFilesDir(): String {
        val folder = "$WALLETS_DIR/${this.currency}/"
        File(folder).mkdirs()
        return folder
    }

    abstract fun start()
    abstract fun createNewWallet(uid: Int): Map<String, Any?>
    abstract fun addWallet(uid: Int, data: Map<String, Any?>)
    abstract fun lookForWallet(uid: Int): Boolean
    abstract fun getBalance(uid: Int): Float
    abstract fun getStringBalance(uid: Int): String
    abstract fun obtainFillUpAddress(uid: Int): String
    abstract fun calculateFee(toId: Int, coinsAmount: Float): Float
    abstract fun send(fromId: Int, toId: Int, coinsAmount: Float): String?
}