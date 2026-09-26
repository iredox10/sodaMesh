package com.sodamesh.mesh

import com.sodamesh.mesh.model.MeshPacket
import com.sodamesh.mesh.router.DedupCache
import com.sodamesh.mesh.router.MeshPacketFactory
import com.sodamesh.mesh.router.MessageRouter
import com.sodamesh.mesh.router.RelayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [MessageRouter]: [MessageRouter.onReceive]
 * dedup/relay/route, [MessageRouter.route] egress outcomes, and the
 * [MessageRouter.ingressPayload] decap helper for the vendor path.
 */
class MessageRouterTest {

    private val router = MessageRouter(DedupCache(), RelayController()).apply {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    private val captured = mutableListOf<Pair<MeshPacket, List<String>>>()

    @After
    fun tearDown() {
        runCatching { router.close() }
        router.scope.cancel()
        captured.clear()
    }

    private fun testRouter(): MessageRouter {
        captured.clear()
        return MessageRouter(DedupCache(), RelayController()).apply {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            sender = MessageRouter.MeshSender { packet, targets ->
                captured.add(packet to targets)
                1
            }
        }
    }

    private fun orderPacket(
        peerHex: String = "customer-1",
        recipientHex: String? = null,
        ttl: Int = com.sodamesh.mesh.MeshConfig.TTL_DEFAULT,
        payload: ByteArray = "order-payload".toByteArray(),
    ): MeshPacket = MeshPacketFactory.buildOrderPacket(
        senderId = MeshPacketFactory.senderIdForPeerId(peerHex),
        orderBytes = payload,
        recipientId = recipientHex?.let(MeshPacketFactory::senderIdForPeerId),
        ttl = ttl,
    )

    @Test
    fun onReceive_firstSeen_acceptsAndDeliversBroadcastToInbound() = runBlocking {
        val r = testRouter()
        try {
            val received = mutableListOf<MeshPacket>()
            val job = launch(Dispatchers.Unconfined) { r.inbound.collect { received.add(it) } }
            try {
                assertTrue(r.onReceive(orderPacket(), neighbors = listOf("n1")))
                assertEquals(1, received.size)
            } finally {
                job.cancel()
            }
        } finally {
            r.close()
        }
    }

    @Test
    fun onReceive_duplicate_droppedSecondTime() {
        val r = testRouter()
        try {
            val packet = orderPacket()
            assertTrue(r.onReceive(packet))
            assertFalse(r.onReceive(packet))
        } finally {
            r.close()
        }
    }

    @Test
    fun onReceive_ttlZero_neverRelays() {
        val r = testRouter()
        try {
            assertTrue(r.onReceive(orderPacket(ttl = 0), neighbors = listOf("n1")))
            Thread.sleep(400)
            assertTrue(captured.isEmpty())
        } finally {
            r.close()
        }
    }

    @Test
    fun onReceive_broadcast_schedulesRelayExcludingIngress() {
        val r = testRouter()
        try {
            assertTrue(r.onReceive(orderPacket(), ingressId = "n1", neighbors = listOf("n1", "n2")))
            Thread.sleep(600)
            assertEquals(1, captured.size)
            val (forwarded, targets) = captured.single()
            assertEquals(com.sodamesh.mesh.MeshConfig.TTL_DEFAULT - 1, forwarded.ttl)
            assertTrue(targets.contains("n2"))
            assertFalse(targets.contains("n1"))
        } finally {
            r.close()
        }
    }

    @Test
    fun route_broadcast_livePeers_sentLive() = runBlocking {
        val r = testRouter()
        try {
            r.links = object : MessageRouter.LinkView {
                override fun livePeers(): Set<String> = setOf("a", "b")
                override fun isLive(peerId: String): Boolean = true
            }
            assertEquals(MessageRouter.RouteResult.SentLive, r.route(orderPacket()))
            assertEquals(1, captured.size)
        } finally {
            r.close()
        }
    }

    @Test
    fun route_broadcast_noPeers_dropped() = runBlocking {
        val r = testRouter()
        try {
            assertEquals(MessageRouter.RouteResult.Dropped, r.route(orderPacket()))
        } finally {
            r.close()
        }
    }

    @Test
    fun route_directed_noLiveLink_queuedWhenOutboxAccepts() = runBlocking {
        val r = testRouter()
        try {
            r.outbox = MessageRouter.MeshOutbox { _, _ -> true }
            val result = r.route(orderPacket(recipientHex = "vendor-1"))
            assertEquals(MessageRouter.RouteResult.Queued, result)
        } finally {
            r.close()
        }
    }

    @Test
    fun route_directed_noLiveLink_noOutbox_dropped() = runBlocking {
        val r = testRouter()
        try {
            val result = r.route(orderPacket(recipientHex = "vendor-1"))
            assertEquals(MessageRouter.RouteResult.Dropped, result)
        } finally {
            r.close()
        }
    }

    @Test
    fun ingressPayload_legacyRaw_passthroughAsIs() {
        val r = testRouter()
        try {
            val raw = """{"orderId":"legacy-1"}""".toByteArray(Charsets.UTF_8)
            assertArrayEquals(raw, r.ingressPayload(raw))
        } finally {
            r.close()
        }
    }

    @Test
    fun ingressPayload_validBroadcastOrder_returnsPayload() {
        val r = testRouter()
        try {
            val payload = "order-bytes".toByteArray()
            val wire = orderPacket(payload = payload).toBytes()
            assertArrayEquals(payload, r.ingressPayload(wire, neighbors = listOf("n1")))
        } finally {
            r.close()
        }
    }

    @Test
    fun ingressPayload_directedToOther_returnsNull() {
        val r = testRouter()
        try {
            r.setLocalId(MeshPacketFactory.senderIdForPeerId("me"))
            val wire = orderPacket(recipientHex = "someone-else").toBytes()
            assertNull(r.ingressPayload(wire))
        } finally {
            r.close()
        }
    }

    @Test
    fun ingressPayload_directedToMe_returnsPayload() {
        val r = testRouter()
        try {
            r.setLocalId(MeshPacketFactory.senderIdForPeerId("me"))
            val payload = "mine".toByteArray()
            val packet = MeshPacketFactory.buildOrderPacket(
                senderId = MeshPacketFactory.senderIdForPeerId("sender"),
                orderBytes = payload,
                recipientId = MeshPacketFactory.senderIdForPeerId("me"),
            )
            assertArrayEquals(payload, r.ingressPayload(packet.toBytes()))
        } finally {
            r.close()
        }
    }

    @Test
    fun ingressPayload_announce_returnsNull() {
        val r = testRouter()
        try {
            val announce = orderPacket().copy(type = MeshPacket.TYPE_ANNOUNCE)
            assertNull(r.ingressPayload(announce.toBytes(), neighbors = listOf("n1")))
        } finally {
            r.close()
        }
    }
}
