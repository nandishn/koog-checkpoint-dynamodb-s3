package io.github.nandishn.koog.checkpoint.aws

internal data class CheckpointMetadataPage(
    val items: List<CheckpointMetadata>,
    val nextCursor: Map<String, String>?,
)

internal data class CheckpointQuery(
    val order: CheckpointOrder,
    val limit: Int,
    val cursor: Map<String, String>? = null,
    val consistentRead: Boolean = true,
)

internal interface CheckpointMetadataStore {
    suspend fun putCheckpointAndLookup(metadata: CheckpointMetadata)
    suspend fun getByCheckpointId(sessionPk: String, checkpointIdHash: String, consistentRead: Boolean): CheckpointMetadata?
    suspend fun queryCheckpoints(sessionPk: String, query: CheckpointQuery): CheckpointMetadataPage
    suspend fun deleteCheckpointMetadata(metadata: CheckpointMetadata)
}

internal data class PayloadRef(
    val agentIdHash: String,
    val checkpointIdHash: String,
    val bucket: String,
    val key: String,
)

internal data class PayloadMetadata(
    val sha256: String,
    val schemaVersion: Int,
    val compression: String,
    val codec: String,
    val expiresAtEpochSeconds: Long?,
    val ttlDays: Long?,
    val tags: Map<String, String>,
)

internal interface CheckpointPayloadStore {
    suspend fun put(ref: PayloadRef, bytes: ByteArray, metadata: PayloadMetadata)
    suspend fun get(ref: PayloadRef): ByteArray
    suspend fun deleteBestEffort(ref: PayloadRef)
}
