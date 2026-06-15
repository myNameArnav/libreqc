package dev.libreqc.bmap

data class BmapAddress(
    val functionBlock: Int,
    val function: Int,
) {
    init {
        require(functionBlock in 0..255) { "Function block must fit in one byte" }
        require(function in 0..255) { "Function must fit in one byte" }
    }
}

enum class BmapOperator(val code: Int) {
    Set(0),
    Get(1),
    SetGet(2),
    Status(3),
    Error(4),
    Start(5),
    Result(6),
    Processing(7),
    Unknown(-1),
    ;

    companion object {
        fun fromCode(code: Int): BmapOperator =
            entries.firstOrNull { it.code == code } ?: Unknown
    }
}

enum class BmapError(val code: Int) {
    Unknown(0),
    InvalidLength(1),
    Checksum(2),
    FunctionBlockNotSupported(3),
    FunctionNotSupported(4),
    OperatorNotSupported(5),
    InvalidData(6),
    DataUnavailable(7),
    Runtime(8),
    Timeout(9),
    InvalidState(10),
    InvalidTransition(15),
    InsecureTransport(20),
    Unrecognized(-1),
    ;

    companion object {
        fun fromCode(code: Int): BmapError =
            entries.firstOrNull { it.code == code } ?: Unrecognized
    }
}

class BmapFrame(
    val address: BmapAddress,
    val operator: BmapOperator,
    val operatorCode: Int,
    val payload: ByteArray,
) {
    val errorCode: Int?
        get() = payload.firstOrNull()?.toInt()?.and(0xff)
            ?.takeIf { operator == BmapOperator.Error }

    val error: BmapError?
        get() = errorCode?.let(BmapError::fromCode)
}

class BmapDecodingException(
    val expectedFrameLength: Int,
    val actualByteCount: Int,
) : IllegalStateException(
    "Truncated BMAP frame: expected $expectedFrameLength bytes, got $actualByteCount",
)

class BmapRequest(
    val address: BmapAddress,
    val operator: BmapOperator,
    payload: ByteArray = byteArrayOf(),
) {
    val payload: ByteArray = payload.copyOf()

    init {
        require(operator != BmapOperator.Unknown) { "Cannot encode an unknown operator" }
    }

    fun toByteArray(): ByteArray = BmapPackets.encode(address, operator, payload)

    fun accepts(response: BmapFrame): Boolean {
        if (response.address != address) {
            return false
        }
        if (response.operator == BmapOperator.Error) {
            return true
        }
        return when (operator) {
            BmapOperator.Get -> response.operator == BmapOperator.Status
            BmapOperator.Start ->
                response.operator == BmapOperator.Processing ||
                    response.operator == BmapOperator.Result
            BmapOperator.Set, BmapOperator.SetGet ->
                response.operator == BmapOperator.Status ||
                    response.operator == BmapOperator.Processing ||
                    response.operator == BmapOperator.Result
            else -> false
        }
    }
}

object BmapPackets {
    @JvmStatic
    @JvmOverloads
    fun get(address: BmapAddress, payload: ByteArray = byteArrayOf()): ByteArray =
        encode(address, BmapOperator.Get, payload)

    @JvmStatic
    @JvmOverloads
    fun start(address: BmapAddress, payload: ByteArray = byteArrayOf()): ByteArray =
        encode(address, BmapOperator.Start, payload)

    @JvmStatic
    fun encode(
        address: BmapAddress,
        operator: BmapOperator,
        payload: ByteArray,
    ): ByteArray {
        require(operator != BmapOperator.Unknown) { "Cannot encode an unknown operator" }
        require(payload.size <= 255) { "Payload must fit in the one-byte length field" }
        return byteArrayOf(
            address.functionBlock.toByte(),
            address.function.toByte(),
            operator.code.toByte(),
            payload.size.toByte(),
            *payload,
        )
    }
}

object BmapDiagnostics {
    @JvmStatic
    fun hex(bytes: ByteArray): String =
        bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }

    @JvmStatic
    fun describe(frame: BmapFrame): String {
        val operator = frame.operator.name.uppercase()
        val error =
            frame.error?.let { " ${it.name}(${frame.errorCode})" }
                ?: ""
        return "[${frame.address.functionBlock}.${frame.address.function}] " +
            "$operator$error payload=${hex(frame.payload)}"
    }
}

class BmapStreamDecoder {
    private var pending = ByteArray(0)

    fun feed(bytes: ByteArray): List<BmapFrame> {
        pending += bytes
        val frames = mutableListOf<BmapFrame>()
        while (pending.size >= HEADER_SIZE) {
            val payloadLength = pending[3].toInt() and 0xff
            val frameLength = HEADER_SIZE + payloadLength
            if (pending.size < frameLength) {
                break
            }

            val operatorCode = pending[2].toInt() and OPERATOR_MASK
            frames += BmapFrame(
                address = BmapAddress(
                    functionBlock = pending[0].toInt() and 0xff,
                    function = pending[1].toInt() and 0xff,
                ),
                operator = BmapOperator.fromCode(operatorCode),
                operatorCode = operatorCode,
                payload = pending.copyOfRange(HEADER_SIZE, frameLength),
            )
            pending = pending.copyOfRange(frameLength, pending.size)
        }
        return frames
    }

    fun finish() {
        pendingError()?.let { throw it }
    }

    fun discardPending(): BmapDecodingException? {
        val error = pendingError()
        pending = ByteArray(0)
        return error
    }

    private fun pendingError(): BmapDecodingException? {
        if (pending.isEmpty()) {
            return null
        }
        val expectedFrameLength =
            if (pending.size < HEADER_SIZE) {
                HEADER_SIZE
            } else {
                HEADER_SIZE + (pending[3].toInt() and 0xff)
            }
        return BmapDecodingException(
            expectedFrameLength = expectedFrameLength,
            actualByteCount = pending.size,
        )
    }

    private companion object {
        const val HEADER_SIZE = 4
        const val OPERATOR_MASK = 0x0f
    }
}
