package neshto.fiatbot

import kotlinx.coroutines.runBlocking
import neshto.fiatbot.advert.PayWay
import neshto.fiatbot.cryptomanager.ManagerPool
import neshto.fiatbot.currency.Crypta
import neshto.fiatbot.trader.Trader
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.Id
import org.litote.kmongo.newId
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneId

data class Deal(
    @BsonId val _id: Id<Deal> = newId(),
    val initiatorUid: Int,
    val buyerUid: Int,
    val sellerUid: Int,
    val currency: Crypta,
    val payWay: PayWay,
    val course: Float,
    val amount: Int,
    val paid: Boolean = false,
    val approved: Boolean = false,
    val updateTime: LocalDateTime? = LocalDateTime.now(ZoneId.of(konfig[timezone]))
) {
    private val log = LoggerFactory.getLogger(this::class.java.simpleName)

    val coins = this.amount.div(this.course)
    private val netFee = ManagerPool.getManager(this.currency).calculateFee(this.buyerUid, this.coins)
    private val appFee = this.coins.times(konfig[transfer_fee].toFloat())
    val coinsTotal = this.coins + this.netFee + this.appFee

    val receiverUid = if (this.buyerUid == this.initiatorUid) this.sellerUid else this.buyerUid
    val receiverIsBuyer = this.receiverUid == this.buyerUid

    val sellerChatId by lazy { Trader.getChatIdByUid(this.sellerUid) }
    val buyerChatId by lazy { Trader.getChatIdByUid(this.buyerUid) }
    val receiverChatId by lazy { Trader.getChatIdByUid(this.receiverUid) }

    init {
        log.debug("amount / course:\t$amount / $course")
        log.debug("coins:\t$coins")
        log.debug("netFee:\t$netFee")
        log.debug("appFee: \t$appFee")
        log.debug("coinsTotal:\t$coinsTotal")
    }

    private fun update(newDeal: Deal): Deal {
        val self = this
        runBlocking {
            mongodbCollection.updateOneById(self._id, newDeal.copy(updateTime = LocalDateTime.now(ZoneId.of(konfig[timezone]))))
        }

        return this
    }

    fun setPaid(): Deal {
        return this.update(this.copy(paid = true))
    }

    fun delete(): Deal {
        val id = this._id
        runBlocking { mongodbCollection.deleteOneById(id) }

        return this
    }

    companion object {
        private val mongodbCollection = DBA.getCollection<Deal>()

        fun save(deal: Deal) = runBlocking {
            mongodbCollection.save(deal.copy(updateTime = LocalDateTime.now(ZoneId.of(konfig[timezone]))))
        }

        fun findById(id: String) = runBlocking {
            mongodbCollection.findOneById(ObjectId(id))
        }
    }
}
