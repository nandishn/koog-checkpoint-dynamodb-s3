package io.github.nandishn.koog.checkpoint.aws

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class KeyFactoryTest {
    @Test
    fun `s3 key does not contain raw session material`() {
        val config = testConfig()
        val factory = KeyFactory(config)
        val rawAgentId = "tenant-123:user-456:conversation-789"

        val keys = factory.keysFor(
            agentId = rawAgentId,
            checkpoint = checkpoint("checkpoint-1", 1),
            payloadSha256 = "abc123",
            storedAt = Instant.parse("2026-09-03T12:00:01Z"),
        )

        assertFalse(keys.s3Key.contains("tenant-123"))
        assertFalse(keys.s3Key.contains("user-456"))
        assertFalse(keys.s3Key.contains("conversation-789"))
        assertTrue(keys.s3Key.contains("sha256=abc123"))
    }

    @Test
    fun `sort keys are deterministic for same-version siblings`() {
        val config = testConfig()
        val factory = KeyFactory(config)
        val storedAt = Instant.parse("2026-09-03T12:00:01Z")

        val first = factory.keysFor("agent", checkpoint("a", 7), "sha-a", storedAt)
        val second = factory.keysFor("agent", checkpoint("b", 7), "sha-b", storedAt)

        assertTrue(first.checkpointSk.startsWith("CP#V#00000000000000000007"))
        assertTrue(second.checkpointSk.startsWith("CP#V#00000000000000000007"))
        assertFalse(first.checkpointSk == second.checkpointSk)
    }

    @Test
    fun `same agent id hashes consistently`() {
        val config = testConfig()
        val factory = KeyFactory(config)

        assertEquals(factory.agentIdHash("agent-1"), factory.agentIdHash("agent-1"))
    }
}
