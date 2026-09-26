package com.sodamesh.mesh

import com.sodamesh.mesh.router.MeshPacketFactory
import com.sodamesh.mesh.router.RelayController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Pure-JVM tests for [RelayController]: dense/thin/directed relay
 * decisions, TTL handling, ingress exclusion, and jitter bounds.
 */
class RelayControllerTest {

    private val relay = RelayController()

    private fun broadcast(ttl: Int = com.sodamesh.mesh.MeshConfig.TTL_DEFAULT) =
        MeshPacketFactory.buildOrderPacket(
            senderId = MeshPacketFactory.senderIdForPeerId("sender"),
            orderBytes = "payload".toByteArray(),
            ttl = ttl,
        )

    private fun directed(ttl: Int = com.sodamesh.mesh.MeshConfig.TTL_DEFAULT) =
        MeshPacketFactory.buildOrderPacket(
            senderId = MeshPacketFactory.senderIdForPeerId("sender"),
            orderBytes = "payload".toByteArray(),
            recipientId = MeshPacketFactory.senderIdForPeerId("vendor"),
            ttl = ttl,
        )

    @Test
    fun decide_ttlZero_neverRelays() {
        val decision = relay.decide(broadcast(ttl = 0), degree = 8)
        assertFalse(decision.shouldRelay)
        assertEquals(0, decision.fanoutCount)
        assertTrue(decision.targets.isEmpty())
    }

    @Test
    fun decide_directed_fullDepthTtlMinusOneAndFullFanout() {
        val neighbors = listOf("a", "b", "c")
        val decision = relay.decide(directed(ttl = 7), degree = 8, neighbors = neighbors)
        assertTrue(decision.shouldRelay)
        assertEquals(6, decision.newTtl)
        assertEquals(3, decision.fanoutCount)
        assertEquals(neighbors, decision.targets)
    }

    @Test
    fun decide_directed_excludesIngress() {
        val decision = relay.decide(
            directed(ttl = 5),
            degree = 3,
            ingressId = "b",
            neighbors = listOf("a", "b", "c"),
        )
        assertTrue(decision.shouldRelay)
        assertEquals(4, decision.newTtl)
        assertEquals(listOf("a", "c"), decision.targets)
    }

    @Test
    fun decide_directed_denseMesh_noTtlClamp() {
        // Directed packets keep full TTL depth even in dense meshes.
        val decision = relay.decide(directed(ttl = 7), degree = 12)
        assertTrue(decision.shouldRelay)
        assertEquals(6, decision.newTtl)
    }

    @Test
    fun decide_broadcast_denseMesh_clampsTtlAndSubsetsFanout() {
        // degree 8: clamp to DENSE_TTL_CAP, fanout ceil(log2(8)) = 3.
        val decision = relay.decide(broadcast(ttl = 7), degree = 8)
        assertTrue(decision.shouldRelay)
        assertEquals(RelayController.DENSE_TTL_CAP, decision.newTtl)
        assertEquals(3, decision.fanoutCount)
    }

    @Test
    fun decide_broadcast_thinMesh_fullDepthAndFullFanout() {
        // degree 2: no clamp, fan out to everyone.
        val decision = relay.decide(broadcast(ttl = 7), degree = 2)
        assertTrue(decision.shouldRelay)
        assertEquals(6, decision.newTtl)
        assertEquals(2, decision.fanoutCount)
    }

    @Test
    fun decide_broadcast_midMesh_logFanout() {
        // degree 4: fanout ceil(log2(4)) = 2, TTL decremented but unclamped.
        val decision = relay.decide(broadcast(ttl = 7), degree = 4)
        assertTrue(decision.shouldRelay)
        assertEquals(6, decision.newTtl)
        assertEquals(2, decision.fanoutCount)
    }

    @Test
    fun decide_broadcast_subsetExcludesIngress() {
        val neighbors = (1..8).map { "n$it" }
        val decision = relay.decide(
            broadcast(),
            degree = 8,
            ingressId = "n1",
            neighbors = neighbors,
            random = Random(42),
        )
        assertTrue(decision.shouldRelay)
        assertEquals(3, decision.targets.size)
        assertFalse(decision.targets.contains("n1"))
    }

    @Test
    fun decide_jitter_withinBounds() {
        repeat(200) {
            val decision = relay.decide(broadcast(), degree = 4)
            assertTrue(decision.delayMs in RelayController.JITTER_MIN_MS..RelayController.JITTER_MAX_MS)
        }
    }

    @Test
    fun decide_jitter_seededRandom_stillBounded() {
        val random = Random(1234)
        repeat(50) {
            val decision = relay.decide(directed(), degree = 3, random = random)
            assertTrue(decision.delayMs in RelayController.JITTER_MIN_MS..RelayController.JITTER_MAX_MS)
        }
    }

    @Test
    fun isDirected_matchesRecipientPresence() {
        assertFalse(relay.isDirected(broadcast()))
        assertTrue(relay.isDirected(directed()))
    }

    @Test
    fun companions_ttlHelpers() {
        assertEquals(0, RelayController.clampTtl(-3))
        assertEquals(RelayController.TTL_MAX, RelayController.clampTtl(99))
        assertTrue(RelayController.shouldRelay(ttl = 1, alreadySeen = false))
        assertFalse(RelayController.shouldRelay(ttl = 0, alreadySeen = false))
        assertFalse(RelayController.shouldRelay(ttl = 7, alreadySeen = true))
        assertEquals(0, RelayController.decremented(0))
        assertEquals(6, RelayController.decremented(7))
    }
}
