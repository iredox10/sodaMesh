package com.sodamesh.mesh.router

import com.sodamesh.mesh.MeshConfig
import com.sodamesh.mesh.model.MeshPacket
import java.security.MessageDigest

/**
 * Envelope helpers for wrapping order payloads in [MeshPacket] (signed TTL
 * envelope, 8-byte ids) and extracting them again.
 *
 * - [buildOrderPacket] wraps already-framed `OrderCodec` bytes as a
 *   `TYPE_ORDER` broadcast (`recipientId = null`, so the [RelayController]
 *   flood policy applies) with [MeshConfig.TTL_DEFAULT] hops.
 * - [extractOrderPayload] returns the payload iff the packet carries
 *   `TYPE_ORDER`, else null.
 * - [senderIdForPeerId] deterministically derives the 8-byte wire sender id
 *   from a stable peer string (e.g. `PrefsStore` peerId): SHA-256, first 8
 *   bytes. Deterministic so retries of the same node reuse one identity and
 *   [DedupCache] stays effective.
 *
 * Signing note: [CryptoManager.sign] needs an Ed25519 private key and there
 * is no key-management owner yet, so the envelope currently stamps a
 * deterministic 64-byte placeholder signature (SHA-256 over
 * `senderId|timestamp|payload`, duplicated). Ingress does NOT verify
 * signatures yet; when real Ed25519 signing lands, replace
 * [placeholderSignature] and verify on the ingress path. Pure JVM.
 */
object MeshPacketFactory {

    /**
     * Wraps [orderBytes] (already-framed `OrderCodec` payload) in a
     * `TYPE_ORDER` [MeshPacket].
     *
     * @param senderId 8-byte wire identity of this node (see [senderIdForPeerId]).
     * @param recipientId null for broadcast (relay flood policy), or an 8-byte
     *   vendor id for directed delivery.
     */
    fun buildOrderPacket(
        senderId: ByteArray,
        orderBytes: ByteArray,
        recipientId: ByteArray? = null,
        ttl: Int = MeshConfig.TTL_DEFAULT,
        timestamp: Long = System.currentTimeMillis(),
    ): MeshPacket {
        require(senderId.size == MeshPacket.ID_LEN) {
            "senderId must be ${MeshPacket.ID_LEN} bytes"
        }
        require(recipientId == null || recipientId.size == MeshPacket.ID_LEN) {
            "recipientId must be null or ${MeshPacket.ID_LEN} bytes"
        }
        return MeshPacket(
            version = MeshPacket.VERSION_V2,
            type = MeshPacket.TYPE_ORDER,
            ttl = ttl.coerceIn(0, 255),
            timestamp = timestamp,
            senderId = senderId.copyOf(),
            recipientId = recipientId?.copyOf(),
            payload = orderBytes.copyOf(),
            signature = placeholderSignature(senderId, timestamp, orderBytes),
        )
    }

    /**
     * Returns a copy of [packet]'s payload when it carries `TYPE_ORDER`,
     * null for any other type (ANNOUNCE/ACK/...).
     */
    fun extractOrderPayload(packet: MeshPacket): ByteArray? =
        if (packet.type == MeshPacket.TYPE_ORDER) packet.payload.copyOf() else null

    /**
     * Deterministic 8-byte wire sender id for a stable peer string.
     * SHA-256(peerId UTF-8), first 8 bytes.
     */
    fun senderIdForPeerId(peerId: String): ByteArray {
        require(peerId.isNotBlank()) { "peerId must not be blank" }
        return MessageDigest.getInstance("SHA-256")
            .digest(peerId.toByteArray(Charsets.UTF_8))
            .copyOf(MeshPacket.ID_LEN)
    }

    /**
     * Deterministic 64-byte placeholder signature until Ed25519 key
     * management lands (see class KDoc): SHA-256 over
     * `senderId|timestamp-BE|payload`, duplicated to [MeshPacket.SIGNATURE_LEN].
     */
    fun placeholderSignature(senderId: ByteArray, timestamp: Long, payload: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(senderId)
        for (shift in 56 downTo 0 step 8) md.update((timestamp ushr shift).toByte())
        md.update(payload)
        val digest = md.digest()
        return ByteArray(MeshPacket.SIGNATURE_LEN) { digest[it % digest.size] }
    }
}
