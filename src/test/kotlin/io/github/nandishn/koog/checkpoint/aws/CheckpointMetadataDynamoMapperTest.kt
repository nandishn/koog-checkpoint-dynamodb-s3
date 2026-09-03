package io.github.nandishn.koog.checkpoint.aws

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
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

    @Test
    fun `metadata defaults to thirty day ttl`() {
        val config = testConfig()
        val storedAt = Instant.parse("2026-09-03T12:00:00Z")
        val source = checkpoint(id = "checkpoint-1", version = 1, createdAt = storedAt)
        val encoded = JsonCheckpointCodec(Compression.Gzip).encode(source)
        val keys = KeyFactory(config).keysFor("agent-1", source, encoded.sha256, storedAt)
        val metadata = CheckpointMetadataFactory.create(keys, source, encoded, storedAt, config)

        val item = CheckpointMetadataDynamoMapper.checkpointItem(metadata)

        assertEquals(storedAt.plus(DynamoDbS3PersistenceConfig.DEFAULT_TTL).epochSeconds, metadata.expiresAtEpochSeconds)
        assertTrue(item.containsKey("expiresAt"))
    }

    @Test
    fun `metadata omits expiresAt when ttl is disabled`() {
        val config = testConfig { ttl = null }
        val storedAt = Instant.parse("2026-09-03T12:00:00Z")
        val source = checkpoint(id = "checkpoint-1", version = 1, createdAt = storedAt)
        val encoded = JsonCheckpointCodec(Compression.Gzip).encode(source)
        val keys = KeyFactory(config).keysFor("agent-1", source, encoded.sha256, storedAt)
        val metadata = CheckpointMetadataFactory.create(keys, source, encoded, storedAt, config)

        val item = CheckpointMetadataDynamoMapper.checkpointItem(metadata)

        assertEquals(null, metadata.expiresAtEpochSeconds)
        assertFalse(item.containsKey("expiresAt"))
    }

    @Test
    fun `ttl must be whole days for s3 lifecycle alignment`() {
        assertFailsWith<IllegalArgumentException> {
            testConfig { ttl = 36.hours }
        }
    }
}
