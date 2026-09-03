package io.github.nandishn.koog.checkpoint.aws

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class CheckpointMetadataDynamoMapperTest {
    @Test
    fun `metadata item does not store raw checkpoint id`() {
        val config = testConfig()
        val source = checkpoint(
            id = "tenant-123:user-456:checkpoint-789",
            version = 1,
            createdAt = Instant.parse("2026-09-03T12:00:00Z"),
        )
        val encoded = JsonCheckpointCodec(Compression.Gzip).encode(source)
        val keys = KeyFactory(config).keysFor("agent-1", source, encoded.sha256, source.createdAt)
        val metadata = CheckpointMetadataFactory.create(keys, source, encoded, source.createdAt, config)

        val item = CheckpointMetadataDynamoMapper.checkpointItem(metadata)

        assertFalse(item.containsKey("checkpointId"))
        assertTrue(item.containsKey("checkpointIdHash"))
    }
}
