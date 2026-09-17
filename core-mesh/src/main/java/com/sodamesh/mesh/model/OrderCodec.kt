package com.sodamesh.mesh.model

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException

/**
 * JSON (Gson/UTF-8) codec between [SodaOrder] and mesh payload bytes.
 * Only dependency: Gson. Size guard: payloads must fit [MAX_PAYLOAD_BYTES].
 */
object OrderCodec {
    /** Max payload size: 16 KiB. */
    const val MAX_PAYLOAD_BYTES = 16 * 1024

    private val gson = Gson()

    /**
     * Validates [order], serializes to JSON UTF-8 bytes.
     * @throws IllegalArgumentException if invalid or over the size guard.
     */
    fun encode(order: SodaOrder): ByteArray {
        order.validate()
        val bytes = gson.toJson(order).toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_PAYLOAD_BYTES) {
            "Order payload too large: ${bytes.size} > $MAX_PAYLOAD_BYTES"
        }
        return bytes
    }

    /**
     * Parses and validates payload bytes back into a [SodaOrder].
     * @throws IllegalArgumentException if over the size guard, malformed, or invalid.
     */
    fun decode(bytes: ByteArray): SodaOrder {
        require(bytes.size <= MAX_PAYLOAD_BYTES) {
            "Order payload too large: ${bytes.size} > $MAX_PAYLOAD_BYTES"
        }
        val order = try {
            gson.fromJson(String(bytes, Charsets.UTF_8), SodaOrder::class.java)
        } catch (e: JsonSyntaxException) {
            throw IllegalArgumentException("Malformed order JSON: ${e.message}", e)
        } ?: throw IllegalArgumentException("Malformed order JSON: null")
        order.validate()
        return order
    }
}
