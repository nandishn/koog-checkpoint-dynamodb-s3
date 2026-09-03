package io.github.nandishn.koog.checkpoint.aws

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import kotlin.time.TimeSource

internal class CheckpointRepository(
    private val metadataStore: CheckpointMetadataStore,
    private val payloadStore: CheckpointPayloadStore,
    private val codec: CheckpointCodec,
    private val keyFactory: KeyFactory,
    private val config: DynamoDbS3PersistenceConfig,
) {
    suspend fun save(
        agentId: String,
        checkpoint: AgentCheckpointData,
    ) {
        val startedAt = config.clock.now()
        val encoded = codec.encode(checkpoint)
        val storedAt = config.clock.now()
        val keys = keyFactory.keysFor(agentId, checkpoint, encoded.sha256, storedAt)
        val metadata = CheckpointMetadataFactory.create(keys, checkpoint, encoded, storedAt, config)
        val payloadRef = metadata.toPayloadRef()

        try {
            payloadStore.put(
                ref = payloadRef,
                bytes = encoded.bytes,
                metadata = PayloadMetadata(
                    sha256 = encoded.sha256,
                    schemaVersion = metadata.schemaVersion,
                    compression = metadata.compression,
                    codec = metadata.codec,
                    tags = payloadTags(metadata),
                ),
            )

            metadataStore.putCheckpointAndLookup(metadata)

            config.metrics.checkpointSaved(
                bytes = encoded.uncompressedBytes.toLong(),
                compressedBytes = encoded.compressedBytes.toLong(),
                latencyMs = config.clock.now().toEpochMilliseconds() - startedAt.toEpochMilliseconds(),
            )
        } catch (e: ConditionalCheckpointConflictException) {
            val existing = metadataStore.getByCheckpointId(
                sessionPk = keys.sessionPk,
                checkpointIdHash = keys.checkpointIdHash,
                consistentRead = config.consistentReads,
            )
            if (existing != null && existing.payloadSha256 == encoded.sha256) {
                config.metrics.checkpointConflict(idempotent = true)
                return
            }
            config.metrics.checkpointConflict(idempotent = false)
            payloadStore.deleteBestEffort(payloadRef)
            throw CheckpointAlreadyExistsException(keys.agentIdHash, keys.checkpointIdHash, e)
        } catch (e: Throwable) {
            payloadStore.deleteBestEffort(payloadRef)
            throw e
        }
    }

    suspend fun getLatest(agentId: String, filter: DynamoDbS3CheckpointFilter): AgentCheckpointData? {
        val agentIdHash = keyFactory.agentIdHash(agentId)
        val sessionPk = keyFactory.sessionPkFromHash(agentIdHash)
        filter.checkpointId?.let { checkpointId ->
            val checkpointIdHash = config.idHashing.hash("checkpoint:$checkpointId")
            val metadata = metadataStore.getByCheckpointId(sessionPk, checkpointIdHash, config.consistentReads)
                ?: return null
            return if (metadata.matches(filter.copy(checkpointId = null, order = CheckpointOrder.Descending))) {
                load(metadata)
            } else {
                null
            }
        }

        var cursor: Map<String, String>? = null
        var scannedCount = 0
        val metadataFilter = filter.copy(order = CheckpointOrder.Descending)

        while (true) {
            val scanBudget = config.maxCheckpointsPerList - scannedCount
            if (scanBudget <= 0) {
                throw TooManyCheckpointsException(agentIdHash, config.maxCheckpointsPerList)
            }
            val page = metadataStore.queryCheckpoints(
                sessionPk = sessionPk,
                query = CheckpointQuery(
                    order = CheckpointOrder.Descending,
                    limit = minOf(config.maxListPageSize, scanBudget),
                    cursor = cursor,
                    consistentRead = config.consistentReads,
                ),
            )

            for (metadata in page.items) {
                scannedCount++
                if (!metadata.matches(metadataFilter)) continue
                val checkpoint = load(metadata)
                if (checkpoint != null) return checkpoint
            }

            cursor = page.nextCursor ?: return null
        }
    }

    suspend fun list(agentId: String, filter: DynamoDbS3CheckpointFilter): List<AgentCheckpointData> {
        val agentIdHash = keyFactory.agentIdHash(agentId)
        val sessionPk = keyFactory.sessionPkFromHash(agentIdHash)
        val limit = filter.limit ?: config.maxCheckpointsPerList
        if (limit > config.maxCheckpointsPerList) {
            throw TooManyCheckpointsException(agentIdHash, config.maxCheckpointsPerList)
        }

        filter.checkpointId?.let { checkpointId ->
            val checkpointIdHash = config.idHashing.hash("checkpoint:$checkpointId")
            val metadata = metadataStore.getByCheckpointId(sessionPk, checkpointIdHash, config.consistentReads)
                ?: return emptyList()
            return if (metadata.matches(filter.copy(checkpointId = null))) {
                listOfNotNull(load(metadata))
            } else {
                emptyList()
            }
        }

        val checkpoints = mutableListOf<AgentCheckpointData>()
        var cursor: Map<String, String>? = null
        var scannedCount = 0
        while (checkpoints.size < limit) {
            val scanBudget = config.maxCheckpointsPerList - scannedCount
            if (scanBudget <= 0) {
                throw TooManyCheckpointsException(agentIdHash, config.maxCheckpointsPerList)
            }
            val page = metadataStore.queryCheckpoints(
                sessionPk = sessionPk,
                query = CheckpointQuery(
                    order = filter.order,
                    limit = minOf(config.maxListPageSize, scanBudget),
                    cursor = cursor,
                    consistentRead = config.consistentReads,
                ),
            )

            for (metadata in page.items) {
                scannedCount++
                if (!metadata.matches(filter)) continue
                load(metadata)?.let { checkpoints += it }
                if (checkpoints.size >= limit) break
            }

            cursor = page.nextCursor ?: break
        }
        return checkpoints
    }

    suspend fun inspect(sessionId: String, limit: Int): SessionCheckpointSummary {
        val metadata = listMetadata(sessionId, limit)
        val latest = metadata.firstOrNull()?.toSummary()
        return SessionCheckpointSummary(
            sessionIdHash = keyFactory.agentIdHash(sessionId),
            checkpointCount = metadata.size,
            latest = latest,
            checkpoints = metadata.map { it.toSummary() },
        )
    }

    suspend fun verify(sessionId: String, limit: Int): CheckpointVerificationReport {
        val metadata = listMetadata(sessionId, limit)
        val problems = mutableListOf<CheckpointVerificationProblem>()
        for (item in metadata) {
            val ref = item.toPayloadRef()
            val result = runCatching {
                val bytes = payloadStore.get(ref)
                val actual = sha256Hex(bytes)
                if (actual != item.payloadSha256) {
                    error("checksum mismatch expected=${item.payloadSha256} actual=$actual")
                }
                codec.decode(bytes, item)
            }
            result.exceptionOrNull()?.let { error ->
                problems += CheckpointVerificationProblem(
                    checkpointIdHash = item.checkpointIdHash,
                    problem = error.message ?: error::class.simpleName.orEmpty(),
                )
            }
        }
        return CheckpointVerificationReport(
            sessionIdHash = keyFactory.agentIdHash(sessionId),
            checkedCount = metadata.size,
            validCount = metadata.size - problems.size,
            problems = problems,
        )
    }

    suspend fun deleteCheckpoint(sessionId: String, checkpointId: String): Boolean {
        val sessionPk = keyFactory.sessionPk(sessionId)
        val checkpointIdHash = config.idHashing.hash("checkpoint:$checkpointId")
        val metadata = metadataStore.getByCheckpointId(sessionPk, checkpointIdHash, config.consistentReads)
            ?: return false
        metadataStore.deleteCheckpointMetadata(metadata)
        payloadStore.deleteBestEffort(metadata.toPayloadRef())
        return true
    }

    private suspend fun load(metadata: CheckpointMetadata): AgentCheckpointData? {
        val payloadRef = metadata.toPayloadRef()
        val loaded = runCatching {
            val mark = TimeSource.Monotonic.markNow()
            val bytes = payloadStore.get(payloadRef)
            val actualSha256 = sha256Hex(bytes)
            if (actualSha256 != metadata.payloadSha256) {
                throw CorruptCheckpointException(
                    agentIdHash = metadata.agentIdHash,
                    checkpointIdHash = metadata.checkpointIdHash,
                    expectedSha256 = metadata.payloadSha256,
                    actualSha256 = actualSha256,
                )
            }
            val checkpoint = codec.decode(bytes, metadata)
            config.metrics.checkpointLoaded(metadata.uncompressedPayloadBytes.toLong(), mark.elapsedNow().inWholeMilliseconds)
            checkpoint
        }

        return loaded.getOrElse { error ->
            when (config.corruptCheckpointPolicy) {
                CorruptCheckpointPolicy.FailFast -> throw error
                CorruptCheckpointPolicy.SkipAndContinue -> if (error.isRecoverableCheckpointLoadFailure()) {
                    config.metrics.checkpointSkipped(error::class.simpleName ?: "unknown")
                    null
                } else {
                    throw error
                }
            }
        }
    }

    private fun payloadTags(metadata: CheckpointMetadata): Map<String, String> =
        buildMap {
            put("koog", "true")
            put("koog-app", config.applicationName)
            put("koog-env", config.environment)
            put("koog-schema-version", metadata.schemaVersion.toString())
            config.ttl?.let {
                val days = it.inWholeDays.coerceAtLeast(1)
                put("koog-ttl-days", days.toString())
            }
        }

    private suspend fun listMetadata(sessionId: String, limit: Int): List<CheckpointMetadata> {
        require(limit > 0) { "limit must be positive" }
        if (limit > config.maxCheckpointsPerList) {
            throw TooManyCheckpointsException(keyFactory.agentIdHash(sessionId), config.maxCheckpointsPerList)
        }

        val sessionPk = keyFactory.sessionPk(sessionId)
        val metadata = mutableListOf<CheckpointMetadata>()
        var cursor: Map<String, String>? = null
        while (metadata.size < limit) {
            val page = metadataStore.queryCheckpoints(
                sessionPk = sessionPk,
                query = CheckpointQuery(
                    order = CheckpointOrder.Descending,
                    limit = minOf(config.maxListPageSize, limit - metadata.size),
                    cursor = cursor,
                    consistentRead = config.consistentReads,
                ),
            )
            metadata += page.items
            cursor = page.nextCursor ?: break
        }
        return metadata
    }

    private fun CheckpointMetadata.toSummary(): CheckpointSummary =
        CheckpointSummary(
            checkpointIdHash = checkpointIdHash,
            version = version,
            createdAt = createdAt,
            storedAt = storedAt,
            nodePath = nodePath,
            isTombstone = isTombstone,
            payloadBytes = payloadBytes,
            uncompressedPayloadBytes = uncompressedPayloadBytes,
            compression = compression,
        )

    private fun CheckpointMetadata.toPayloadRef(): PayloadRef =
        PayloadRef(
            agentIdHash = agentIdHash,
            checkpointIdHash = checkpointIdHash,
            bucket = s3Bucket,
            key = s3Key,
        )

    private fun Throwable.isRecoverableCheckpointLoadFailure(): Boolean =
        this is CorruptCheckpointException || this is MissingCheckpointPayloadException
}
