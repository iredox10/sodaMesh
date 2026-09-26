package com.sodamesh.mesh

import com.sodamesh.mesh.store.Outbox
import com.sodamesh.mesh.store.OutboxNote
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background redelivery sweeper for queued (un-ACKed) orders.
 *
 * Every [RETRY_INTERVAL_MS], [sweepOnce] snapshots the [Outbox] pending
 * queue: each entry gets [Outbox.recordAttempt] (which drops it at
 * [Outbox.MAX_ATTEMPTS] or on [Outbox.TTL_MS] expiry) and, while attempts
 * remain, one best-effort [BleMeshSender.retryDelivery] over the current
 * GATT link (no scanning/connecting — the entry stays queued when no link
 * is up). Entries ACKed in the meantime ([BleMeshSender.observeAck] clears
 * them) are simply gone from the snapshot.
 *
 * STARTUP (coordinator-owned): this worker is `@Singleton`-injectable via
 * Hilt but does NOT self-start — the coordinator starts it once from an
 * application-scoped coroutine scope, e.g. in the `Application.onCreate`
 * entry point or the Hilt-provided initializer that owns `MeshService`:
 * `outboxWorker.start(appScope)`. Do NOT start it from an Activity
 * (process death would stop retries) and do NOT start it twice ([start] is
 * idempotent and returns the active job).
 */
@Singleton
class OutboxWorker @Inject constructor(
    private val outbox: Outbox,
    private val sender: BleMeshSender,
) {

    companion object {
        /** Delay between redelivery sweeps. */
        const val RETRY_INTERVAL_MS = 30_000L
    }

    @Volatile
    private var loop: Job? = null

    /**
     * Starts the periodic sweep loop on [scope]. Idempotent: returns the
     * active job when already started. Cancel the returned job (or call
     * [stop]) to halt.
     */
    fun start(scope: CoroutineScope): Job {
        loop?.takeIf { it.isActive }?.let { return it }
        val job = scope.launch {
            while (isActive) {
                delay(RETRY_INTERVAL_MS)
                runCatching { sweepOnce() }
            }
        }
        loop = job
        return job
    }

    /** Halts the loop started by [start]. */
    fun stop() {
        loop?.cancel()
        loop = null
    }

    /**
     * One redelivery pass over the current pending snapshot.
     * @return number of entries re-written to the GATT link.
     */
    suspend fun sweepOnce(): Int {
        var redelivered = 0
        val snapshot = outbox.pending.value.toList()
        for (entry in snapshot) {
            when (outbox.recordAttempt(entry.id)) {
                is OutboxNote.AttemptRecorded -> {
                    if (runCatching { sender.retryDelivery(entry.payload) }.getOrDefault(false)) {
                        redelivered++
                    }
                }
                // MaxAttemptsReached / DroppedExpired / UnknownId / Acked:
                // already removed or gone — nothing to redeliver.
                else -> Unit
            }
        }
        runCatching { outbox.sweep() }
        return redelivered
    }
}
