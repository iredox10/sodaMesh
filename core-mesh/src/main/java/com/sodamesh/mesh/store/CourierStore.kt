package com.sodamesh.mesh.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Store-and-carry buffer for delay-tolerant delivery (courier / mule nodes).
 *
 * Policy:
 * - [MAX_ENVELOPES]: at most 20 carried envelopes in total; beyond that the
 *   oldest envelope is evicted (FIFO).
 * - [MAX_PAYLOAD_BYTES]: envelopes larger than 16KB are rejected.
 * - [TTL_MS]: envelopes expire 24h after creation ([sweep] purges them).
 * - Copy budget (binary spray-and-wait): a new envelope starts with
 *   [DEFAULT_COPIES] (4) copies, never more than [MAX_COPIES] (8). Each
 *   forwarding encounter halves the budget via [spraySplit] (keeper retains
 *   ceil(n/2), the peer receives floor(n/2)); a single remaining copy means
 *   the wait phase -- deliver directly only.
 *
 * Opaque addressing: couriers never store recipient identities. Each envelope
 * carries only a rotating HMAC day-tag,
 * `HMAC-SHA256(dayKey, "sodamesh-day-tag" || day || recipientId)[..16]`,
 * where `day` is the UTC calendar date. The destination proves itself by
 * reproducing the tag; tags from previous days are accepted within [TTL_MS]
 * via [matchAnyDay] so midnight rotation never strands a carried envelope.
 * Compromise of the store leaks tags, not identities.
 *
 * JDK + kotlinx-coroutines only (javax.crypto, java.time, no new deps).
 */
class CourierStore(
    dayKey: ByteArray,
    private val clock: () -> Long = System::currentTimeMillis,
    private val currentDayUtc: () -> String = { LocalDate.now(ZoneOffset.UTC).toString() },
) {
    private val keySpec = SecretKeySpec(dayKey.copyOf(), HMAC_ALGORITHM)
    private val mutex = Mutex()
    private val envelopes = LinkedHashMap<String, CarryEnvelope>() // id -> envelope (insertion order)
    private val _carried = MutableStateFlow<List<CarryEnvelope>>(emptyList())

    /** Observable snapshot of non-expired carried envelopes, oldest first. */
    val carried: StateFlow<List<CarryEnvelope>> = _carried.asStateFlow()

    /** Derives the opaque day-tag for [recipientId] on [dayUtc] (default: today). */
    fun tagFor(recipientId: ByteArray, dayUtc: String = currentDayUtc()): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM).apply { init(keySpec) }
        mac.update(TAG_DOMAIN.toByteArray(Charsets.UTF_8))
        mac.update(dayUtc.toByteArray(Charsets.UTF_8))
        mac.update(recipientId)
        return mac.doFinal().copyOf(TAG_BYTES)
    }

    /** Constant-time check that [tag] is the day-tag for [recipientId] on [dayUtc]. */
    fun verifyTag(tag: ByteArray, recipientId: ByteArray, dayUtc: String = currentDayUtc()): Boolean =
        MessageDigest.isEqual(tag, tagFor(recipientId, dayUtc))

    /**
     * Accepts an envelope for [recipientId] into the carry buffer.
     * Rejects payloads over [MAX_PAYLOAD_BYTES]; evicts the oldest envelope
     * when the buffer already holds [MAX_ENVELOPES].
     *
     * @param copies initial copy budget in 1..[MAX_COPIES] (default [DEFAULT_COPIES]).
     */
    suspend fun carry(
        recipientId: ByteArray,
        payload: ByteArray,
        copies: Int = DEFAULT_COPIES,
        id: String = java.util.UUID.randomUUID().toString(),
    ): CourierNote {
        require(copies in 1..MAX_COPIES) { "copies must be in 1..$MAX_COPIES" }
        if (payload.size > MAX_PAYLOAD_BYTES) {
            return CourierNote.RejectedTooLarge(payload.size, MAX_PAYLOAD_BYTES)
        }
        val now = clock()
        return mutex.withLock {
            purgeExpiredLocked(now)
            var evicted: CarryEnvelope? = null
            if (envelopes.size >= MAX_ENVELOPES) {
                val oldest = envelopes.values.minBy { it.createdAtMs }
                envelopes.remove(oldest.id)
                evicted = oldest
            }
            val env = CarryEnvelope(
                id = id,
                tag = tagFor(recipientId),
                dayUtc = currentDayUtc(),
                payload = payload.copyOf(),
                createdAtMs = now,
                expiresAtMs = now + TTL_MS,
                copiesRemaining = copies,
            )
            envelopes[id] = env
            refreshLocked()
            CourierNote.Carried(env, evictedOlder = evicted)
        }
    }

    /**
     * Binary spray-and-wait split for one forwarding encounter over [id]:
     * keeper retains ceil(n/2), returns a handover copy with floor(n/2).
     * A single-copy envelope is in the wait phase ([CourierNote.WaitPhase]).
     */
    suspend fun spraySplit(id: String): CourierNote = mutex.withLock {
        val now = clock()
        val env = envelopes[id] ?: return CourierNote.UnknownId(id)
        if (env.isExpired(now)) {
            envelopes.remove(id)
            refreshLocked()
            return CourierNote.DroppedExpired(env)
        }
        if (env.copiesRemaining <= 1) return CourierNote.WaitPhase(env)
        val give = env.copiesRemaining / 2
        val keep = env.copiesRemaining - give
        envelopes[id] = env.copy(copiesRemaining = keep)
        refreshLocked()
        CourierNote.Handover(envelopes[id]!!, handover = env.copy(copiesRemaining = give))
    }

    /**
     * Returns non-expired envelopes whose tag matches [tag] for [recipientId]
     * on any day still within [TTL_MS] (tolerates midnight rotation).
     */
    suspend fun matchAnyDay(tag: ByteArray, recipientId: ByteArray): List<CarryEnvelope> = mutex.withLock {
        val now = clock()
        purgeExpiredLocked(now)
        refreshLocked()
        val days = daysWithinTtl(now)
        envelopes.values.filter { env ->
            days.any { day -> MessageDigest.isEqual(env.tag, tagFor(recipientId, day)) } &&
                MessageDigest.isEqual(env.tag, tag)
        }.map { it.copy() }
    }

    /** Removes [id] after delivery / handover completion. */
    suspend fun remove(id: String): CourierNote = mutex.withLock {
        val env = envelopes.remove(id) ?: return CourierNote.UnknownId(id)
        refreshLocked()
        CourierNote.Delivered(env)
    }

    /**
     * Purges expired envelopes.
     * @return number removed.
     */
    suspend fun sweep(): Int = mutex.withLock {
        val removed = purgeExpiredLocked(clock())
        if (removed > 0) refreshLocked()
        removed
    }

    /** Remaining copy budget for [id], or null when unknown. */
    suspend fun budget(id: String): Int? = mutex.withLock { envelopes[id]?.copiesRemaining }

    /** Current number of carried (non-expired) envelopes. */
    suspend fun size(): Int = mutex.withLock {
        purgeExpiredLocked(clock())
        refreshLocked()
        envelopes.size
    }

    // -- internals (mutex held) ------------------------------------------------

    private fun purgeExpiredLocked(now: Long): Int {
        val expired = envelopes.values.filter { it.isExpired(now) }.map { it.id }
        expired.forEach(envelopes::remove)
        return expired.size
    }

    private fun refreshLocked() {
        _carried.value = envelopes.values.sortedBy { it.createdAtMs }.map { it.copy() }
    }

    /** UTC date strings from today back over the TTL window (today + yesterday). */
    private fun daysWithinTtl(now: Long): List<String> {
        val today = LocalDate.now(ZoneOffset.UTC)
        // 24h TTL can span at most one midnight boundary.
        return listOf(today.toString(), today.minusDays(1).toString())
    }

    companion object {
        /** Max carried envelopes in total (oldest evicted past this). */
        const val MAX_ENVELOPES = 20

        /** Max envelope payload: 16KB. */
        const val MAX_PAYLOAD_BYTES = 16 * 1024

        /** Envelope time-to-live: 24h from creation. */
        const val TTL_MS = 24L * 60 * 60 * 1000

        /** Initial spray-and-wait copy budget for a new envelope. */
        const val DEFAULT_COPIES = 4

        /** Hard cap on the copy budget (priority boosts included). */
        const val MAX_COPIES = 8

        /** Truncated day-tag length in bytes (bandwidth-friendly, 128-bit). */
        const val TAG_BYTES = 16

        internal const val HMAC_ALGORITHM = "HmacSHA256"
        internal const val TAG_DOMAIN = "sodamesh-day-tag|"
    }
}

/** An opaque carried envelope: routable by day-tag only, never by identity. */
class CarryEnvelope(
    val id: String,
    val tag: ByteArray,
    val dayUtc: String,
    val payload: ByteArray,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val copiesRemaining: Int,
) {
    fun isExpired(now: Long): Boolean = now >= expiresAtMs

    fun copy(
        id: String = this.id,
        tag: ByteArray = this.tag.copyOf(),
        dayUtc: String = this.dayUtc,
        payload: ByteArray = this.payload.copyOf(),
        createdAtMs: Long = this.createdAtMs,
        expiresAtMs: Long = this.expiresAtMs,
        copiesRemaining: Int = this.copiesRemaining,
    ): CarryEnvelope = CarryEnvelope(id, tag, dayUtc, payload, createdAtMs, expiresAtMs, copiesRemaining)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CarryEnvelope) return false
        return id == other.id && tag.contentEquals(other.tag) && dayUtc == other.dayUtc &&
            payload.contentEquals(other.payload) && createdAtMs == other.createdAtMs &&
            expiresAtMs == other.expiresAtMs && copiesRemaining == other.copiesRemaining
    }

    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + tag.contentHashCode()
        r = 31 * r + dayUtc.hashCode()
        r = 31 * r + payload.contentHashCode()
        r = 31 * r + createdAtMs.hashCode()
        r = 31 * r + expiresAtMs.hashCode()
        r = 31 * r + copiesRemaining
        return r
    }

    override fun toString(): String =
        "CarryEnvelope(id=$id, dayUtc=$dayUtc, bytes=${payload.size}, " +
            "copiesRemaining=$copiesRemaining, expiresAtMs=$expiresAtMs)"
}

/** Exhaustive outcome of every [CourierStore] operation. */
sealed interface CourierNote {
    /** Envelope accepted; [evictedOlder] set when the 20-envelope cap forced eviction. */
    data class Carried(val envelope: CarryEnvelope, val evictedOlder: CarryEnvelope? = null) : CourierNote

    /** Payload exceeded [CourierStore.MAX_PAYLOAD_BYTES]; nothing stored. */
    data class RejectedTooLarge(val bytes: Int, val maxBytes: Int) : CourierNote

    /** Spray phase: hand [handover] to the encountered peer, keep [keeper]. */
    data class Handover(val keeper: CarryEnvelope, val handover: CarryEnvelope) : CourierNote

    /** Single copy left: deliver directly to the destination only. */
    data class WaitPhase(val envelope: CarryEnvelope) : CourierNote

    /** Envelope removed after delivery. */
    data class Delivered(val envelope: CarryEnvelope) : CourierNote

    /** Envelope exceeded its 24h TTL; dropped. */
    data class DroppedExpired(val envelope: CarryEnvelope) : CourierNote

    /** No envelope with this id. */
    data class UnknownId(val id: String) : CourierNote
}
