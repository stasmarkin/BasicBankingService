package me.stasmarkin.simplebank.config

data class AccountProcessorConfig(
    val poolSize: Int,
    val balanceQueueSize: Int,
    val transferQueueSize: Int,
    val createQueueSize: Int
)