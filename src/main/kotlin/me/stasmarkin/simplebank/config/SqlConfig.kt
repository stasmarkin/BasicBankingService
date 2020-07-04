package me.stasmarkin.simplebank.config

import com.github.jasync.sql.db.ConnectionPoolConfiguration
import com.github.jasync.sql.db.SSLConfiguration
import com.github.jasync.sql.db.interceptor.QueryInterceptor
import com.github.jasync.sql.db.util.ExecutorServiceUtils
import com.github.jasync.sql.db.util.NettyUtils
import io.netty.buffer.ByteBufAllocator
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.EventLoopGroup
import io.netty.util.CharsetUtil
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.nio.charset.Charset
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

data class SqlConfig(
    val host: String = "localhost",
    val port: Int = 5432,
    val database: String? = null,
    val username: String = "dbuser",
    val password: String? = null,
    val maxActiveConnections: Int = 1,
    val maxIdleTime: Long = TimeUnit.MINUTES.toMillis(1),
    val maxPendingQueries: Int = Int.MAX_VALUE,
    val connectionValidationInterval: Long = 5000,
    val connectionCreateTimeout: Long = 5000,
    val connectionTestTimeout: Long = 5000,
    val queryTimeout: Long? = null,
    val maximumMessageSize: Int = 16777216,
    val applicationName: String? = null,
    val maxConnectionTtl: Long? = null
) {
    fun toJasyncConfig(
        eventLoopGroup: EventLoopGroup = NettyUtils.DefaultEventLoopGroup,
        executionContext: Executor = ExecutorServiceUtils.CommonPool,
        coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
        ssl: SSLConfiguration = SSLConfiguration(),
        charset: Charset = CharsetUtil.UTF_8,
        allocator: ByteBufAllocator = PooledByteBufAllocator.DEFAULT,
        interceptors: List<Supplier<QueryInterceptor>> = emptyList()
    ) = ConnectionPoolConfiguration(
        host = host,
        port = port,
        database = database,
        username = username,
        password = password,
        maxActiveConnections = maxActiveConnections,
        maxIdleTime = maxIdleTime,
        maxPendingQueries = maxPendingQueries,
        connectionValidationInterval = connectionValidationInterval,
        connectionCreateTimeout = connectionCreateTimeout,
        connectionTestTimeout = connectionTestTimeout,
        queryTimeout = queryTimeout,
        eventLoopGroup = eventLoopGroup,
        executionContext = executionContext,
        coroutineDispatcher = coroutineDispatcher,
        ssl = ssl,
        charset = charset,
        maximumMessageSize = maximumMessageSize,
        allocator = allocator,
        applicationName = applicationName,
        interceptors = interceptors,
        maxConnectionTtl = maxConnectionTtl
    )
}