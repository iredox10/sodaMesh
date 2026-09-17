package com.sodamesh.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM (no Android deps) tests for the relay/TTL contract.
 *
 * NOTE — self-contained by design: [TestRelayPolicy] mirrors the production
 * contract the mesh owning agent must implement (TTL clamped to
 * [TTL_MAX], cf. MeshConfig.TTL_DEFAULT; relay iff unseen and ttl > 0;
 * decrement per hop). It lets these tests compile and pass before the
 * production Relay lands; swap the double for the real class once
 * available, keeping the same assertions.
 */
object TestRelayPolicy {
    const val TTL_MAX = 7 // mirrors MeshConfig.TTL_DEFAULT

    /** Clamp an incoming TTL into the legal range [0, max]. */
    fun clampTtl(ttl: Int, max: Int = TTL_MAX): Int = ttl.coerceIn(0, max)

    /** Relay iff the message is unseen and still has hops left. */
    fun shouldRelay(ttl: Int, alreadySeen: Boolean): Boolean = !alreadySeen && ttl > 0

    /** TTL to stamp on the forwarded copy (never below zero). */
    fun decremented(ttl: Int): Int = (ttl - 1).coerceAtLeast(0)
}

class RelayTest {

    @Test
    fun clampTtl_negative_clampsToZero() {
        assertEquals(0, TestRelayPolicy.clampTtl(-1))
        assertEquals(0, TestRelayPolicy.clampTtl(Int.MIN_VALUE))
    }

    @Test
    fun clampTtl_aboveMax_clampsToMax() {
        assertEquals(TestRelayPolicy.TTL_MAX, TestRelayPolicy.clampTtl(TestRelayPolicy.TTL_MAX + 1))
        assertEquals(TestRelayPolicy.TTL_MAX, TestRelayPolicy.clampTtl(Int.MAX_VALUE))
    }

    @Test
    fun clampTtl_inRange_passesThrough() {
        assertEquals(0, TestRelayPolicy.clampTtl(0))
        assertEquals(1, TestRelayPolicy.clampTtl(1))
        assertEquals(TestRelayPolicy.TTL_MAX, TestRelayPolicy.clampTtl(TestRelayPolicy.TTL_MAX))
    }

    @Test
    fun clampTtl_customMax_usesProvidedBound() {
        assertEquals(3, TestRelayPolicy.clampTtl(99, max = 3))
        assertEquals(0, TestRelayPolicy.clampTtl(-5, max = 3))
        assertEquals(2, TestRelayPolicy.clampTtl(2, max = 3))
    }

    @Test
    fun shouldRelay_freshMessageWithHops_returnsTrue() {
        assertTrue(TestRelayPolicy.shouldRelay(ttl = 7, alreadySeen = false))
        assertTrue(TestRelayPolicy.shouldRelay(ttl = 1, alreadySeen = false))
    }

    @Test
    fun shouldRelay_zeroTtl_returnsFalse() {
        assertFalse(TestRelayPolicy.shouldRelay(ttl = 0, alreadySeen = false))
    }

    @Test
    fun shouldRelay_alreadySeen_returnsFalse() {
        assertFalse(TestRelayPolicy.shouldRelay(ttl = 7, alreadySeen = true))
    }

    @Test
    fun relayChain_decrementsUntilDrop() {
        var ttl = TestRelayPolicy.clampTtl(3)
        var hops = 0
        while (TestRelayPolicy.shouldRelay(ttl, alreadySeen = false)) {
            ttl = TestRelayPolicy.decremented(ttl)
            hops++
        }
        assertEquals(3, hops)
        assertEquals(0, ttl)
        assertFalse(TestRelayPolicy.shouldRelay(ttl, alreadySeen = false))
    }

    @Test
    fun decremented_neverGoesNegative() {
        assertEquals(0, TestRelayPolicy.decremented(0))
        assertEquals(0, TestRelayPolicy.decremented(-4))
        assertEquals(6, TestRelayPolicy.decremented(7))
    }
}
