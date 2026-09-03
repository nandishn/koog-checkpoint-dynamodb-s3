package io.github.nandishn.koog.checkpoint.aws

interface CheckpointMetrics {
    fun checkpointSaved(bytes: Long, compressedBytes: Long, latencyMs: Long)
    fun checkpointLoaded(bytes: Long, latencyMs: Long)
    fun checkpointSkipped(reason: String)
    fun checkpointConflict(idempotent: Boolean)
}

object NoopCheckpointMetrics : CheckpointMetrics {
    override fun checkpointSaved(bytes: Long, compressedBytes: Long, latencyMs: Long) = Unit
    override fun checkpointLoaded(bytes: Long, latencyMs: Long) = Unit
    override fun checkpointSkipped(reason: String) = Unit
    override fun checkpointConflict(idempotent: Boolean) = Unit
}
