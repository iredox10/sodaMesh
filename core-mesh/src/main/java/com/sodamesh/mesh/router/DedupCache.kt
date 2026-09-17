package com.sodamesh.mesh.router

import com.sodamesh.mesh.model.MeshPacket
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe LRU dedup for inbound mesh packets (bitchat method).
 *
 * Identity = `senderId | timestamp | type | SHA-256(payload)`, i.e. the
 * `sender+ts+type+digest` key from the mesh contract. Capacity is
 * [MAX_ENTRIES_DEFAULT] (1000) entries with [EXPIRY_MS_DEFAULT] (5 min)
 * expiry; duplicates inside the window are dropped, anything older is
 * treated as unseen (safe re-relay).
 *
 * Pure JVM (no Android deps) so it stays unit-testable. All state is
 * guarded by one lock; every public method is safe to call from any thread.
 */
@Singleton
class DedupCache @Inject constructor() {

    /** Max tracked keys; oldest evicted past this (LRU). Tunable for tests. */
    @Volatile
    var maxEntries: Int = MAX_ENTRIES_DEFAULT

    /** Freshness window per key in ms. Tunable for tests. */
    @Volatile
    var expiryMs: Long = EXPIRY_MS_DEFAULT

    private val lock = Any()

    // Access-order LinkedHashMap = LRU. Touch via get() on hits.
    private val table = object : LinkedHashMap<String, Long>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean =
            size > maxEntries
    }

    /** Dedup key for raw components. [payloadDigest] is any stable digest (see [sha256]). */
    fun keyFor(
        senderId: ByteArray,
        timestamp: Long,
        type: Byte,
        payloadDigest: ByteArray,
    ): String = keyFor(toHex(senderId), timestamp, type, toHex(payloadDigest))

    /** Dedup key for hex components (no hashing; caller supplies the digest). */
    fun keyFor(
        senderHex: String,
        timestamp: Long,
        type: Byte,
        digestHex: String,
    ): String = "$senderHex|$timestamp|${type.toInt() and 0xFF}|$digestHex"

    /** Dedup key for a wire packet; digest = SHA-256([MeshPacket.payload]). */
    fun keyFor(packet: MeshPacket): String =
        keyFor(packet.senderId, packet.timestamp, packet.type, sha256(packet.payload))

    /**
     * Dedup check-and-record: returns true when [key] is unseen (caller should
     * relay/deliver) and records it; false when seen inside [expiryMs].
     */
    fun shouldRelay(key: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            val last = table[key]
            // nowMs < last (clock moved back) also counts as "seen" to avoid storms.
            if (last != null && (nowMs < last || nowMs - last < expiryMs)) return false
            table[key] = nowMs
            trimLocked()
            true
        }

    /** [shouldRelay] for a wire packet (key = [keyFor]). */
    fun shouldRelay(packet: MeshPacket, nowMs: Long = System.currentTimeMillis()): Boolean =
        shouldRelay(keyFor(packet), nowMs)

    /** True when [key] is currently tracked and fresh (does not record). */
    fun contains(key: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        synchronized(lock) {
            val last = table[key] ?: return false
            nowMs >= last && nowMs - last < expiryMs
        }

    /** Records [key] as seen at [nowMs] without a freshness check. */
    fun markSeen(key: String, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            table[key] = nowMs
            trimLocked()
        }
    }

    /** Drops keys older than [expiryMs]; returns the number removed. */
    fun evictExpired(nowMs: Long = System.currentTimeMillis()): Int = synchronized(lock) {
        val expired = table.filterValues { nowMs - it >= expiryMs }.keys.toList()
        expired.forEach(table::remove)
        expired.size
    }

    /** Current number of tracked (possibly stale) keys. */
    fun size(): Int = synchronized(lock) { table.size }

    /** Clears all tracked keys (logout / tests). */
    fun clear() = synchronized(lock) { table.clear() }

    private fun trimLocked() {
        // removeEldestEntry covers single puts; this also heals a lowered maxEntries.
        while (table.size > maxEntries) {
            val eldest = table.keys.firstOrNull() ?: break
            table.remove(eldest)
        }
    }

    companion object {
        /** LRU capacity: 1000 entries. */
        const val MAX_ENTRIES_DEFAULT = 1000

        /** Freshness window: 5 minutes in ms. */
        const val EXPIRY_MS_DEFAULT = 5L * 60 * 1000

        /** SHA-256 digest (the ByteArray digest used in dedup keys). */
        fun sha256(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)

        /** Lowercase hex, no prefix. */
        fun toHex(bytes: ByteArray): String {
            val out = CharArray(bytes.size * 2)
            val digits = "0123456789abcdef"
            bytes.forEachIndexed { i, b ->
                out[i * 2] = digits[(b.toInt() ushr 4) and 0xF]
                out[i * 2 + 1] = digits[b.toInt() and 0xF]
            }
            return String(out)
        }
    }
}
