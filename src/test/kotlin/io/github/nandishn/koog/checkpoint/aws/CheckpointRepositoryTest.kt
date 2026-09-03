package io.github.nandishn.koog.checkpoint.aws

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
