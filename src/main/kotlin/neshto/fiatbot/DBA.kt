package neshto.fiatbot

import org.litote.kmongo.coroutine.*
import org.litote.kmongo.reactivestreams.*

object DBA {

    private val mongodbClient = KMongo.createClient(konfig[mongodb_connection_string]).coroutine
    private val mongodbDatabase = mongodbClient.getDatabase(konfig[mongodb_database])

    fun getDatabase(): CoroutineDatabase = this.mongodbDatabase

    inline fun <reified T: Any> getCollection(): CoroutineCollection<T> = this.getDatabase().getCollection()
}