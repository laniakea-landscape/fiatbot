package neshto.fiatbot.advert

import kotlinx.coroutines.runBlocking
import neshto.fiatbot.DBA
import neshto.fiatbot.currency.Crypta
import neshto.fiatbot.konfig
import neshto.fiatbot.timezone
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.id.StringId
import java.time.LocalDateTime
import java.time.ZoneId

data class Advert (
    @BsonId val _id: Id<Advert> = newId(),
    val active: Boolean = true,
    val traderId: Int? = null,
    val payWay: PayWay? = null,
    val currency: Crypta? = null,
    val course: Float? = null,
    val type: AdvertType? = null,
    val min: Int? = null,
    val max: Int? = null,
    val description: String? = null,
    val updateTime: LocalDateTime? = LocalDateTime.now(ZoneId.of(konfig[timezone]))
) {
    fun addType(type: AdvertType) = this.update(this.copy(type = type))
    fun addCurrency(currency: Crypta) = this.update(this.copy(currency = currency))
    fun addPayWay(payWay: PayWay) = this.update(this.copy(payWay = payWay))
    fun addCourse(course: Float) = this.update(this.copy(course = course))
    fun addLimits(limits: Pair<Int, Int>) = this.update(this.copy(min = limits.first, max = limits.second))
    fun addDescription(description: String) = this.update(this.copy(description = description))

    fun create(): Boolean {
        return if (this.validate()) {
            save(this)
            true
        } else {
            false
        }
    }

    fun validate() = setOf(this.traderId, this.course, this.currency, this.max, this.min, this.payWay, this.type)
        .all { it != null }

    private fun update(newAdvert: Advert): Advert {
        val self = this
        runBlocking {
            mongodbCollection.updateOneById(self._id, newAdvert.copy(updateTime = LocalDateTime.now(ZoneId.of(konfig[timezone]))))
        }
        return newAdvert
    }

    companion object {
        private val mongodbCollection = DBA.getCollection<Advert>()

        fun save(advert: Advert) = runBlocking {
            mongodbCollection.save(advert.copy(updateTime = LocalDateTime.now(ZoneId.of(konfig[timezone]))))
        }

        fun getById(aid: String): Advert? = runBlocking {
            mongodbCollection.findOneById(ObjectId(aid))
        }

        fun getList(search: Advert): List<Advert> {
            val sort = when (search.type) {
                AdvertType.PURCHASE -> ascending(Advert::course)
                else                -> descending(Advert::course)
            }

            return runBlocking {
                mongodbCollection.find(
                    Advert::type eq search.type,
                    Advert::currency eq search.currency,
                    Advert::payWay eq search.payWay,
                    Advert::active eq true
                ).sort(sort).toList()
            }
        }
    }
}