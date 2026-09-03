package io.github.nandishn.koog.checkpoint.aws

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class CheckpointCodecTest {
    @Test
    fun `json gzip codec round trips koog checkpoint data`() {
        val config = testConfig()
        val codec = JsonCheckpointCodec(Compression.Gzip)
        val source = checkpoint(
            id = "checkpoint-1",
            version = 42,
            createdAt = Instant.parse("2026-09-03T12:00:00Z"),
            nodePath = "classify-intent",
        )
        val encoded = codec.encode(source)
        val keys = KeyFactory(config).keysFor("agent-1", source, encoded.sha256, source.createdAt)
        val metadata = CheckpointMetadataFactory.create(keys, source, encoded, source.createdAt, config)

        val decoded = codec.decode(encoded.bytes, metadata)

        assertEquals(source.checkpointId, decoded.checkpointId)
        assertEquals(source.version, decoded.version)
        assertEquals(source.createdAt, decoded.createdAt)
        assertEquals(source.graphProperties?.nodePath, decoded.graphProperties?.nodePath)
        assertTrue(encoded.compressedBytes > 0)
    }
}
