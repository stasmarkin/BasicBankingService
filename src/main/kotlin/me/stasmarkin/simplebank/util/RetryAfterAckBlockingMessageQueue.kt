package me.stasmarkin.simplebank.util

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Behaves like normal MessageQueue,
 * once you poll an item, you have to ack or nack it
 * nack puts items to a reserved space, where it can't be polled from
 * ack frees space for next messages and returns all nacked items from reserved space to the end of queue
 *
 * Usage:
 *  Producers push items with offer() method only.
 *
 *  Consumers use poll() method for polling and processing.
 *  After processing he has to return item back with method nack()
 *  or confirm successful processing with method ack().
 */
class RetryAfterAckBlockingMessageQueue<T>(size: Int) {
    private val stamper = AtomicLong(0);

    private val capacity = AtomicInteger(size)
    private val lastExamined = AtomicLong(0);

    private val queue = ArrayBlockingQueue<Pair<Long, T>>(size)
    private val reserve = ArrayBlockingQueue<Pair<Long, T>>(size)

    fun poll(): Pair<Long, T>? = queue.poll()

    @Suppress("unused") //params for interface clarity
    fun ack(iteration: Long, t: T) {
        lastExamined.set(stamper.incrementAndGet())
        releaseReserved()
        capacity.incrementAndGet()
    }

    @Deprecated(message = "never use it, it's a hack", level = DeprecationLevel.WARNING)
    fun releaseReserved() {
        reserve.drainTo(queue)
    }

    fun nack(iteration: Long, t: T) {
        if (lastExamined.get() > iteration) {
            queue.add(Pair(stamper.incrementAndGet(), t))
            return
        }

        val el = Pair(stamper.incrementAndGet(), t)
        reserve.add(el)

        if (lastExamined.get() > iteration && reserve.remove(el)) {
            queue.add(Pair(stamper.incrementAndGet(), t))
        }
    }

    fun offer(t: T): Boolean {
        if (capacity.decrementAndGet() < 0) {
            capacity.incrementAndGet()
            return false
        }

        return queue.add(Pair(stamper.incrementAndGet(), t))
    }

    fun size(): Int = queue.size
}