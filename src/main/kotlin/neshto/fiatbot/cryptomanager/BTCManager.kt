package neshto.fiatbot.cryptomanager

import neshto.fiatbot.BOT_UID
import neshto.fiatbot.currency.Crypta
import neshto.fiatbot.konfig
import neshto.fiatbot.passphrase
import neshto.fiatbot.transfer_fee

import org.bitcoinj.core.*
import org.bitcoinj.core.listeners.DownloadProgressTracker
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.params.TestNet3Params
import org.bitcoinj.script.Script
import org.bitcoinj.store.SPVBlockStore
import org.bitcoinj.wallet.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import kotlin.math.roundToLong


class BTCManager : Manager(Crypta.BTC) {

    private val log = LoggerFactory.getLogger(this::class.java.simpleName)

    private val networkParams = TestNet3Params()
    private val context = Context(this.networkParams)
    private val keyChainGroupStructure = KeyChainGroupStructure.DEFAULT
    private val outputScriptType = Script.ScriptType.P2PKH

    private val walletStore: MutableMap<Int, Wallet> = mutableMapOf()

    private val chainFile = File(this.getFilesDir(), "chain.spvchain")
    private val store = SPVBlockStore(this.networkParams, this.chainFile)
//    private val store = MemoryBlockStore(networkParams)
    private val chain = BlockChain(this.networkParams, this.store)
    private val peerGroup = run {
        val peerGroup = PeerGroup(networkParams, chain)
        peerGroup.addPeerDiscovery(DnsDiscovery(this.networkParams))
        peerGroup
    }

    override fun start() {
        Context.propagate(this.context)

        this.peerGroup.start()
        val listener = DownloadProgressTracker()
        this.peerGroup.startBlockChainDownload(listener)
        listener.await()
    }

    protected fun finalize() {
        Context.propagate(this.context)

        this.peerGroup.stop()
        this.walletStore.forEach {
            val uid = it.key
            val wallet = it.value
            wallet.saveToFile(this.getWalletFile(uid))
        }
        this.store.close()

        log.debug("BTCManager finalized")
    }

    override fun addWallet(uid: Int, data: Map<String, Any?>) {
        Context.propagate(this.context)

        val wallet = when (this.getWalletFile(uid).exists()) {
            true -> this.loadWallet(uid)
            false -> this.restoreWallet(uid, data)
        }
        this.peerGroup.addWallet(wallet)
        this.chain.addWallet(wallet)
        this.walletStore[uid] = wallet
    }

    private fun getWalletFile(uid: Int) = File(getFilesDir(), "$uid.wallet")

    override fun createNewWallet(uid: Int): Map<String, Any?> {
        Context.propagate(this.context)

        val kcgBuilder = KeyChainGroup.builder(this.networkParams, this.keyChainGroupStructure)
        kcgBuilder.fromRandom(this.outputScriptType)

        val wallet = Wallet(networkParams, kcgBuilder.build())
        val seed = wallet.keyChainSeed

        wallet.encrypt(konfig[passphrase])
        wallet.freshReceiveKey()
        wallet.saveToFile(this.getWalletFile(uid))

        return mapOf(
            "mnemonicCode" to seed.mnemonicCode!!.joinToString(" "),
            "creationTime" to seed.creationTimeSeconds
        )
    }

    private fun restoreWallet(uid: Int, data: Map<String, Any?>): Wallet {
        Context.propagate(this.context)

        val mnemonicCode = data["mnemonicCode"] as String
        val creationTime = data["creationTime"] as Long
        val seed = DeterministicSeed(mnemonicCode, null, "", creationTime)
        val kcgBuilder = KeyChainGroup.builder(this.networkParams, this.keyChainGroupStructure)
        kcgBuilder.fromSeed(seed, this.outputScriptType)

        val wallet = Wallet(this.networkParams, kcgBuilder.build())
        wallet.encrypt(konfig[passphrase])
        wallet.freshReceiveKey()
        wallet.saveToFile(this.getWalletFile(uid))

        return wallet
    }

    private fun loadWallet(uid: Int): Wallet {
        Context.propagate(this.context)

        val walletStream = FileInputStream(this.getWalletFile(uid))
        val wallet: Wallet = try {
            val proto = WalletProtobufSerializer.parseToProto(walletStream)
            val serializer = WalletProtobufSerializer()
            serializer.readWallet(this.networkParams, null, proto)
        } catch (exc: UnreadableWalletException) {
            log.error("${exc::class.java.canonicalName} ${exc.message}")
            throw exc
        } finally {
            walletStream.close()
        }

        if (!wallet.isEncrypted) {
            wallet.encrypt(konfig[passphrase])
        }
        wallet.autosaveToFile(this.getWalletFile(uid), 30, TimeUnit.SECONDS, null)

        return wallet
    }

    override fun lookForWallet(uid: Int): Boolean = walletStore.containsKey(uid)

    override fun getBalance(uid: Int): Float {
        Context.propagate(this.context)

        return this.walletStore[uid]!!.balance.value / SATOSHI_MULTIPLIER
    }

    override fun getStringBalance(uid: Int): String {
        Context.propagate(this.context)

        return this.walletStore[uid]!!.balance.toFriendlyString()
    }

    override fun obtainFillUpAddress(uid: Int): String {
        Context.propagate(this.context)

        return this.walletStore[uid]!!.currentReceiveAddress().toString()
    }

    override fun calculateFee(toId: Int, coinsAmount: Float): Float {
//        val fee = this.createTransaction(toId, coinsAmount).fee
//        return fee.value / SATOSHI_MULTIPLIER
        return 0.00001f
    }

    override fun send(fromId: Int, toId: Int, coinsAmount: Float): String {
        Context.propagate(this.context)

        val fromWallet = this.walletStore[fromId]!!

        val tx = this.createTransaction(toId, coinsAmount)
        log.info("Transaction fee = ${tx.fee}")

        val request = SendRequest.forTx(tx)
        request.aesKey = fromWallet.keyCrypter!!.deriveKey(konfig[passphrase])

        fromWallet.completeTx(request)
        fromWallet.commitTx(request.tx)
        this.peerGroup.broadcastTransaction(request.tx)

        return tx.txId.toString()
    }

    private fun createTransaction(toId: Int, coinsAmount: Float): Transaction {
        val toWallet = this.walletStore[toId]!!
        val appWallet = this.walletStore[BOT_UID]!!

        val tx = Transaction(this.networkParams)
        tx.addOutput(Coin.valueOf((coinsAmount * SATOSHI_MULTIPLIER).roundToLong()), toWallet.currentReceiveAddress())
        tx.addOutput(
            Coin.valueOf((coinsAmount * konfig[transfer_fee] * SATOSHI_MULTIPLIER).roundToLong()),
            appWallet.currentReceiveAddress()
        )

        return tx
    }

    companion object {
        private const val SATOSHI_MULTIPLIER = 100_000_000f
    }
}