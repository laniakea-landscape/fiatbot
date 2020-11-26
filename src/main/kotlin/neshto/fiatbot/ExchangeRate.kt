package neshto.fiatbot

import com.google.gson.Gson
import neshto.fiatbot.currency.Crypta
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit


object ExchangeRate {

    private val log = LoggerFactory.getLogger("ExchangeRate")

    private val apiKey: String = konfig[cryptocompare_api_key]
    private val rates: MutableMap<Crypta, Float> = mutableMapOf()
    private var time: LocalDateTime = LocalDateTime.now(ZoneId.of(konfig[timezone]))

    private val uri = URI("https://min-api.cryptocompare.com/data/pricemulti?fsyms=${Crypta.values().joinToString(",")}&tsyms=RUB&apiKey=${this.apiKey}")

    private fun loadRates() {
        val rates = Gson().fromJson(uri.toURL().readText(), Map::class.java)
        log.info("Exchange rates loaded: $rates")
        rates.forEach { this.rates[Crypta.valueOf(it.key.toString())] = ((it.value as Map<*, *>)["RUB"] as Double).toFloat() }
        this.time = LocalDateTime.now(ZoneId.of(konfig[timezone]))
    }

    fun getCourse(currency: Crypta): Float {
        if (
            this.rates.isEmpty()
            || (this.time.until(LocalDateTime.now(ZoneId.of(konfig[timezone])), ChronoUnit.SECONDS)) >= konfig[cryptocompare_api_call_timeout]
        ) loadRates()

        return this.rates[currency]!!
    }
}