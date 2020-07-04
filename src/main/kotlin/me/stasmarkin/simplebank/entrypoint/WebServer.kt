package me.stasmarkin.simplebank.entrypoint

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.github.jasync.sql.db.Connection
import com.github.jasync.sql.db.mysql.MySQLConnectionBuilder.createConnectionPool
import me.stasmarkin.simplebank.*
import me.stasmarkin.simplebank.config.AccountProcessorConfig
import me.stasmarkin.simplebank.config.SqlConfig
import me.stasmarkin.simplebank.config.WebServerConfig
import me.stasmarkin.simplebank.processing.AccountDao
import me.stasmarkin.simplebank.processing.AccountLocks
import me.stasmarkin.simplebank.processing.AccountProcessor
import me.stasmarkin.simplebank.util.ResourceReader
import ratpack.exec.Promise
import ratpack.handling.Chain
import ratpack.jackson.Jackson.json
import ratpack.jackson.Jackson.jsonNode
import ratpack.server.RatpackServer
import ratpack.server.ServerConfig
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

fun main(args: Array<String>) {
    //so dependency very injection

    val webServerConfig = ResourceReader.json<WebServerConfig>("/configs/server/web-server.json")
    val sqlConfig = ResourceReader.json<SqlConfig>("/configs/server/sql.json")
    val accProcessorConfig = ResourceReader.json<AccountProcessorConfig>("/configs/server/acc-processor.json")

    val connection: Connection = createConnectionPool(sqlConfig.toJasyncConfig())
    connection.connect().get()

    val dao = AccountDao(connection)

    val ex = ThreadPoolExecutor(
        accProcessorConfig.poolSize,
        accProcessorConfig.poolSize,
        0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue()
    )
    val locks = AccountLocks()

    val accProcessor = AccountProcessor(
        accProcessorConfig, ex, locks, dao

    )

    WebServer(dao, webServerConfig, accProcessor).start()
}


class WebServer(
    private val dao: AccountDao,
    private val webServerConfig: WebServerConfig,
    private val accProcessor: AccountProcessor
) {

    fun start() {
        val config = ServerConfig.embedded()
            .port(webServerConfig.port)
            .development(false)
        RatpackServer.start { server ->
            server
                .serverConfig(config)
                .registryOf { r -> r.add(jacksonObjectMapper()) }
                .handlers { chain -> definePaths(chain) }
        }
    }

    private fun definePaths(chain: Chain) {
        chain
            .prefix("api/v1") {
                it
                    .post("balance") { ctx ->
                        ctx.parse(BalanceRequest::class.java).then { req ->
                            Promise.async<BalanceResponse> { downstream ->
                                downstream.accept(accProcessor.submit(req));
                            }.then { resp ->
                                ctx.response.status(resp.result.code)
                                ctx.render(json(resp))
                            }
                        }
                    }

                    .post("transfer") { ctx ->
                        ctx.parse(TransferRequest::class.java).then { req ->
                            Promise.async<TransferResponse> { downstream ->
                                downstream.accept(accProcessor.submit(req));
                            }.then { resp ->
                                ctx.response.status(resp.result.code)
                                ctx.render(json(resp))
                            }
                        }
                    }

                    .post("create") { ctx ->
                        ctx.parse(CreateRequest::class.java).then { req ->
                            Promise.async<CreateResponse> { downstream ->
                                downstream.accept(accProcessor.submit(req));
                            }.then { resp ->
                                ctx.response.status(resp.result.code)
                                ctx.render(json(resp))
                            }
                        }
                    }

                    .post("admin/noop") { ctx ->
                        ctx.response.status(200)
                        ctx.render("OK")
                    }

                    .post("admin/init") { ctx ->
                        ctx.parse(jsonNode()).then {
                            val dbSize = it["dbSize"].intValue()
                            val initAmount = it["initAmount"].longValue()
                            dao.init(dbSize, initAmount)
                            ctx.render("OK")
                        }

                    }
            }
    }
}


