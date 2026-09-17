package com.sodamesh.mesh.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

/**
 * Persistent sender-side queue: messages awaiting BLE delivery to a peer.
 *
 * Policy:
 * - [MAX_PER_PEER]: at most 100 queued messages per peer; enqueueing beyond
 *   that evicts the oldest message for that peer (FIFO).
 * - [TTL_MS]: messages older than 24h are expired lazily (on access) and by
 *   [sweep].
 * - [MAX_ATTEMPTS]: a message attempted 8 times is dropped as undeliverable.
 *
 * NOTE (sealed + persistence): outcomes are modelled as the sealed hierarchy
 * [OutboxNote] so callers handle every case exhaustively. Persistence is
 * in-memory plus an optional disk journal: pass [persistenceDir] (a writable
 * directory) to journal every mutation to `outbox.journal` via plain
 * [DataOutputStream] framing -- no DataStore/Gson dependency, so this file
 * compiles against JDK + kotlinx-coroutines only. Journal writes are
 * write-through under [mutex]; the constructor replays the journal
 * synchronously (documented: construct off the main thread when a
 * [persistenceDir] is supplied). A corrupt journal is discarded, never fatal.
 */
class Outbox(
    private val persistenceDir: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val messages = LinkedHashMap<String, QueuedMessage>() // id -> message (insertion order)
    private val _pending = MutableStateFlow<List<QueuedMessage>>(emptyList())
    private val ioDispatcher = Dispatchers.IO

    /** Observable snapshot of non-expired queued messages, oldest first. */
    val pending: StateFlow<List<QueuedMessage>> = _pending.asStateFlow()

    init {
        val dir = persistenceDir
        if (dir != null) {
            // Synchronous replay; see class NOTE. Corruption -> start empty.
            runCatching { readJournal(dir) }.getOrNull()?.let { loaded ->
                val now = clock()
                loaded.filterTo(messages) { (_, m) -> !m.isExpired(now) }
                refreshLocked()
            }
        }
    }

    /** Enqueues [payload] for [peerId]. Evicts oldest for that peer when full. */
    suspend fun enqueue(peerId: String, payload: ByteArray, id: String = UUID.randomUUID().toString()): OutboxNote {
        require(peerId.isNotBlank()) { "peerId must not be blank" }
        val now = clock()
        return mutex.withLock {
            purgeExpiredLocked(now)
            val forPeer = messages.values.filter { it.peerId == peerId }.sortedBy { it.enqueuedAtMs }
            var evicted: QueuedMessage? = null
            if (forPeer.size >= MAX_PER_PEER) {
                evicted = forPeer.first()
                messages.remove(evicted.id)
            }
            val msg = QueuedMessage(
                id = id,
                peerId = peerId,
                payload = payload.copyOf(),
                enqueuedAtMs = now,
                attempts = 0,
                lastAttemptMs = null,
            )
            messages[id] = msg
            persistLocked()
            refreshLocked()
            OutboxNote.Enqueued(msg, evictedOlder = evicted)
        }
    }

    /**
     * Records one send attempt for [id]. At [MAX_ATTEMPTS] the message is
     * dropped and [OutboxNote.MaxAttemptsReached] is returned.
     */
    suspend fun recordAttempt(id: String): OutboxNote = mutex.withLock {
        val now = clock()
        val msg = messages[id] ?: return OutboxNote.UnknownId(id)
        if (msg.isExpired(now)) {
            messages.remove(id)
            persistLocked()
            refreshLocked()
            return OutboxNote.DroppedExpired(msg)
        }
        val next = msg.copy(attempts = msg.attempts + 1, lastAttemptMs = now)
        if (next.attempts >= MAX_ATTEMPTS) {
            messages.remove(id)
            persistLocked()
            refreshLocked()
            OutboxNote.MaxAttemptsReached(next)
        } else {
            messages[id] = next
            persistLocked()
            refreshLocked()
            OutboxNote.AttemptRecorded(next, remaining = MAX_ATTEMPTS - next.attempts)
        }
    }

    /** Removes [id] after acknowledgement. */
    suspend fun ack(id: String): OutboxNote = mutex.withLock {
        val msg = messages.remove(id) ?: return OutboxNote.UnknownId(id)
        persistLocked()
        refreshLocked()
        OutboxNote.Acked(msg)
    }

    /** Non-expired queued messages for [peerId], oldest first. */
    suspend fun pendingFor(peerId: String): List<QueuedMessage> = mutex.withLock {
        purgeExpiredLocked(clock())
        refreshLocked()
        messages.values.filter { it.peerId == peerId }.sortedBy { it.enqueuedAtMs }.map { it.copy() }
    }

    /** Current queue depth for [peerId] (expired messages purged first). */
    suspend fun depth(peerId: String): Int = pendingFor(peerId).size

    /**
     * Purges expired messages across all peers.
     * @return number of messages removed.
     */
    suspend fun sweep(): Int = mutex.withLock {
        val removed = purgeExpiredLocked(clock())
        if (removed > 0) {
            persistLocked()
            refreshLocked()
        }
        removed
    }

    /** Clears the whole queue (used on logout / tests). */
    suspend fun clear() = mutex.withLock {
        messages.clear()
        persistLocked()
        refreshLocked()
    }

    // -- internals (mutex held) ------------------------------------------------

    /** Removes expired entries; returns count removed. */
    private fun purgeExpiredLocked(now: Long): Int {
        val expired = messages.values.filter { it.isExpired(now) }.map { it.id }
        expired.forEach(messages::remove)
        return expired.size
    }

    private fun refreshLocked() {
        _pending.value = messages.values.sortedBy { it.enqueuedAtMs }.map { it.copy() }
    }

    private suspend fun persistLocked() {
        val dir = persistenceDir ?: return
        val snapshot = messages.values.toList()
        withContext(ioDispatcher) {
            runCatching { writeJournal(dir, snapshot) }
        }
    }

    private fun journalFile(dir: File) = File(dir, JOURNAL_NAME)

    private fun writeJournal(dir: File, snapshot: List<QueuedMessage>) {
        dir.mkdirs()
        val tmp = File(dir, "$JOURNAL_NAME.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(JOURNAL_VERSION)
            out.writeInt(snapshot.size)
            for (m in snapshot) {
                out.writeUTF(m.id)
                out.writeUTF(m.peerId)
                out.writeLong(m.enqueuedAtMs)
                out.writeInt(m.attempts)
                out.writeLong(m.lastAttemptMs ?: -1L)
                out.writeInt(m.payload.size)
                out.write(m.payload)
            }
        }
        val target = journalFile(dir)
        if (target.exists() && !target.delete()) return // keep old journal on failure
        tmp.renameTo(target)
    }

    private fun readJournal(dir: File): Map<String, QueuedMessage> {
        val f = journalFile(dir)
        if (!f.exists()) return emptyMap()
        DataInputStream(f.inputStream().buffered()).use { inp ->
            val version = inp.readInt()
            require(version == JOURNAL_VERSION) { "unsupported journal v$version" }
            val count = inp.readInt()
            require(count in 0..MAX_JOURNAL_ENTRIES) { "implausible entry count $count" }
            val out = LinkedHashMap<String, QueuedMessage>(count)
            repeat(count) {
                val id = inp.readUTF()
                val peerId = inp.readUTF()
                val enqueuedAtMs = inp.readLong()
                val attempts = inp.readInt()
                val lastRaw = inp.readLong()
                val len = inp.readInt()
                require(len in 0..MAX_JOURNAL_PAYLOAD_BYTES) { "implausible payload $len" }
                val payload = ByteArray(len).also(inp::readFully)
                out[id] = QueuedMessage(
                    id = id,
                    peerId = peerId,
                    payload = payload,
                    enqueuedAtMs = enqueuedAtMs,
                    attempts = attempts.coerceIn(0, MAX_ATTEMPTS),
                    lastAttemptMs = lastRaw.takeIf { it >= 0 },
                )
            }
            return out
        }
    }

    companion object {
        /** Max queued messages per peer (oldest evicted past this). */
        const val MAX_PER_PEER = 100

        /** Message time-to-live: 24h. */
        const val TTL_MS = 24L * 60 * 60 * 1000

        /** Send attempts before a message is dropped as undeliverable. */
        const val MAX_ATTEMPTS = 8

        private const val JOURNAL_NAME = "outbox.journal"
        private const val JOURNAL_VERSION = 1
        private const val MAX_JOURNAL_ENTRIES = 100_000
        private const val MAX_JOURNAL_PAYLOAD_BYTES = 4 * 1024 * 1024
    }
}

/** A message awaiting delivery, with retry bookkeeping. */
class QueuedMessage(
    val id: String,
    val peerId: String,
    val payload: ByteArray,
    val enqueuedAtMs: Long,
    val attempts: Int = 0,
    val lastAttemptMs: Long? = null,
) {
    fun isExpired(now: Long): Boolean = now - enqueuedAtMs > Outbox.TTL_MS

    fun copy(
        id: String = this.id,
        peerId: String = this.peerId,
        payload: ByteArray = this.payload.copyOf(),
        enqueuedAtMs: Long = this.enqueuedAtMs,
        attempts: Int = this.attempts,
        lastAttemptMs: Long? = this.lastAttemptMs,
    ): QueuedMessage = QueuedMessage(id, peerId, payload, enqueuedAtMs, attempts, lastAttemptMs)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QueuedMessage) return false
        return id == other.id && peerId == other.peerId && payload.contentEquals(other.payload) &&
            enqueuedAtMs == other.enqueuedAtMs && attempts == other.attempts &&
            lastAttemptMs == other.lastAttemptMs
    }

    override fun hashCode(): Int {
        var r = id.hashCode()
        r = 31 * r + peerId.hashCode()
        r = 31 * r + payload.contentHashCode()
        r = 31 * r + enqueuedAtMs.hashCode()
        r = 31 * r + attempts
        r = 31 * r + (lastAttemptMs?.hashCode() ?: 0)
        return r
    }

    override fun toString(): String =
        "QueuedMessage(id=$id, peerId=$peerId, bytes=${payload.size}, " +
            "enqueuedAtMs=$enqueuedAtMs, attempts=$attempts, lastAttemptMs=$lastAttemptMs)"
}

/**
 * Exhaustive outcome of every [Outbox] mutation, so callers handle
 * enqueue / retry / drop / ack paths explicitly.
 */
sealed interface OutboxNote {
    /** Message queued; [evictedOlder] set when the per-peer cap forced a FIFO eviction. */
    data class Enqueued(val message: QueuedMessage, val evictedOlder: QueuedMessage? = null) : OutboxNote

    /** One attempt logged; [remaining] attempts left before the cap. */
    data class AttemptRecorded(val message: QueuedMessage, val remaining: Int) : OutboxNote

    /** Attempt cap ([Outbox.MAX_ATTEMPTS]) hit; message dropped. */
    data class MaxAttemptsReached(val message: QueuedMessage) : OutboxNote

    /** Message exceeded [Outbox.TTL_MS]; dropped without delivery. */
    data class DroppedExpired(val message: QueuedMessage) : OutboxNote

    /** Message acknowledged by the peer and removed. */
    data class Acked(val message: QueuedMessage) : OutboxNote

    /** No queued message with this id. */
    data class UnknownId(val id: String) : OutboxNote
}
