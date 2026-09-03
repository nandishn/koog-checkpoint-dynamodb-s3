package io.github.nandishn.koog.checkpoint.aws

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class CheckpointRepositoryTest {
    @Test
    fun `saves and loads latest checkpoint`() = runTest {
        val config = testConfig()
        val repository = testRepository(config)

        repository.save("agent-1", checkpoint("first", 1))
        repository.save("agent-1", checkpoint("second", 2))

        val latest = repository.getLatest("agent-1", DynamoDbS3CheckpointFilter())

        assertNotNull(latest)
        assertEquals("second", latest.checkpointId)
    }

    @Test
    fun `duplicate checkpoint with same checksum is idempotent`() = runTest {
        val clock = MutableClock(Instant.parse("2026-09-03T12:00:00Z"))
        val config = testConfig { this.clock = clock }
        val repository = testRepository(config)
        val sameCheckpoint = checkpoint("same", 1)

        repository.save("agent-1", sameCheckpoint)
        repository.save("agent-1", sameCheckpoint)

        val checkpoints = repository.list("agent-1", DynamoDbS3CheckpointFilter())
        assertEquals(1, checkpoints.size)
    }

    @Test
    fun `metadata commit timeout preserves payload and treats save as successful`() = runTest {
        val config = testConfig()
        val metadataStore = FakeMetadataStore().apply {
            failAfterPut = IllegalStateException("timeout after commit")
        }
        val payloadStore = FakePayloadStore()
        val repository = testRepository(config, metadataStore, payloadStore)

        repository.save("agent-1", checkpoint("first", 1))

        assertEquals(emptyList(), payloadStore.deletedKeys)
        val latest = repository.getLatest("agent-1", DynamoDbS3CheckpointFilter())
        assertNotNull(latest)
        assertEquals("first", latest.checkpointId)
    }

    @Test
    fun `metadata write failure deletes payload when checkpoint is absent`() = runTest {
        val config = testConfig()
        val metadataStore = FakeMetadataStore().apply {
            failBeforePut = IllegalStateException("metadata unavailable")
        }
        val payloadStore = FakePayloadStore()
        val repository = testRepository(config, metadataStore, payloadStore)

        assertFailsWith<IllegalStateException> {
            repository.save("agent-1", checkpoint("first", 1))
        }

        assertEquals(1, payloadStore.deletedKeys.size)
        assertNull(repository.getLatest("agent-1", DynamoDbS3CheckpointFilter()))
    }

    @Test
    fun `metadata write failure preserves payload when outcome cannot be verified`() = runTest {
        val config = testConfig()
        val metadataStore = FakeMetadataStore().apply {
            failBeforePut = IllegalStateException("metadata timeout")
            failLookup = IllegalStateException("lookup unavailable")
        }
        val payloadStore = FakePayloadStore()
        val repository = testRepository(config, metadataStore, payloadStore)

        val error = assertFailsWith<IllegalStateException> {
            repository.save("agent-1", checkpoint("first", 1))
        }

        assertEquals("metadata timeout", error.message)
        assertEquals(1, error.suppressed.size)
        assertEquals(emptyList(), payloadStore.deletedKeys)
    }

    @Test
    fun `save writes same default ttl markers to payload metadata`() = runTest {
        val storedAt = Instant.parse("2026-09-03T12:00:00Z")
        val config = testConfig {
            clock = MutableClock(storedAt)
        }
        val payloadStore = FakePayloadStore()
        val repository = testRepository(config, payloadStore = payloadStore)

        repository.save("agent-1", checkpoint("first", 1))

        val payloadMetadata = payloadStore.metadataByKey.values.single()
        val expectedExpiresAt = storedAt.plus(DynamoDbS3PersistenceConfig.DEFAULT_TTL).epochSeconds
        assertEquals(30L, payloadMetadata.ttlDays)
        assertEquals(expectedExpiresAt, payloadMetadata.expiresAtEpochSeconds)
        assertEquals("30", payloadMetadata.tags["koog-ttl-days"])
        assertEquals(expectedExpiresAt.toString(), payloadMetadata.tags["koog-expires-at"])
    }

    @Test
    fun `save writes same custom ttl markers to payload metadata`() = runTest {
        val storedAt = Instant.parse("2026-09-03T12:00:00Z")
        val config = testConfig {
            ttl = 7.days
            clock = MutableClock(storedAt)
        }
        val payloadStore = FakePayloadStore()
        val repository = testRepository(config, payloadStore = payloadStore)

        repository.save("agent-1", checkpoint("first", 1))

        val payloadMetadata = payloadStore.metadataByKey.values.single()
        val expectedExpiresAt = storedAt.plus(7.days).epochSeconds
        assertEquals(7L, payloadMetadata.ttlDays)
        assertEquals(expectedExpiresAt, payloadMetadata.expiresAtEpochSeconds)
        assertEquals("7", payloadMetadata.tags["koog-ttl-days"])
        assertEquals(expectedExpiresAt.toString(), payloadMetadata.tags["koog-expires-at"])
    }

    @Test
    fun `save omits ttl markers from payload metadata when ttl is disabled`() = runTest {
        val config = testConfig { ttl = null }
        val payloadStore = FakePayloadStore()
        val repository = testRepository(config, payloadStore = payloadStore)

        repository.save("agent-1", checkpoint("first", 1))

        val payloadMetadata = payloadStore.metadataByKey.values.single()
        assertEquals(null, payloadMetadata.ttlDays)
        assertEquals(null, payloadMetadata.expiresAtEpochSeconds)
        assertFalse(payloadMetadata.tags.containsKey("koog-ttl-days"))
        assertFalse(payloadMetadata.tags.containsKey("koog-expires-at"))
    }

    @Test
    fun `checkpoint id filter uses hashed lookup metadata`() = runTest {
        val config = testConfig()
        val repository = testRepository(config)

        repository.save("agent-1", checkpoint("first", 1))
        repository.save("agent-1", checkpoint("second", 2))

        val latest = repository.getLatest("agent-1", DynamoDbS3CheckpointFilter(checkpointId = "first"))
        val listed = repository.list("agent-1", DynamoDbS3CheckpointFilter(checkpointId = "second"))

        assertNotNull(latest)
        assertEquals("first", latest.checkpointId)
        assertEquals(listOf("second"), listed.map { it.checkpointId })
    }

    @Test
    fun `corrupt latest checkpoint can be skipped`() = runTest {
        val config = testConfig {
            corruptCheckpointPolicy = CorruptCheckpointPolicy.SkipAndContinue
        }
        val payloadStore = FakePayloadStore()
        val repository = testRepository(config, payloadStore = payloadStore)

        repository.save("agent-1", checkpoint("first", 1))
        repository.save("agent-1", checkpoint("second", 2))
        payloadStore.corruptReads = true

        assertNull(repository.getLatest("agent-1", DynamoDbS3CheckpointFilter()))
    }

    @Test
    fun `non checkpoint load failures are not skipped`() = runTest {
        val config = testConfig {
            corruptCheckpointPolicy = CorruptCheckpointPolicy.SkipAndContinue
        }
        val payloadStore = FakePayloadStore()
        val repository = testRepository(config, payloadStore = payloadStore)

        repository.save("agent-1", checkpoint("first", 1))
        payloadStore.failReads = IllegalStateException("aws unavailable")

        assertFailsWith<IllegalStateException> {
            repository.getLatest("agent-1", DynamoDbS3CheckpointFilter())
        }
    }

    @Test
    fun `latest query caps metadata scanned when filters do not match`() = runTest {
        val config = testConfig {
            maxListPageSize = 2
            maxCheckpointsPerList = 3
        }
        val repository = testRepository(config)

        repository.save("agent-1", checkpoint("first", 1, nodePath = "one"))
        repository.save("agent-1", checkpoint("second", 2, nodePath = "two"))
        repository.save("agent-1", checkpoint("third", 3, nodePath = "three"))
        repository.save("agent-1", checkpoint("fourth", 4, nodePath = "four"))

        assertFailsWith<TooManyCheckpointsException> {
            repository.getLatest("agent-1", DynamoDbS3CheckpointFilter(nodePath = "missing"))
        }
    }

    @Test
    fun `list query caps metadata scanned when filters do not match`() = runTest {
        val config = testConfig {
            maxListPageSize = 2
            maxCheckpointsPerList = 3
        }
        val repository = testRepository(config)

        repository.save("agent-1", checkpoint("first", 1, nodePath = "one"))
        repository.save("agent-1", checkpoint("second", 2, nodePath = "two"))
        repository.save("agent-1", checkpoint("third", 3, nodePath = "three"))
        repository.save("agent-1", checkpoint("fourth", 4, nodePath = "four"))

        assertFailsWith<TooManyCheckpointsException> {
            repository.list("agent-1", DynamoDbS3CheckpointFilter(nodePath = "missing"))
        }
    }
}
