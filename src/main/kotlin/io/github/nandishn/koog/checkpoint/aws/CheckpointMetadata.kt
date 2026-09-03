package io.github.nandishn.koog.checkpoint.aws

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.feature.isTombstone
import kotlin.time.Instant

internal data class CheckpointMetadata(
    val agentIdHash: String,
    val checkpointIdHash: String,
    val sessionPk: String,
    val checkpointSk: String,
    val checkpointLookupSk: String,
    val version: Long,
    val createdAt: Instant,
    val storedAt: Instant,
    val nodePath: String,
    val isTombstone: Boolean,
    val s3Bucket: String,
    val s3Key: String,
    val payloadSha256: String,
    val payloadBytes: Int,
    val uncompressedPayloadBytes: Int,
    val codec: String,
    val compression: String,
    val schemaVersion: Int,
    val expiresAtEpochSeconds: Long?,
) {
    fun matches(filter: DynamoDbS3CheckpointFilter): Boolean {
        if (!filter.includeTombstones && isTombstone) return false
        filter.minVersion?.let { if (version < it) return false }
        filter.maxVersion?.let { if (version > it) return false }
        filter.createdAfter?.let { if (createdAt < it) return false }
        filter.createdBefore?.let { if (createdAt > it) return false }
        filter.nodePath?.let { if (nodePath != it) return false }
        return true
    }
}

internal object CheckpointMetadataFactory {
    const val SCHEMA_VERSION = 1

    fun create(
        keys: CheckpointKeys,
        checkpoint: AgentCheckpointData,
        encoded: EncodedCheckpoint,
        storedAt: Instant,
        config: DynamoDbS3PersistenceConfig,
    ): CheckpointMetadata {
        val expiresAt = config.ttl?.let { storedAt.plus(it).epochSeconds }
        return CheckpointMetadata(
            agentIdHash = keys.agentIdHash,
            checkpointIdHash = keys.checkpointIdHash,
            sessionPk = keys.sessionPk,
            checkpointSk = keys.checkpointSk,
            checkpointLookupSk = keys.checkpointLookupSk,
            version = checkpoint.version,
            createdAt = checkpoint.createdAt,
            storedAt = storedAt,
            nodePath = checkpoint.graphProperties?.nodePath
                ?: checkpoint.plannerProperties?.executionPoint?.toString()
                ?: "",
            isTombstone = checkpoint.isTombstone(),
            s3Bucket = config.bucketName,
            s3Key = keys.s3Key,
            payloadSha256 = encoded.sha256,
            payloadBytes = encoded.compressedBytes,
            uncompressedPayloadBytes = encoded.uncompressedBytes,
            codec = encoded.codec,
            compression = encoded.compression.storageName,
            schemaVersion = SCHEMA_VERSION,
            expiresAtEpochSeconds = expiresAt,
        )
    }
}
