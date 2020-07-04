package me.stasmarkin.simplebank.entrypoint

import com.beust.klaxon.Klaxon
import me.stasmarkin.simplebank.BalanceRequest
import me.stasmarkin.simplebank.CreateRequest
import me.stasmarkin.simplebank.TransferRequest
import me.stasmarkin.simplebank.util.ResourceReader
import org.asynchttpclient.AsyncHttpClient
import org.asynchttpclient.BoundRequestBuilder
import org.asynchttpclient.Dsl.asyncHttpClient
import org.asynchttpclient.Response
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random


enum class Scenario { balance, balanceMissed, transferRnd2rnd, transferRnd2one, transferRnd2none, create, createDuplicate }

data class StressConfig(
    val address: String,
    val port: Int,
    val dbSize: Int,
    val init: Init?,
    val concurrency: Int,
    val scenarios: HashMap<String, Int>
) {
    data class Init(
        val reinit: Boolean,
        val initAmount: Long
    )
}

class ScenarioGenerator(val scenarios: HashMap<String, Int>) {
    private val rnd = Random(System.currentTimeMillis())

    private val totalW = scenarios.values.sum()
    private val balanceW = scenarios.getOrDefault("balance", 0)
    private val balanceMissedW = scenarios.getOrDefault("balanceMissed", 0) + balanceW
    private val transferRnd2rndW = scenarios.getOrDefault("transferRnd2rnd", 0) + balanceMissedW
    private val transferRnd2oneW = scenarios.getOrDefault("transferRnd2one", 0) + transferRnd2rndW
    private val transferRnd2noneW = scenarios.getOrDefault("transferRnd2none", 0) + transferRnd2oneW
    private val createW = scenarios.getOrDefault("create", 0) + transferRnd2noneW
    private val createDuplicateW = scenarios.getOrDefault("createDuplicate", 0) + createW

    fun next(): Scenario {
        val v = rnd.nextInt(totalW)
        if (v < balanceW) return Scenario.balance
        if (v < balanceMissedW) return Scenario.balanceMissed
        if (v < transferRnd2rndW) return Scenario.transferRnd2rnd
        if (v < transferRnd2oneW) return Scenario.transferRnd2one
        if (v < transferRnd2noneW) return Scenario.transferRnd2none
        if (v < createW) return Scenario.create
        return Scenario.createDuplicate;
    }
}

class StressClient(
    val config: StressConfig
) {

    private val rnd = Random(System.currentTimeMillis())
    private val generator = ScenarioGenerator(config.scenarios)
    private var http: AsyncHttpClient = asyncHttpClient()

    private val r200 = AtomicLong(0)
    private val r400 = AtomicLong(0)
    private val r500 = AtomicLong(0)
    private val r503 = AtomicLong(0)
    private val rErr = AtomicLong(0)
    private val rOthers = AtomicLong(0)

    fun telemetry(): Map<String, Long> {
        val result = mutableMapOf(
            "r200" to r200.getAndSet(0),
            "r400" to r400.getAndSet(0),
            "r500" to r500.getAndSet(0),
            "r503" to r503.getAndSet(0),
            "rErr" to rErr.getAndSet(0),
            "rOthers" to rOthers.getAndSet(0)
        )
        result["total"] = result.values.sum()
        return result.filter { it.value > 0 }
    }

    fun start() {
        if (config.init != null && config.init.reinit) {
            println("REINIT DB")
            http.preparePost("http://${config.address}:${config.port}/api/v1/admin/init")
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                        {
                            "dbSize": ${config.dbSize},
                            "initAmount": ${config.init.initAmount}
                        }
                        """.trimIndent()
                )
                .execute()
                .get()
        }

        repeat(config.concurrency) {
            startLoop()
            println("LOOP STARTED $it")
        }

        println("CLIENT STARTED")
    }

    private fun startLoop() {
        loopRequests()
    }

    private fun loopRequests(): CompletableFuture<Response> {
        return prepareRndRequest()
            .setReadTimeout(5000)
            .setRequestTimeout(5000)
            .execute()
            .toCompletableFuture()
            .exceptionally { null }
            .thenCompose { prev ->
                when (prev?.statusCode) {
                    200 -> r200.incrementAndGet()
                    400 -> r400.incrementAndGet()
                    500 -> r500.incrementAndGet()
                    503 -> r503.incrementAndGet()
                    null -> rErr.incrementAndGet()
                    else -> rOthers.incrementAndGet()
                }

                loopRequests()
            }
    }

    private fun prepareRndRequest(): BoundRequestBuilder {
        return when (generator.next()) {
            Scenario.balance ->
                http.preparePost("http://${config.address}:${config.port}/api/v1/balance")
                    .setHeader("Content-Type", "application/json")
                    .setBody(Klaxon().toJsonString(BalanceRequest(existingId())))

            Scenario.balanceMissed ->
                http.preparePost("http://${config.address}:${config.port}/api/v1/balance")
                    .setHeader("Content-Type", "application/json")
                    .setBody(Klaxon().toJsonString(BalanceRequest(fakeId())))

            Scenario.transferRnd2rnd ->
                http.preparePost("http://${config.address}:${config.port}/api/v1/transfer")
                    .setHeader("Content-Type", "application/json")
                    .setBody(Klaxon().toJsonString(TransferRequest(existingId(), existingId(), 10)))


            Scenario.transferRnd2one ->
                http.preparePost("http://${config.address}:${config.port}/api/v1/transfer")
                    .setHeader("Content-Type", "application/json")
                    .setBody(Klaxon().toJsonString(TransferRequest(existingId(), 1, 10)))

            Scenario.transferRnd2none ->
                http.preparePost("http://${config.address}:${config.port}/api/v1/transfer")
                    .setHeader("Content-Type", "application/json")
                    .setBody(Klaxon().toJsonString(TransferRequest(existingId(), fakeId(), 10)))

            Scenario.create ->
                http.preparePost("http://${config.address}:${config.port}/api/v1/create")
                    .setHeader("Content-Type", "application/json")
                    .setBody(Klaxon().toJsonString(CreateRequest(createCounter.incrementAndGet(), 777)))

            Scenario.createDuplicate ->
                http.preparePost("http://${config.address}:${config.port}/api/v1/create")
                    .setHeader("Content-Type", "application/json")
                    .setBody(Klaxon().toJsonString(CreateRequest(existingId(), 777)))
        }
    }

    private fun existingId() = rnd.nextInt(0, config.dbSize) + 1
    private fun fakeId() = rnd.nextInt(config.dbSize * 10, config.dbSize * 100)
    private val createCounter = AtomicInteger(rnd.nextInt(config.dbSize * 1000, config.dbSize * 1000 + 1_000_000_000))

}


fun main() {
    val config = ResourceReader.json<StressConfig>("/configs/client/stress.json")

    val client = StressClient(config)
    client.start()


    var nextWakeUp = System.currentTimeMillis()
    while (true) {
        nextWakeUp += 1000;
        sleepTill(nextWakeUp)

        println(client.telemetry())
    }

}

fun sleepTill(nextWakeUp: Long) {
    while (true) {
        val sleepTime = nextWakeUp - System.currentTimeMillis()
        if (sleepTime < 0)
            return
        Thread.sleep(sleepTime)
    }
}
