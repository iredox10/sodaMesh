package com.sodamesh.mesh

import com.sodamesh.mesh.store.Outbox
import com.sodamesh.mesh.store.OutboxNote
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [Outbox]: enqueue/attempt/ack/expiry, the
 * [Outbox.MAX_ATTEMPTS] drop cap, and the [Outbox.MAX_PER_PEER] FIFO
 * eviction. Uses a controllable clock; no disk journal.
 */
class OutboxTest {

    private var now = 1_000_000L
    private fun outbox() = Outbox(persistenceDir = null, clock = { now })

    @Test
    fun enqueue_pendingForReturnsItOldestFirst() = runBlocking {
        val box = outbox()
        val first = box.enqueue("vendor", "a".toByteArray(), id = "id-1")
        now += 1
        val second = box.enqueue("vendor", "b".toByteArray(), id = "id-2")
        assertTrue(first is OutboxNote.Enqueued)
        assertTrue(second is OutboxNote.Enqueued)
        val pending = box.pendingFor("vendor")
        assertEquals(listOf("id-1", "id-2"), pending.map { it.id })
        assertEquals(2, box.depth("vendor"))
    }

    @Test
    fun recordAttempt_incrementsUntilCapThenDrops() = runBlocking {
        val box = outbox()
        box.enqueue("vendor", "x".toByteArray(), id = "id-1")
        repeat(Outbox.MAX_ATTEMPTS - 1) { attempt ->
            val note = box.recordAttempt("id-1")
            assertTrue(note is OutboxNote.AttemptRecorded)
            assertEquals(attempt + 1, (note as OutboxNote.AttemptRecorded).message.attempts)
            assertEquals(Outbox.MAX_ATTEMPTS - (attempt + 1), note.remaining)
        }
        val dropped = box.recordAttempt("id-1")
        assertTrue(dropped is OutboxNote.MaxAttemptsReached)
        assertEquals(0, box.depth("vendor"))
        assertTrue(box.recordAttempt("id-1") is OutboxNote.UnknownId)
    }

    @Test
    fun ack_removesEntry() = runBlocking {
        val box = outbox()
        box.enqueue("vendor", "x".toByteArray(), id = "id-1")
        val acked = box.ack("id-1")
        assertTrue(acked is OutboxNote.Acked)
        assertEquals(0, box.depth("vendor"))
        assertTrue(box.ack("id-1") is OutboxNote.UnknownId)
    }

    @Test
    fun sweep_expiresMessagesPastTtl() = runBlocking {
        val box = outbox()
        box.enqueue("vendor", "x".toByteArray(), id = "id-1")
        now += Outbox.TTL_MS + 1
        assertEquals(1, box.sweep())
        assertEquals(0, box.depth("vendor"))
    }

    @Test
    fun recordAttempt_expiredEntry_droppedExpired() = runBlocking {
        val box = outbox()
        box.enqueue("vendor", "x".toByteArray(), id = "id-1")
        now += Outbox.TTL_MS + 1
        assertTrue(box.recordAttempt("id-1") is OutboxNote.DroppedExpired)
    }

    @Test
    fun enqueue_beyondPerPeerCap_evictsOldest() = runBlocking {
        val box = outbox()
        var evicted: OutboxNote.Enqueued? = null
        repeat(Outbox.MAX_PER_PEER + 1) { i ->
            now += 1
            val note = box.enqueue("vendor", "p$i".toByteArray(), id = "id-$i")
            if (i == Outbox.MAX_PER_PEER) evicted = note as OutboxNote.Enqueued
        }
        assertEquals(Outbox.MAX_PER_PEER, box.depth("vendor"))
        assertNotNull(evicted?.evictedOlder)
        assertEquals("id-0", evicted?.evictedOlder?.id)
        assertNull(box.pendingFor("vendor").find { it.id == "id-0" })
    }

    @Test
    fun pendingFlow_reflectsMutations() = runBlocking {
        val box = outbox()
        assertTrue(box.pending.value.isEmpty())
        box.enqueue("vendor", "x".toByteArray(), id = "id-1")
        assertEquals(1, box.pending.value.size)
        box.ack("id-1")
        assertTrue(box.pending.value.isEmpty())
    }

    @Test
    fun clear_emptiesQueue() = runBlocking {
        val box = outbox()
        box.enqueue("a", "x".toByteArray(), id = "id-1")
        box.enqueue("b", "y".toByteArray(), id = "id-2")
        box.clear()
        assertEquals(0, box.depth("a"))
        assertEquals(0, box.depth("b"))
    }
}
