package io.github.nandishn.koog.checkpoint.aws

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.feature.GraphCheckpointProperties
import kotlin.time.Clock
import kotlin.time.Instant

internal fun testConfig(
    block: DynamoDbS3PersistenceConfig.Builder.() -> Unit = {},
): DynamoDbS3PersistenceConfig =
    DynamoDbS3PersistenceConfig.Builder().apply {
        tableName = "koog-checkpoints"
        bucketName = "koog-checkpoint-payloads"
        keyPrefix = "test/checkpoints"
        applicationName = "test-app"
        environment = "test"
        block()
    }.build()

internal fun checkpoint(
    id: String,
    version: Long,
    createdAt: Instant = Instant.parse("2026-09-03T12:00:00Z"),
    nodePath: String = "node-$version",
): AgentCheckpointData =
    AgentCheckpointData(
        checkpointId = id,
        createdAt = createdAt,
        messageHistory = emptyList(),
        llmParams = null,
        version = version,
        graphProperties = GraphCheckpointProperties(nodePath = nodePath),
    )

internal class MutableClock(
    private var current: Instant,
) : Clock {
    override fun now(): Instant = current
}

internal class FakePayloadStore : CheckpointPayloadStore {
    private val objects = linkedMapOf<String, ByteArray>()
    val metadataByKey = linkedMapOf<String, PayloadMetadata>()
    val deletedKeys = mutableListOf<String>()
    var corruptReads: Boolean = false
    var failReads: Throwable? = null

    override suspend fun put(ref: PayloadRef, bytes: ByteArray, metadata: PayloadMetadata) {
        objects[ref.key] = bytes.copyOf()
        metadataByKey[ref.key] = metadata
    }

    override suspend fun get(ref: PayloadRef): ByteArray {
        failReads?.let { throw it }
        val bytes = objects[ref.key]
            ?: throw MissingCheckpointPayloadException(ref.agentIdHash, ref.checkpointIdHash)
        return if (corruptReads) bytes + 1.toByte() else bytes.copyOf()
    }

    override suspend fun deleteBestEffort(ref: PayloadRef) {
        deletedKeys += ref.key
        objects.remove(ref.key)
    }
}

internal class FakeMetadataStore : CheckpointMetadataStore {
    private val checkpoints = linkedMapOf<Pair<String, String>, CheckpointMetadata>()
    private val lookup = linkedMapOf<Pair<String, String>, CheckpointMetadata>()
    var failBeforePut: Exception? = null
    var failAfterPut: Exception? = null
    var failLookup: Exception? = null

    override suspend fun putCheckpointAndLookup(metadata: CheckpointMetadata) {
        failBeforePut?.let { throw it }
        val checkpointKey = metadata.sessionPk to metadata.checkpointSk
        val lookupKey = metadata.sessionPk to metadata.checkpointLookupSk
        if (checkpoints.containsKey(checkpointKey) || lookup.containsKey(lookupKey)) {
            throw ConditionalCheckpointConflictException("checkpoint already exists")
        }
        checkpoints[checkpointKey] = metadata
        lookup[lookupKey] = metadata
        failAfterPut?.let { throw it }
    }

    override suspend fun getByCheckpointId(
        sessionPk: String,
        checkpointIdHash: String,
        consistentRead: Boolean,
    ): CheckpointMetadata? {
        failLookup?.let { throw it }
        return lookup[sessionPk to "CPID#$checkpointIdHash"]
    }

    override suspend fun queryCheckpoints(sessionPk: String, query: CheckpointQuery): CheckpointMetadataPage {
        val allItems = checkpoints.values
            .filter { it.sessionPk == sessionPk }
            .sortedBy { it.checkpointSk }
            .let {
                if (query.order == CheckpointOrder.Descending) it.asReversed() else it
            }
        val start = query.cursor?.get(CURSOR)?.toIntOrNull() ?: 0
        val end = minOf(start + query.limit, allItems.size)
        val items = allItems.subList(start, end)
        return CheckpointMetadataPage(
            items = items,
            nextCursor = if (end < allItems.size) mapOf(CURSOR to end.toString()) else null,
        )
    }

    override suspend fun deleteCheckpointMetadata(metadata: CheckpointMetadata) {
        checkpoints.remove(metadata.sessionPk to metadata.checkpointSk)
        lookup.remove(metadata.sessionPk to metadata.checkpointLookupSk)
    }
}

private const val CURSOR = "cursor"

internal fun testRepository(
    config: DynamoDbS3PersistenceConfig,
    metadataStore: FakeMetadataStore = FakeMetadataStore(),
    payloadStore: FakePayloadStore = FakePayloadStore(),
): CheckpointRepository {
    val keyFactory = KeyFactory(config)
    return CheckpointRepository(
        metadataStore = metadataStore,
        payloadStore = payloadStore,
        codec = JsonCheckpointCodec(config.compression),
        keyFactory = keyFactory,
        config = config,
    )
}
