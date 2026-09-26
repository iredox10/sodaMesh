package com.sodamesh.mesh

import com.sodamesh.mesh.model.MeshPacket
import com.sodamesh.mesh.router.MeshPacketFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the order envelope: [MeshPacketFactory] build/extract,
 * wire round-trip via [MeshPacket.toBytes]/[MeshPacket.fromBytes], and the
 * non-throwing [MeshPacket.fromBytesOrNull] used by the ingress path.
 */
class PacketRoundTripTest {

    private val senderId = MeshPacketFactory.senderIdForPeerId("customer-peer-1")
    private val orderBytes = """{"orderId":"o-1","shopId":"SODA-STORE-01"}""".toByteArray(Charsets.UTF_8)

    @Test
    fun buildOrderPacket_broadcast_hasNullRecipientAndDefaultTtl() {
        val packet = MeshPacketFactory.buildOrderPacket(senderId, orderBytes)
        assertEquals(MeshPacket.TYPE_ORDER, packet.type)
        assertEquals(MeshPacket.VERSION_V2, packet.version)
        assertNull(packet.recipientId)
        assertEquals(com.sodamesh.mesh.MeshConfig.TTL_DEFAULT, packet.ttl)
        assertArrayEquals(senderId, packet.senderId)
        assertArrayEquals(orderBytes, packet.payload)
        assertEquals(MeshPacket.SIGNATURE_LEN, packet.signature.size)
    }

    @Test
    fun buildOrderPacket_directed_keepsRecipient() {
        val vendorId = MeshPacketFactory.senderIdForPeerId("vendor-peer-9")
        val packet = MeshPacketFactory.buildOrderPacket(senderId, orderBytes, recipientId = vendorId)
        assertNotNull(packet.recipientId)
        assertArrayEquals(vendorId, packet.recipientId)
    }

    @Test
    fun wireRoundTrip_toBytesFromBytes_preservesFields() {
        val packet = MeshPacketFactory.buildOrderPacket(senderId, orderBytes, timestamp = 1_700_000_000_000L)
        val parsed = MeshPacket.fromBytes(packet.toBytes())
        assertEquals(packet, parsed)
        assertArrayEquals(orderBytes, MeshPacketFactory.extractOrderPayload(parsed))
    }

    @Test
    fun extractOrderPayload_nonOrderType_returnsNull() {
        val announce = MeshPacketFactory.buildOrderPacket(senderId, orderBytes)
            .copy(type = MeshPacket.TYPE_ANNOUNCE)
        assertNull(MeshPacketFactory.extractOrderPayload(announce))
    }

    @Test
    fun fromBytesOrNull_malformed_returnsNull() {
        assertNull(MeshPacket.fromBytesOrNull(byteArrayOf()))
        assertNull(MeshPacket.fromBytesOrNull(orderBytes)) // legacy raw payload: not a packet
        assertNull(MeshPacket.fromBytesOrNull(ByteArray(MeshPacket.BASE_OVERHEAD_BYTES)))
    }

    @Test
    fun fromBytesOrNull_valid_returnsPacket() {
        val packet = MeshPacketFactory.buildOrderPacket(senderId, orderBytes)
        assertEquals(packet, MeshPacket.fromBytesOrNull(packet.toBytes()))
    }

    @Test
    fun senderIdForPeerId_deterministicEightBytes() {
        val a = MeshPacketFactory.senderIdForPeerId("same-peer")
        val b = MeshPacketFactory.senderIdForPeerId("same-peer")
        assertEquals(MeshPacket.ID_LEN, a.size)
        assertArrayEquals(a, b)
    }

    @Test
    fun placeholderSignature_deterministicAndSized() {
        val s1 = MeshPacketFactory.placeholderSignature(senderId, 123L, orderBytes)
        val s2 = MeshPacketFactory.placeholderSignature(senderId, 123L, orderBytes)
        assertEquals(MeshPacket.SIGNATURE_LEN, s1.size)
        assertArrayEquals(s1, s2)
    }

    @Test
    fun buildOrderPacket_copiesInputs_defensive() {
        val mutable = orderBytes.copyOf()
        val packet = MeshPacketFactory.buildOrderPacket(senderId, mutable)
        mutable[0] = 0
        assertTrue(packet.payload.contentEquals(orderBytes))
    }
}
