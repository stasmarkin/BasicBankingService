package me.stasmarkin.simplebank.processing

import com.github.jasync.sql.db.Connection
import java.util.concurrent.CompletableFuture

class AccountDao(
    private val sql: Connection
) {
    fun balance(accId: Int): CompletableFuture<Long?> {
        return sql
            .sendPreparedStatement(
                """
                    select  balance
                    from    account
                    where   id = ?
                """.trimIndent(), listOf(accId)
            )
            .thenApply<Long?> { rs ->
                if (rs.rows.size == 0)
                    return@thenApply null
                return@thenApply rs.rows.first().getLong("balance")
            }
    }

    fun updateBalance(accId: Int, oldAmount: Long, newAmount: Long): CompletableFuture<Void> {
        return sql
            .sendPreparedStatement(
                """
                    update  account
                    set     balance = ?
                    where   id = ? 
                        and balance = ?
                    
                """.trimIndent(), listOf(newAmount, accId, oldAmount)
            )
            .thenAccept { assert(it.rowsAffected == 1L) { "Balance mismatch" } }
    }

    fun create(accId: Int, amount: Long): CompletableFuture<Void> {
        return sql
            .sendPreparedStatement(
                """
                    insert into     account
                                    (id, balance)
                    values          ( ?,       ?)
                """.trimIndent(), listOf(accId, amount)
            )
            .thenAccept { assert(it.rowsAffected == 1L) { "Race condition" } }
    }

    fun init(dbSize: Int, initAmount: Long) {
        sql.sendPreparedStatement(
            """
            drop table if exists account
        """.trimIndent()
        ).get()

        sql.sendPreparedStatement(
            """
            create table account (
                id          int     not null,
                balance     bigint  not null,
                primary key(id)
            )
        """.trimIndent()
        ).get()

        generateSequence(1) { it + 1 }
            .map { create(it, initAmount) }
            .take(dbSize)
            .chunked(100)
            .forEach {
                CompletableFuture.allOf(*it.toTypedArray()).get()
            }
    }

}