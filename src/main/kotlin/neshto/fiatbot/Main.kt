package neshto.fiatbot

import com.natpryce.konfig.*
import com.pengrad.telegrambot.UpdatesListener
import com.pengrad.telegrambot.request.GetUpdates
import neshto.fiatbot.updateprocessor.UpdateProcessor
import org.slf4j.LoggerFactory
import java.io.File

const val NULL_MESSAGE = "<null message>"

val konfig = ConfigurationProperties.systemProperties() overriding
        EnvironmentVariables() overriding
        ConfigurationProperties.fromOptionalFile(File("/etc/fiatbot.properties")) overriding
        ConfigurationProperties.fromOptionalFile(File(System.getProperty("user.home"),".fiatbot.properties")) overriding
        ConfigurationProperties.fromResource("konfig.properties")

val timezone = Key("timezone", stringType)

val mongodb_connection_string = Key("mongodb.connection-string", stringType)
val mongodb_database = Key("mongodb.database", stringType)

val passphrase = Key("passphrase", stringType)
val token = Key("token", stringType)

val cryptocompare_api_key = Key("cryptocompare_api_key", stringType)
val cryptocompare_api_call_timeout = Key("cryptocompare_api_call_timeout", intType)

val transfer_fee = Key("transfer_fee", doubleType)


fun main(args: Array<String>) {

    val logger = LoggerFactory.getLogger("MAIN")
    logger.info("Starting...")

    val bot = FiatBot(konfig[token])

    logger.info("Creating listener...")
    val updateListener = UpdatesListener { updates ->
        updates.forEach {
            logger.info(it.toString())
            try {
                UpdateProcessor(it, bot).process()
            } catch (exc: Throwable) {
                logger.error("${exc.message ?: exc::class.java.simpleName}\n\n${exc.stackTrace.joinToString("\n")}")
            }
        }
        UpdatesListener.CONFIRMED_UPDATES_ALL
    }

    logger.info("Launching listening loop...")
    bot.setUpdatesListener(
        updateListener,
        GetUpdates()
            .offset(0)
            .limit(10)
            .timeout(60)
            .allowedUpdates("message", "callback_query")
    )
}

