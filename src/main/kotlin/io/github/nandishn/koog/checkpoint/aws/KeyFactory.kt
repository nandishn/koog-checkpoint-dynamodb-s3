package io.github.nandishn.koog.checkpoint.aws

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import kotlin.time.Instant

internal data class CheckpointKeys(
    val agentIdHash: String,
    val checkpointIdHash: String,
    val sessionPk: String,
    val checkpointSk: String,
    val checkpointLookupSk: String,
    val s3Key: String,
)

internal class KeyFactory(
    private val config: DynamoDbS3PersistenceConfig,
) {
    fun keysFor(agentId: String, checkpoint: AgentCheckpointData, payloadSha256: String, storedAt: Instant): CheckpointKeys {
        val agentIdHash = agentIdHash(agentId)
        val checkpointIdHash = checkpointIdHash(checkpoint.checkpointId)
        val checkpointSk = checkpointSk(checkpoint.version, checkpoint.createdAt, storedAt, checkpointIdHash)
        return CheckpointKeys(
            agentIdHash = agentIdHash,
            checkpointIdHash = checkpointIdHash,
            sessionPk = sessionPkFromHash(agentIdHash),
            checkpointSk = checkpointSk,
            checkpointLookupSk = checkpointLookupSk(checkpointIdHash),
            s3Key = s3Key(agentIdHash, checkpointIdHash, payloadSha256, config.compression),
        )
    }

    fun agentIdHash(agentId: String): String =
        config.idHashing.hash("${config.applicationName}:${config.environment}:$agentId")

    fun sessionPk(agentId: String): String = sessionPkFromHash(agentIdHash(agentId))

    fun sessionPkFromHash(agentIdHash: String): String =
        "APP#${config.applicationName}#ENV#${config.environment}#SESSION#$agentIdHash"

    fun checkpointLookupSk(checkpointIdHash: String): String = "CPID#$checkpointIdHash"

    private fun checkpointIdHash(checkpointId: String): String = config.idHashing.hash("checkpoint:$checkpointId")

    private fun checkpointSk(version: Long, createdAt: Instant, storedAt: Instant, checkpointIdHash: String): String =
        "CP#V#${version.pad20()}#T#${createdAt.toEpochMilliseconds().pad20()}#S#${storedAt.toEpochMilliseconds().pad20()}#ID#$checkpointIdHash"

    private fun s3Key(
        agentIdHash: String,
        checkpointIdHash: String,
        payloadSha256: String,
        compression: Compression,
    ): String {
        val suffix = when (compression) {
            Compression.None -> "json"
            Compression.Gzip -> "json.gz"
        }
        return "${config.keyPrefix}/v1/session=$agentIdHash/checkpoint=$checkpointIdHash/sha256=$payloadSha256.$suffix"
    }

    private fun Long.pad20(): String {
        require(this >= 0) { "checkpoint ordering fields must be non-negative" }
        return toString().padStart(20, '0')
    }

    companion object {
        const val PK = "pk"
        const val SK = "sk"
        const val CHECKPOINT_SK_PREFIX = "CP#"
        const val CHECKPOINT_LOOKUP_SK_PREFIX = "CPID#"
    }
}
