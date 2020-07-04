package me.stasmarkin.simplebank.processing

import java.util.concurrent.ConcurrentHashMap

class AccountLocks {

    private val locks = ConcurrentHashMap<Int, Long>()

    fun tryLock(id: Int, stamp: Long) : Boolean {
        return locks.getOrPut(id, { stamp }) == stamp

    }

    fun release(id: Int, stamp: Long) : Boolean {
        return locks.remove(id, stamp)
    }
}