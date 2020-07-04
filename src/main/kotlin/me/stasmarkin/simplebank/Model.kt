package me.stasmarkin.simplebank


data class BalanceRequest(
    val accId: Int
)

data class TransferRequest(
    val senderId: Int,
    val recipientId: Int,
    val amount: Long
)

data class CreateRequest(
    val accId: Int,
    val amount: Long
)

enum class Result(val code: Int) {
    SUCCESS(200),
    INTERNAL_ERROR(500),
    OVERLOADED(503),
    ACC_NOT_EXISTS(400),
    WRONG_AMOUNT(400),
    ACC_ALREADY_EXISTS(400)
}

data class BalanceResponse(
    val result: Result,
    val accId: Int? = null,
    val balance: Long? = null
) {
    companion object {
        fun success(accId: Int, balance: Long) =
            BalanceResponse(Result.SUCCESS, accId, balance)

        fun accNotExists() = BalanceResponse(Result.ACC_NOT_EXISTS)
        fun overloaded() = BalanceResponse(Result.OVERLOADED)
    }
}

data class TransferResponse(
    val result: Result,
    val senderAmount: Long? = null,
    val recipientAmount: Long? = null
) {
    companion object {
        fun success(senderAmount: Long, recipientAmount: Long) =
            TransferResponse(Result.SUCCESS, senderAmount, recipientAmount)

        fun wrongAmount() = TransferResponse(Result.WRONG_AMOUNT)
        fun accNotExists() = TransferResponse(Result.ACC_NOT_EXISTS)
        fun overloaded() = TransferResponse(Result.OVERLOADED)

    }
}


data class CreateResponse(
    val result: Result
) {
    companion object {
        fun success() = CreateResponse(Result.SUCCESS)
        fun alreadyExist() = CreateResponse(Result.ACC_ALREADY_EXISTS)
        fun wrongAmount() = CreateResponse(Result.WRONG_AMOUNT)
        fun overloaded() = CreateResponse(Result.OVERLOADED)
    }
}