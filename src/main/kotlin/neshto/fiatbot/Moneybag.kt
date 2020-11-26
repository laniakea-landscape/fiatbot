package neshto.fiatbot

import kotlinx.coroutines.runBlocking
import neshto.fiatbot.currency.Crypta
import neshto.fiatbot.cryptomanager.Manager
import neshto.fiatbot.cryptomanager.ManagerPool
import org.bson.codecs.pojo.annotations.BsonId
import org.litote.kmongo.Id
import org.litote.kmongo.newId

data class Moneybag(
    @BsonId val _id: Id<Moneybag> = newId(),
    val currency: Crypta,
    val data: Map<String, Any?>,
    var blocked: Float = 0f
) {
    private val manager: Manager = ManagerPool.getManager(this.currency)

    companion object {
        private val mongodbCollection = DBA.getCollection<Moneybag>()

        fun getAll(): List<Moneybag> = runBlocking {
            mongodbCollection.find().toList()
        }

        fun create(currency: Crypta, uid: Int): Moneybag = runBlocking {
            val provider = ManagerPool.getManager(currency)
            val data = provider.createNewWallet(uid)
            ManagerPool.getManager(currency).addWallet(uid, data)

            val moneybag = Moneybag(currency = currency, data = data)
            if (uid == BOT_UID) {
                mongodbCollection.insertOne(moneybag)
            }
            moneybag
        }
    }
}