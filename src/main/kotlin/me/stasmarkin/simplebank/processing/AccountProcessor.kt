package me.stasmarkin.simplebank.processing

import me.stasmarkin.simplebank.*
import me.stasmarkin.simplebank.config.AccountProcessorConfig
import me.stasmarkin.simplebank.util.RetryAfterAckBlockingMessageQueue
import me.stasmarkin.simplebank.util.Smelly
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletableFuture.completedFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

class AccountProcessor(
    config: AccountProcessorConfig,
    private val ex: ThreadPoolExecutor,
    private val locks: AccountLocks,
    private val dao: AccountDao
) {

    private val parking = Object()
    private val threadsSleeping = AtomicInteger(0)

    private val balanceQueue =
        RetryAfterAckBlockingMessageQueue<Pair<BalanceRequest, CompletableFuture<BalanceResponse>>>(config.balanceQueueSize)
    private val transferQueue =
        RetryAfterAckBlockingMessageQueue<Pair<TransferRequest, CompletableFuture<TransferResponse>>>(config.transferQueueSize)
    private val createQueue =
        RetryAfterAckBlockingMessageQueue<Pair<CreateRequest, CompletableFuture<CreateResponse>>>(config.createQueueSize)

    init {
        repeat(ex.maximumPoolSize) {
            ex.submit(this::loop)
        }
    }

    fun submit(req: BalanceRequest): CompletableFuture<BalanceResponse> {
        return submit(balanceQueue, req, { BalanceResponse.overloaded() })
    }

    fun submit(req: TransferRequest): CompletableFuture<TransferResponse> {
        return submit(transferQueue, req, { TransferResponse.overloaded() })
    }

    fun submit(req: CreateRequest): CompletableFuture<CreateResponse> {
        return submit(createQueue, req, { CreateResponse.overloaded() })
    }

    private fun <REQ, RES> submit(
        q: RetryAfterAckBlockingMessageQueue<Pair<REQ, CompletableFuture<RES>>>,
        req: REQ, overloadedBuilder: () -> RES
    ): CompletableFuture<RES> {
        val result = CompletableFuture<RES>()

        if (threadsSleeping.get() > 0) {
            synchronized(parking) { parking.notifyAll() }
        }

        if (!q.offer(Pair(req, result)))
            return completedFuture(overloadedBuilder())

        if (threadsSleeping.get() > 0) {
            synchronized(parking) { parking.notifyAll() }
        }

        return result;
    }

    private fun loop() {
        loop@ while (true) {
            if (balanceQueue.size() + transferQueue.size() + createQueue.size() == 0) {
                threadsSleeping.incrementAndGet()
                if (balanceQueue.size() + transferQueue.size() + createQueue.size() == 0) {
                    synchronized(parking) { parking.wait() }
                }
                threadsSleeping.decrementAndGet();
            }

            handle(balanceQueue, this::handleBalanceRequest)
            handle(transferQueue, this::handleTransferRequest)
            handle(createQueue, this::handleCreateRequest)
        }
    }

    private fun <T> handle(queueRetriable: RetryAfterAckBlockingMessageQueue<T>, handler: (T) -> CompletableFuture<Boolean>) {
        while (true) {
            val (stamp, item) = queueRetriable.poll() ?: break
            try {
                handler(item)
                    .exceptionally { false }
                    .thenAccept { handled ->
                        if (handled) {
                            queueRetriable.ack(stamp, item)
                            releaseMutuallyReserved()


                            if (threadsSleeping.get() > 0) {
                                synchronized(parking) { parking.notifyAll() }
                            }
                            return@thenAccept
                        }

                        queueRetriable.nack(stamp, item)
                    }
            } catch (e: Exception) {
                queueRetriable.nack(stamp, item)
            }
        }
    }

    @Smelly("""
    since all queues share some locks, some items could
    be reserved due to mutual blocks between different queues
    
    For example:
       * q1 locks l1
       * q2 if full and every item require lock l1
       * q2 moves all items to reversed space
       * q1 release l1 and acks only q1
       * q2 is infinitely blocked
    
    so, we need to release mutually blocked items between queues   
    """)
    private fun releaseMutuallyReserved() {
        transferQueue.releaseReserved()
        createQueue.releaseReserved()
    }

    private fun handleBalanceRequest(pair: Pair<BalanceRequest, CompletableFuture<BalanceResponse>>): CompletableFuture<Boolean> {
        try {
            val accId = pair.first.accId
            return dao.balance(accId)
                .thenApply {
                    if (it == null) BalanceResponse.accNotExists()
                    else BalanceResponse.success(accId, it)
                }
                .whenComplete { resp, err ->
                    if (resp != null) {
                        pair.second.complete(resp)
                        return@whenComplete
                    }

                    if (err != null) {
                        pair.second.completeExceptionally(err)
                        return@whenComplete
                    }

                    pair.second.completeExceptionally(RuntimeException("Never gonna happen"))
                }
                .thenApply { true }

        } catch (e: Exception) {
            pair.second.completeExceptionally(e);
            return completedFuture(true)
        }
    }

    private val stamps = AtomicLong()

    private fun handleTransferRequest(pair: Pair<TransferRequest, CompletableFuture<TransferResponse>>): CompletableFuture<Boolean> {
        val (senderId, recipientId, amount) = pair.first
        val lower = min(senderId, recipientId)
        val upper = max(senderId, recipientId)
        val stamp = stamps.incrementAndGet()

        try {
            if (amount <= 0) {
                pair.second.complete(TransferResponse.wrongAmount())
                return completedFuture(true)
            }

            if (senderId == recipientId) {
                return dao
                    .balance(senderId)
                    .thenApply {
                        if (it == null) TransferResponse.accNotExists()
                        else TransferResponse.success(it, it)
                    }
                    .whenComplete { resp, err ->
                        if (resp != null) {
                            pair.second.complete(resp)
                            return@whenComplete
                        }

                        if (err != null) {
                            pair.second.completeExceptionally(err)
                            return@whenComplete
                        }

                        pair.second.completeExceptionally(RuntimeException("Never gonna happen"))
                    }
                    .thenApply { true }
            }

            val locked: Boolean = (inline@{
                if (locks.tryLock(lower, stamp)) {
                    if (locks.tryLock(upper, stamp))
                        return@inline true
                    locks.release(lower, stamp)
                }
                return@inline false
            })()

            if (!locked)
                return completedFuture(false)

            try {
                return dao.balance(senderId)
                    .thenCombine(dao.balance(recipientId)) { senderBal, recipientBal -> Pair(senderBal, recipientBal) }
                    .thenCompose {
                        val (senderBal, recipientBal) = it
                        if (senderBal == null || recipientBal == null)
                            return@thenCompose completedFuture(TransferResponse.accNotExists())

                        if (senderBal - amount < 0)
                            return@thenCompose completedFuture(TransferResponse.wrongAmount())

                        if (sumCauseOverflow(recipientBal, amount))
                            return@thenCompose completedFuture(TransferResponse.wrongAmount())

                        return@thenCompose dao.updateBalance(senderId, senderBal, senderBal - amount)
                            .thenCompose { dao.updateBalance(recipientId, recipientBal, recipientBal + amount) }
                            .thenApply {
                                TransferResponse.success(senderBal - amount, recipientBal + amount)
                            }
                    }
                    .whenComplete { resp, err ->
                        locks.release(upper, stamp)
                        locks.release(lower, stamp)

                        if (resp != null) {
                            pair.second.complete(resp)
                            return@whenComplete
                        }

                        if (err != null) {
                            pair.second.completeExceptionally(err)
                            return@whenComplete
                        }

                        pair.second.completeExceptionally(RuntimeException("Never gonna happen"))
                    }
                    .thenApply { true }

            } catch (e: Exception) {
                locks.release(upper, stamp)
                locks.release(lower, stamp)
                pair.second.completeExceptionally(e)
                return completedFuture(true)
            }

        } catch (e: Exception) {
            pair.second.completeExceptionally(e)
            return completedFuture(true)
        }
    }

    private fun sumCauseOverflow(x: Long, y: Long): Boolean {
        val r = x + y
        return (x xor r) and (y xor r) < 0
    }

    private fun handleCreateRequest(pair: Pair<CreateRequest, CompletableFuture<CreateResponse>>): CompletableFuture<Boolean> {
        val (accId, amount) = pair.first
        val stamp = stamps.incrementAndGet()

        if (amount <= 0) {
            pair.second.complete(CreateResponse.wrongAmount())
            return completedFuture(true)
        }

        if (!locks.tryLock(accId, stamp))
            return completedFuture(false)

        try {
            return dao.balance(accId)
                .thenCompose { balance ->
                    if (balance != null)
                        return@thenCompose completedFuture(CreateResponse.alreadyExist())

                    return@thenCompose dao.create(accId, amount)
                        .thenApply { CreateResponse.success() }
                }
                .whenComplete { resp, err ->
                    locks.release(accId, stamp)

                    if (resp != null) {
                        pair.second.complete(resp)
                        return@whenComplete
                    }

                    if (err != null) {
                        pair.second.completeExceptionally(err)
                        return@whenComplete
                    }

                    pair.second.completeExceptionally(RuntimeException("Never gonna happen"))
                }
                .thenApply { true }

        } catch (e: Exception) {
            locks.release(accId, stamp)
            pair.second.completeExceptionally(e)
            return completedFuture(true)
        }
    }


}


