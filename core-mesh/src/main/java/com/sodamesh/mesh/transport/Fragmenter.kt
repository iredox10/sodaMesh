package com.sodamesh.mesh.transport

import com.sodamesh.mesh.MeshConfig
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * BLE fragmentation for SodaMesh ORDER / ACK payloads.
 *
 * Pure Kotlin (no Android imports) so it stays unit-testable on the JVM.
 *
 * Contract (mirrors FragmenterTest doubles):
 * - [split] cuts [payload] into MTU-bounded chunks (default [MeshConfig.FRAG_MTU]).
 *   Empty payload yields one empty fragment; exact multiples yield no trailing
 *   empty chunk; every chunk is `<= chunkSize`.
 * - Wire shape per fragment: 5-byte header `[fragId:8][index:16 BE][total:16 BE]`
 *   followed by the chunk ([encode]/[decode]).
 * - [Reassembler.add] ignores duplicates and inconsistent headers, accepts
 *   out-of-order delivery, and returns the full payload once every piece of a
 *   message arrived, else null.
 *
 * Production guards on top of the test contract:
 * - up to [MAX_CONCURRENT_SESSIONS] (128) concurrent fragIds; the eldest is
 *   evicted when full,
 * - per-session [SESSION_TIMEOUT_MS] (30 s) expiry,
 * - [MAX_REASSEMBLED_BYTES] (1 MiB) cap per reassembled message.
 */
object Fragmenter {

    /** Bytes of the [encode]d header: fragId u8 + index u16be + total u16be. */
    const val HEADER_SIZE = 5

    /** Max payload bytes per fragment chunk. Mirrors [MeshConfig.FRAG_MTU]. */
    const val MAX_PAYLOAD_PER_FRAGMENT = MeshConfig.FRAG_MTU // 469

    /** Max concurrent reassembly sessions (keyed by fragId). */
    const val MAX_CONCURRENT_SESSIONS = 128

    /** Session expiry after the first fragment arrived. */
    const val SESSION_TIMEOUT_MS = 30_000L

    /** Cap on a single reassembled message. */
    const val MAX_REASSEMBLED_BYTES = 1024 * 1024 // 1 MiB

    /** Upper bound on fragments per message given the cap and max chunk size. */
    const val MAX_FRAGMENTS_PER_MESSAGE: Int =
        (MAX_REASSEMBLED_BYTES + MAX_PAYLOAD_PER_FRAGMENT - 1) / MAX_PAYLOAD_PER_FRAGMENT

    private val fragIdCounter = AtomicInteger(Random.nextInt(256))

    /** Next 8-bit fragment id (wraps 0..255). */
    fun nextFragId(): Int = fragIdCounter.getAndUpdate { (it + 1) and 0xFF }

    /**
     * Splits [payload] into fragments under [fragId].
     *
     * @param fragId 8-bit id (0..255) correlating the fragments of one message.
     * @param chunkSize payload bytes per fragment, 1..[MAX_PAYLOAD_PER_FRAGMENT].
     * @throws IllegalArgumentException on bad args or payloads over the 1 MiB cap.
     */
    fun split(
        fragId: Int,
        payload: ByteArray,
        chunkSize: Int = MAX_PAYLOAD_PER_FRAGMENT,
    ): List<MeshFragment> {
        require(fragId in 0..255) { "fragId must be 0..255" }
        require(chunkSize in 1..MAX_PAYLOAD_PER_FRAGMENT) {
            "chunkSize must be 1..$MAX_PAYLOAD_PER_FRAGMENT"
        }
        require(payload.size <= MAX_REASSEMBLED_BYTES) {
            "payload ${payload.size} exceeds 1 MiB cap"
        }
        if (payload.isEmpty()) return listOf(MeshFragment(fragId, 0, 1, byteArrayOf()))
        val chunks = payload.asList().chunked(chunkSize) { it.toByteArray() }
        require(chunks.size <= MAX_FRAGMENTS_PER_MESSAGE) { "too many fragments" }
        return chunks.mapIndexed { i, chunk -> MeshFragment(fragId, i, chunks.size, chunk) }
    }

    /** Encodes a fragment to its wire form (header + chunk). */
    fun encode(fragment: MeshFragment): ByteArray {
        require(fragment.fragId in 0..255) { "fragId must be 0..255" }
        require(fragment.total in 1..MAX_FRAGMENTS_PER_MESSAGE) { "bad total" }
        require(fragment.index in 0 until fragment.total) { "index out of range" }
        require(fragment.payload.size <= MAX_PAYLOAD_PER_FRAGMENT) { "chunk too large" }
        val out = ByteArray(HEADER_SIZE + fragment.payload.size)
        out[0] = fragment.fragId.toByte()
        out[1] = (fragment.index shr 8).toByte()
        out[2] = fragment.index.toByte()
        out[3] = (fragment.total shr 8).toByte()
        out[4] = fragment.total.toByte()
        fragment.payload.copyInto(out, HEADER_SIZE)
        return out
    }

    /** Decodes wire bytes; null when malformed. */
    fun decode(raw: ByteArray): MeshFragment? {
        if (raw.size < HEADER_SIZE) return null
        val fragId = raw[0].toInt() and 0xFF
        val index = ((raw[1].toInt() and 0xFF) shl 8) or (raw[2].toInt() and 0xFF)
        val total = ((raw[3].toInt() and 0xFF) shl 8) or (raw[4].toInt() and 0xFF)
        if (total < 1 || total > MAX_FRAGMENTS_PER_MESSAGE) return null
        if (index < 0 || index >= total) return null
        if (raw.size - HEADER_SIZE > MAX_PAYLOAD_PER_FRAGMENT) return null
        return MeshFragment(fragId, index, total, raw.copyOfRange(HEADER_SIZE, raw.size))
    }
}

/** In-memory fragment: message correlation id + position + one chunk. */
data class MeshFragment(
    val fragId: Int,
    val index: Int,
    val total: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshFragment) return false
        return fragId == other.fragId &&
            index == other.index &&
            total == other.total &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = fragId
        result = 31 * result + index
        result = 31 * result + total
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * Reassembles fragments into full payloads.
 *
 * @param clock millis source (injectable for tests).
 */
class Reassembler(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Session(
        val total: Int,
        val firstSeenAt: Long,
        val chunks: Array<ByteArray?>,
        var received: Int = 0,
        var bytes: Int = 0,
    )

    /** Insertion-ordered so the eldest session is evicted when full. */
    private val sessions = LinkedHashMap<Int, Session>()

    /**
     * Feeds one decoded [fragment]; returns the full payload once every piece
     * of its message arrived, else null. Thread-safe.
     */
    @Synchronized
    fun add(fragment: MeshFragment): ByteArray? {
        if (fragment.total < 1 ||
            fragment.total > Fragmenter.MAX_FRAGMENTS_PER_MESSAGE ||
            fragment.index < 0 ||
            fragment.index >= fragment.total
        ) {
            return null
        }
        if (fragment.payload.size > Fragmenter.MAX_PAYLOAD_PER_FRAGMENT) return null
        purgeExpiredLocked(clock())

        var session = sessions[fragment.fragId]
        if (session == null || session.total != fragment.total) {
            if (session != null) sessions.remove(fragment.fragId)
            if (sessions.size >= Fragmenter.MAX_CONCURRENT_SESSIONS) {
                val eldest = sessions.keys.firstOrNull()
                if (eldest != null) sessions.remove(eldest)
            }
            session = Session(
                total = fragment.total,
                firstSeenAt = clock(),
                chunks = arrayOfNulls(fragment.total),
            )
            sessions[fragment.fragId] = session
        }

        // Duplicate: keep the first copy, never corrupt.
        if (session.chunks[fragment.index] == null) {
            // 1 MiB cap: drop the whole session on overflow.
            if (session.bytes + fragment.payload.size > Fragmenter.MAX_REASSEMBLED_BYTES) {
                sessions.remove(fragment.fragId)
                return null
            }
            session.chunks[fragment.index] = fragment.payload
            session.received++
            session.bytes += fragment.payload.size
        }

        if (session.received != session.total) return null
        sessions.remove(fragment.fragId)
        val out = ByteArray(session.bytes)
        var offset = 0
        for (i in 0 until session.total) {
            val chunk = session.chunks[i] ?: return null
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    /** Decodes wire [raw] bytes then [add]s them; null when malformed/incomplete. */
    @Synchronized
    fun feed(raw: ByteArray): ByteArray? {
        val fragment = Fragmenter.decode(raw) ?: return null
        return add(fragment)
    }

    /** Drops sessions older than [Fragmenter.SESSION_TIMEOUT_MS]. */
    @Synchronized
    fun purgeExpired(now: Long = clock()) {
        purgeExpiredLocked(now)
    }

    private fun purgeExpiredLocked(now: Long) {
        val expired = sessions.filterValues { now - it.firstSeenAt > Fragmenter.SESSION_TIMEOUT_MS }.keys
        expired.forEach { sessions.remove(it) }
    }

    @Synchronized
    fun pendingCount(): Int = sessions.size
}
