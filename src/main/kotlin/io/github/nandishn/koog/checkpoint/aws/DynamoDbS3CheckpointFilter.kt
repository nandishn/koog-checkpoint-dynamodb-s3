package io.github.nandishn.koog.checkpoint.aws

import kotlin.time.Instant

data class DynamoDbS3CheckpointFilter(
    val checkpointId: String? = null,
    val minVersion: Long? = null,
    val maxVersion: Long? = null,
    val createdAfter: Instant? = null,
    val createdBefore: Instant? = null,
    val nodePath: String? = null,
    val includeTombstones: Boolean = false,
    val limit: Int? = null,
    val order: CheckpointOrder = CheckpointOrder.Ascending,
) {
    init {
        limit?.let { require(it > 0) { "limit must be positive when set" } }
    }
}
