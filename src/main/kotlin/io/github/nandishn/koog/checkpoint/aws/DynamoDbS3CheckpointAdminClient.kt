package io.github.nandishn.koog.checkpoint.aws

import kotlin.time.Instant

class DynamoDbS3CheckpointAdminClient internal constructor(
    private val repository: CheckpointRepository,
) {
    suspend fun inspectSession(
        sessionId: String,
        limit: Int = DEFAULT_ADMIN_LIMIT,
    ): SessionCheckpointSummary =
        repository.inspect(sessionId, limit)

    suspend fun verifySession(
        sessionId: String,
        limit: Int = DEFAULT_ADMIN_LIMIT,
    ): CheckpointVerificationReport =
        repository.verify(sessionId, limit)

    suspend fun deleteCheckpoint(sessionId: String, checkpointId: String): Boolean =
        repository.deleteCheckpoint(sessionId, checkpointId)

    companion object {
        const val DEFAULT_ADMIN_LIMIT = 1_000
    }
}

data class SessionCheckpointSummary(
    val sessionIdHash: String,
    val checkpointCount: Int,
    val latest: CheckpointSummary?,
    val checkpoints: List<CheckpointSummary>,
)

data class CheckpointSummary(
    val checkpointIdHash: String,
    val version: Long,
    val createdAt: Instant,
    val storedAt: Instant,
    val nodePath: String,
    val isTombstone: Boolean,
    val payloadBytes: Int,
    val uncompressedPayloadBytes: Int,
    val compression: String,
)

data class CheckpointVerificationReport(
    val sessionIdHash: String,
    val checkedCount: Int,
    val validCount: Int,
    val problems: List<CheckpointVerificationProblem>,
) {
    val isHealthy: Boolean
        get() = problems.isEmpty()
}

data class CheckpointVerificationProblem(
    val checkpointIdHash: String,
    val problem: String,
)
