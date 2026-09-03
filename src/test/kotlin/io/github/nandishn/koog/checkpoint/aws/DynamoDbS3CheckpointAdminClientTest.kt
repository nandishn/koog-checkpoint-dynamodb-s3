package io.github.nandishn.koog.checkpoint.aws

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DynamoDbS3CheckpointAdminClientTest {
    @Test
    fun `admin client inspects session without exposing raw ids`() = runTest {
        val config = testConfig()
        val repository = testRepository(config)
        val admin = DynamoDbS3CheckpointAdminClient(repository)

        repository.save("tenant-1:conversation-1", checkpoint("checkpoint-1", 1))

        val summary = admin.inspectSession("tenant-1:conversation-1")

        assertEquals(1, summary.checkpointCount)
        assertFalse(summary.sessionIdHash.contains("tenant-1"))
        assertEquals(1, summary.checkpoints.size)
    }

    @Test
    fun `admin client verifies healthy payloads and deletes checkpoints`() = runTest {
        val config = testConfig()
        val repository = testRepository(config)
        val admin = DynamoDbS3CheckpointAdminClient(repository)

        repository.save("agent-1", checkpoint("checkpoint-1", 1))

        assertTrue(admin.verifySession("agent-1").isHealthy)
        assertTrue(admin.deleteCheckpoint("agent-1", "checkpoint-1"))
        assertEquals(0, admin.inspectSession("agent-1").checkpointCount)
    }
}
