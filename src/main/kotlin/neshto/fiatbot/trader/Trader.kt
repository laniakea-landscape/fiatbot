package neshto.fiatbot.trader

import com.mongodb.reactivestreams.client.Success
import kotlinx.coroutines.runBlocking
import neshto.fiatbot.DBA
import neshto.fiatbot.Deal
import neshto.fiatbot.Moneybag
import neshto.fiatbot.advert.Advert
import neshto.fiatbot.advert.AdvertType
import neshto.fiatbot.advert.PayWay
import neshto.fiatbot.cryptomanager.ManagerPool
import neshto.fiatbot.currency.Crypta
import neshto.fiatbot.rawmessage.RawMessage
import neshto.fiatbot.screen.InlineAction
import neshto.fiatbot.screen.ScreenName
import neshto.fiatbot.screen.menuaction.MenuActionName
import neshto.fiatbot.FiatBotException
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.Id
import org.litote.kmongo.eq
import org.litote.kmongo.newId
import org.slf4j.LoggerFactory
import kotlin.random.Random
import kotlin.random.asJavaRandom

data class Trader(
    @BsonId val _id: Id<Moneybag> = newId(),
    val uid: Int,
    val username: String?,
    val chatId: Long,
    val link: String = "/${this.generateCodename()}",

    val roles: Set<Role> = setOf(Role.TRADER),
    val moneybags: MutableMap<Crypta, Moneybag> = mutableMapOf(),

    var dealAdvert: Advert? = null,
    var deal: Deal? = null,

    var currentScreenName: ScreenName? = null,
    var possibleMenuActionNames: Set<MenuActionName> = emptySet(),
    var possibleInlineActions: Set<InlineAction> = emptySet(),
    var uncompletedAdvert: Advert? = null,
    var currentSearch: Advert? = null,
    var currentCollocutorUid: Int? = null
) {
    private val log = LoggerFactory.getLogger(this::class.java.simpleName)

    fun register(): Success? {
        val self = this
        return runBlocking {
            if (mongodbCollection.findOne(Trader::uid eq uid) == null) {
                mongodbCollection.insertOne(
                    Trader(
                        uid = self.uid,
                        username = self.username,
                        chatId = self.chatId,
                        currentScreenName = ScreenName.REGISTRATION_COMPLETED
                    )
                )
            } else null
        }
    }

    private fun update(newThis: Trader? = null): Trader {
        val self = newThis ?: this
        runBlocking { mongodbCollection.updateOneById(ObjectId(self._id.toString()), self) }

        return self
    }

    fun setCurrentState(rawMessage: RawMessage): Trader {
        val screenName = rawMessage.screenName
        val menuActionNames = rawMessage.menuActionNames
        val inlineActions = rawMessage.inlineActions

        this.currentScreenName = screenName
        this.possibleMenuActionNames = menuActionNames
        this.possibleInlineActions = inlineActions

        return this.update()
    }

    fun addMoneybag(currency: Crypta): Trader {
        val moneybag = Moneybag.create(currency, this.uid)
        this.moneybags[currency] = moneybag

        return this.update()
    }

    fun <T> updateUncompletedAdvert(update: T): Advert {
        val currentUncompletedAdvert = this.uncompletedAdvert ?: Advert(traderId = this.uid)

        this.uncompletedAdvert = when (update) {
            is AdvertType -> currentUncompletedAdvert.copy(type = update)
            is Crypta     -> currentUncompletedAdvert.copy(currency = update)
            is PayWay     -> currentUncompletedAdvert.copy(payWay = update)
            is Float      -> currentUncompletedAdvert.copy(course = update)
            is Pair<*, *> -> {
                val min = update.first as Int
                val max = update.second as Int
                currentUncompletedAdvert.copy(min = min, max = max)
            }
            is String     -> currentUncompletedAdvert.copy(description = update)
            else          -> throw FiatBotException("Bad uncompleted advert update type!")
        }

        return this.update().uncompletedAdvert as Advert
    }

    fun clearUncompletedAdvert(): Trader {
        this.uncompletedAdvert = null
        return this.update()
    }

    fun setDealAdvert(advert: Advert): Trader {
        this.dealAdvert = advert
        return this.update()
    }
    fun clearDealAdvert(): Trader {
        this.dealAdvert = null
        return this.update()
    }

    fun setDeal(amount: Int): Deal {
        this.dealAdvert ?: throw FiatBotException("dealAdvert is null at setDeat")

        val advertType = this.dealAdvert!!.type as AdvertType
        val advertAuthorId = this.dealAdvert!!.traderId as Int

        this.deal = Deal(
            initiatorUid = this.uid,
            buyerUid = if (advertType == AdvertType.SELL) this.uid else advertAuthorId,
            sellerUid = if (advertType == AdvertType.SELL) advertAuthorId else this.uid,
            currency = this.dealAdvert!!.currency!!,
            payWay = this.dealAdvert!!.payWay!!,
            course = this.dealAdvert!!.course!!,
            amount = amount
        )

        return this.update().deal!!
    }

    fun clearDeal(): Trader {
        this.deal = null
        return this.update()
    }

    fun <T> updateCurrentSearch(update: T, reset: Boolean = false): Advert {
        val currentSearch = (if (reset) null else this.currentSearch) ?: Advert()

        this.currentSearch = when (update) {
            is AdvertType -> currentSearch.copy(type = update)
            is Crypta     -> currentSearch.copy(currency = update)
            is PayWay     -> currentSearch.copy(payWay = update)
            else          -> throw FiatBotException("Bad current search update type!")
        }

        return this.update().currentSearch as Advert
    }

    fun currentSearchBack(): Trader {
        val currentSearch = this.currentSearch as Advert

        this.currentSearch = when {
            currentSearch.payWay != null   -> currentSearch.copy(payWay = null)
            currentSearch.currency != null -> currentSearch.copy(currency = null)
            currentSearch.type != null     -> currentSearch.copy(type = null)
            else                           -> currentSearch
        }

        return this.update()
    }

    fun setCurrentCollocutorUid(cuid: Int): Trader {
        this.currentCollocutorUid = cuid
        return this.update()
    }

    fun clearCurrentCollocutorUid(): Trader {
        this.currentCollocutorUid = null
        return this.update()
    }

    fun blockCoins(currency: Crypta, amount: Float): Trader {
        val moneybag = this.moneybags[currency] ?: throw FiatBotException("$currency moneybag is null")
        moneybag.blocked += amount

        return this.update()
    }

    fun unblockCoins(currency: Crypta, amount: Float): Trader {
        log.debug("traderUid: ${this.uid}\ncurrency: $currency\namount: $amount")
        val moneybag = this.moneybags[currency] ?: throw FiatBotException("$currency moneybag is null")
        moneybag.blocked -= amount
        if (moneybag.blocked < 0f) moneybag.blocked = 0f

        return this.update()
    }

    fun getFunds(currency: Crypta): Float {
        val balance = ManagerPool.getManager(currency).getBalance(this.uid)
        val blocked = this.moneybags[currency]!!.blocked

        log.debug("balance: $balance")
        log.debug("blocked: $blocked")
        return ManagerPool.getManager(currency).getBalance(this.uid) - this.moneybags[currency]!!.blocked
    }

    fun obtainFillUpAddress(currency: Crypta): String =
         ManagerPool.getManager(currency).obtainFillUpAddress(this.uid)

    companion object {
        private val log = LoggerFactory.getLogger(this::class.java)
        private val mongodbCollection = DBA.getCollection<Trader>()

        private fun generateCodename(): String {
            var codename: String
            do {
                codename = Random.asJavaRandom().ints(CODENAME_SIZE, 0, CODENAME_SOURCE.length)
                    .toArray()
                    .map(CODENAME_SOURCE::get)
                    .joinToString("")
            } while (this.getByCodename(codename) != null)
            return codename
        }

        fun getByUid(id: Int): Trader? = runBlocking {
            mongodbCollection.findOne(Trader::uid eq id)
        }

        fun getByCodename(codename: String): Trader? = runBlocking {
            mongodbCollection.findOne(Trader::link eq codename)
        }

        fun getAll(): List<Trader> = runBlocking {
            mongodbCollection.find().toList()
        }

        fun getChatIdByUid(uid: Int): Long {
            val trader = runBlocking {
                mongodbCollection.findOne("{ uid: $uid }, { chatId: 1 }")
            }
            log.debug("chatId: ${trader?.chatId}")
            return trader?.chatId as Long
        }

        private const val CODENAME_SIZE: Long = 6
        private const val CODENAME_SOURCE: String = "abcdef0123456789"

        val GUEST = Trader(uid = -1, chatId = -1, username = "GUEST")
    }
}