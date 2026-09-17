package com.sodamesh.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM (no Android deps) tests for the fragmentation contract.
 *
 * NOTE — self-contained by design: the [TestFragmenter]/[TestReassembler]
 * doubles below mirror the production contract the BLE owning agent must
 * implement (messageId/index/total header + [FRAG_MTU]-sized payloads, cf.
 * MeshConfig.FRAG_MTU). They let these tests compile and pass before the
 * production Fragmenter lands; swap the doubles for the real classes once
 * available, keeping the same assertions.
 */
private const val FRAG_MTU = 469 // mirrors MeshConfig.FRAG_MTU

/** Wire shape: header (messageId/index/total) + one MTU-bounded chunk. */
data class MeshFragment(
    val messageId: String,
    val index: Int,
    val total: Int,
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshFragment) return false
        return messageId == other.messageId &&
            index == other.index &&
            total == other.total &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = messageId.hashCode()
        result = 31 * result + index
        result = 31 * result + total
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

object TestFragmenter {
    fun split(messageId: String, bytes: ByteArray, mtu: Int = FRAG_MTU): List<MeshFragment> {
        require(mtu > 0) { "mtu must be positive" }
        if (bytes.isEmpty()) return listOf(MeshFragment(messageId, 0, 1, byteArrayOf()))
        val chunks = bytes.asList().chunked(mtu).map { it.toByteArray() }
        return chunks.mapIndexed { i, chunk -> MeshFragment(messageId, i, chunks.size, chunk) }
    }
}

class TestReassembler {
    private val parts = mutableMapOf<Int, ByteArray>()
    private var total: Int = -1

    /** Returns the full payload once every piece arrived, else null. */
    fun add(fragment: MeshFragment): ByteArray? {
        if (fragment.index < 0 || fragment.index >= fragment.total) return null
        if (total == -1) {
            total = fragment.total
        } else if (total != fragment.total) {
            return null // inconsistent header — ignore
        }
        parts.putIfAbsent(fragment.index, fragment.payload) // duplicates ignored
        if (parts.size != total) return null
        val out = ByteArray(parts.values.sumOf { it.size })
        var offset = 0
        for (i in 0 until total) {
            val chunk = parts[i] ?: return null
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }
}

class FragmenterTest {

    @Test
    fun split_smallPayload_yieldsSingleFragment() {
        val bytes = "small order".toByteArray()
        val frags = TestFragmenter.split("m1", bytes)

        assertEquals(1, frags.size)
        assertEquals(MeshFragment("m1", 0, 1, bytes), frags[0])
    }

    @Test
    fun split_emptyPayload_yieldsOneEmptyFragment() {
        val frags = TestFragmenter.split("m-empty", byteArrayOf())

        assertEquals(1, frags.size)
        assertEquals(0, frags[0].payload.size)
    }

    @Test
    fun split_largePayload_respectsMtuAndCoversAllBytes() {
        val bytes = ByteArray(FRAG_MTU * 2 + 100) { it.toByte() }
        val frags = TestFragmenter.split("m2", bytes)

        assertEquals(3, frags.size)
        frags.forEachIndexed { i, f ->
            assertEquals("m2", f.messageId)
            assertEquals(i, f.index)
            assertEquals(3, f.total)
            assertTrue("chunk $i exceeds mtu", f.payload.size <= FRAG_MTU)
        }
        assertEquals(FRAG_MTU, frags[0].payload.size)
        assertEquals(FRAG_MTU, frags[1].payload.size)
        assertEquals(100, frags[2].payload.size)
        val joined = frags.flatMap { it.payload.asList() }.toByteArray()
        assertArrayEquals(bytes, joined)
    }

    @Test
    fun split_exactMultipleOfMtu_hasNoTrailingEmptyChunk() {
        val bytes = ByteArray(FRAG_MTU * 2) { 7 }
        val frags = TestFragmenter.split("m3", bytes)

        assertEquals(2, frags.size)
        frags.forEach { assertEquals(FRAG_MTU, it.payload.size) }
    }

    @Test
    fun reassemble_inOrder_returnsOriginalBytes() {
        val bytes = ByteArray(1200) { (it * 31).toByte() }
        val frags = TestFragmenter.split("m4", bytes)
        val reassembler = TestReassembler()

        var done: ByteArray? = null
        frags.forEach { done = reassembler.add(it) }

        assertNotNull(done)
        assertArrayEquals(bytes, done)
    }

    @Test
    fun reassemble_outOfOrder_returnsOriginalBytes() {
        val bytes = ByteArray(1000) { (it * 17).toByte() }
        val frags = TestFragmenter.split("m5", bytes)
        val reassembler = TestReassembler()

        var done: ByteArray? = null
        frags.reversed().forEach { done = reassembler.add(it) }

        assertNotNull(done)
        assertArrayEquals(bytes, done)
    }

    @Test
    fun reassemble_duplicateFragment_stillCompletes() {
        val bytes = ByteArray(900) { it.toByte() }
        val frags = TestFragmenter.split("m6", bytes)
        val reassembler = TestReassembler()

        assertNull(reassembler.add(frags[0]))
        assertNull(reassembler.add(frags[0])) // duplicate — must not corrupt
        var done: ByteArray? = null
        frags.drop(1).forEach { done = reassembler.add(it) }

        assertNotNull(done)
        assertArrayEquals(bytes, done)
    }

    @Test
    fun reassemble_incomplete_returnsNull() {
        val bytes = ByteArray(1000) { it.toByte() }
        val frags = TestFragmenter.split("m7", bytes)
        val reassembler = TestReassembler()

        frags.dropLast(1).forEach { assertNull(reassembler.add(it)) }
    }
}
