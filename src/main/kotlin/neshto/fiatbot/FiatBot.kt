package neshto.fiatbot

import com.pengrad.telegrambot.TelegramBot
import kotlinx.coroutines.runBlocking
import neshto.fiatbot.cryptomanager.ManagerPool
import neshto.fiatbot.currency.Crypta
import neshto.fiatbot.screen.menuaction.MenuAction
import neshto.fiatbot.screen.Screen
import neshto.fiatbot.trader.Trader
import org.slf4j.Logger
import org.slf4j.LoggerFactory

const val BOT_UID: Int = 0
val WALLETS_DIR = "${System.getProperty("user.home")}/wallets/"

class FiatBot(token: String): TelegramBot(token) {
    private val log: Logger = LoggerFactory.getLogger(this::class.java.simpleName)
    private var _config: Config = this.loadConfig()
    val config: Config
        get() = this._config

    init {
        log.trace("Currency managers initialization started")
        this.initCurrencyManagers()
        log.info("Currency managers initialization completed")
    }

    private fun loadConfig(): Config {
        log.trace("Config loading started")
        var config: Config
        do {
            config = Config(
                screens = Screen.getAll().map { it.name to it }.toMap(),
                menuActions = MenuAction.getAll().map { it.name to it }.toMap(),
                moneybags = Moneybag.getAll().map { it.currency to it }.toMap()
            )
        } while (!this.validateConfig(config))

        log.trace("Config loaded successfully")
        return config
    }

    private fun validateConfig(config: Config): Boolean {
        var valid = true
        Crypta.values().forEach {
            config.moneybags.getOrElse(
                it
            ) {
                valid = false
                Moneybag.create(it, BOT_UID)
            }
        }

        if (valid) {
            log.trace("Config validation succeed")
        } else {
            log.warn("Config validation failed")
        }
        return valid
    }

    fun reloadConfig() {
        this._config = this.loadConfig()
    }

    private fun initCurrencyManagers() {
        this.config.moneybags.forEach { (currency, moneybag) ->
            ManagerPool.getManager(currency).addWallet(BOT_UID, moneybag.data)
            log.trace("Application $currency wallet added")
        }
        log.trace("Application wallets initialization complete")
        Trader.getAll().forEach { trader ->
            log.trace("trader:", trader)
            val uid = trader.uid
            trader.moneybags.forEach { (currency, moneybag ) ->
                ManagerPool.getManager(currency).addWallet(uid, moneybag.data)
                log.trace("Trader #${trader.uid} $currency wallet added")
            }
        }
        log.trace("Traders wallets initialization complete")
        Crypta.values().forEach {
            ManagerPool.getManager(it).start()
            log.info("$it manager started")
        }
        log.info("Currency managers initialization complete")
    }
}