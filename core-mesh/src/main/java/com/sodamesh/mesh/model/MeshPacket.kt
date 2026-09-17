package com.sodamesh.mesh.model

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Mesh wire packet (v2 header + payload + signature).
 *
 * Compact binary layout (big-endian), all integers unsigned unless noted:
 * ```
 * [version:1][type:1][ttl:1][flags:1]
 * [timestamp:8 (Int64 millis epoch)]
 * [senderId:8]
 * [recipientId:8 iff flags & HAS_RECIPIENT != 0]
 * [payloadLen:4 (Int32)]
 * [payload:payloadLen]
 * [signature:64]
 * ```
 * Fixed overhead: 88 bytes without recipient, 96 bytes with recipient.
 */
data class MeshPacket(
    val version: Int,
    val type: Byte,
    val ttl: Int,
    val timestamp: Long,
    val senderId: ByteArray,
    val recipientId: ByteArray?,
    val payload: ByteArray,
    val signature: ByteArray
) {
    companion object {
        const val VERSION_V2 = 2

        const val TYPE_ANNOUNCE: Byte = 0x01
        const val TYPE_ORDER: Byte = 0x20
        const val TYPE_ACK: Byte = 0x21

        const val ID_LEN = 8
        const val SIGNATURE_LEN = 64

        private const val FLAG_HAS_RECIPIENT = 0x01

        /** Overhead excluding payload and optional recipient. */
        const val BASE_OVERHEAD_BYTES = 1 + 1 + 1 + 1 + 8 + ID_LEN + 4 + SIGNATURE_LEN // = 88

        fun isKnownType(type: Byte): Boolean =
            type == TYPE_ANNOUNCE || type == TYPE_ORDER || type == TYPE_ACK

        /**
         * Parses one packet from [bytes].
         * @throws IllegalArgumentException if malformed, version/type unknown, or truncated.
         */
        fun fromBytes(bytes: ByteArray): MeshPacket {
            require(bytes.size >= BASE_OVERHEAD_BYTES) {
                "Truncated packet: ${bytes.size} < $BASE_OVERHEAD_BYTES"
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val version = buf.get().toInt() and 0xFF
            require(version == VERSION_V2) { "Unsupported version: $version" }
            val type = buf.get()
            require(isKnownType(type)) {
                "Unknown packet type: 0x${"%02X".format(type)}"
            }
            val ttl = buf.get().toInt() and 0xFF
            val flags = buf.get().toInt() and 0xFF
            val timestamp = buf.long
            require(timestamp >= 0) { "Negative timestamp: $timestamp" }

            val senderId = ByteArray(ID_LEN).also { buf.get(it) }
            val recipientId: ByteArray? =
                if (flags and FLAG_HAS_RECIPIENT != 0) ByteArray(ID_LEN).also { buf.get(it) } else null

            require(buf.remaining() >= Int.SIZE_BYTES + SIGNATURE_LEN) { "Truncated packet body" }
            val payloadLen = buf.int
            require(payloadLen >= 0) { "Negative payloadLen: $payloadLen" }
            require(buf.remaining() == payloadLen + SIGNATURE_LEN) {
                "Length mismatch: payloadLen=$payloadLen, remaining=${buf.remaining()}"
            }
            val payload = ByteArray(payloadLen).also { buf.get(it) }
            val signature = ByteArray(SIGNATURE_LEN).also { buf.get(it) }

            return MeshPacket(
                version = version,
                type = type,
                ttl = ttl,
                timestamp = timestamp,
                senderId = senderId,
                recipientId = recipientId,
                payload = payload,
                signature = signature
            )
        }
    }

    init {
        require(version == VERSION_V2) { "Unsupported version: $version" }
        require(isKnownType(type)) { "Unknown packet type: 0x${"%02X".format(type)}" }
        require(ttl in 0..255) { "ttl must be 0..255, was $ttl" }
        require(senderId.size == ID_LEN) { "senderId must be $ID_LEN bytes" }
        require(recipientId == null || recipientId.size == ID_LEN) {
            "recipientId must be null or $ID_LEN bytes"
        }
        require(signature.size == SIGNATURE_LEN) { "signature must be $SIGNATURE_LEN bytes" }
    }

    /** Serializes to the compact binary layout. */
    fun toBytes(): ByteArray {
        val hasRecipient = recipientId != null
        val flags = if (hasRecipient) FLAG_HAS_RECIPIENT else 0
        val buf = ByteBuffer
            .allocate(BASE_OVERHEAD_BYTES + (if (hasRecipient) ID_LEN else 0) + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
        buf.put(version.toByte())
        buf.put(type)
        buf.put(ttl.toByte())
        buf.put(flags.toByte())
        buf.putLong(timestamp)
        buf.put(senderId)
        if (hasRecipient) buf.put(recipientId)
        buf.putInt(payload.size)
        buf.put(payload)
        buf.put(signature)
        return buf.array()
    }

    /** Returns a copy with ttl decremented by one, floored at 0. */
    fun withDecrementedTtl(): MeshPacket = copy(ttl = (ttl - 1).coerceAtLeast(0))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshPacket) return false
        return version == other.version &&
            type == other.type &&
            ttl == other.ttl &&
            timestamp == other.timestamp &&
            senderId.contentEquals(other.senderId) &&
            (recipientId == null && other.recipientId == null ||
                recipientId != null && other.recipientId != null &&
                recipientId.contentEquals(other.recipientId)) &&
            payload.contentEquals(other.payload) &&
            signature.contentEquals(other.signature)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + type
        result = 31 * result + ttl
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + senderId.contentHashCode()
        result = 31 * result + (recipientId?.contentHashCode() ?: 0)
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + signature.contentHashCode()
        return result
    }
}
