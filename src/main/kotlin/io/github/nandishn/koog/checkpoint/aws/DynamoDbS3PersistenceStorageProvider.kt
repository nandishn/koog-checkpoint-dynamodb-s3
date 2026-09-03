package io.github.nandishn.koog.checkpoint.aws

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.providers.PersistenceStorageProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.s3.S3Client
import aws.smithy.kotlin.runtime.net.url.Url
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

class DynamoDbS3PersistenceStorageProvider internal constructor(
    private val repository: CheckpointRepository,
    private val clientLifecycle: ClientLifecycle = ClientLifecycle.None,
) : PersistenceStorageProvider<DynamoDbS3CheckpointFilter>, AutoCloseable {
    override suspend fun saveCheckpoint(sessionId: String, agentCheckpointData: AgentCheckpointData) {
        repository.save(sessionId, agentCheckpointData)
    }

    override suspend fun getLatestCheckpoint(
        sessionId: String,
        filter: DynamoDbS3CheckpointFilter?,
    ): AgentCheckpointData? =
        repository.getLatest(sessionId, filter ?: DynamoDbS3CheckpointFilter(order = CheckpointOrder.Descending))

    override suspend fun getCheckpoints(
        sessionId: String,
        filter: DynamoDbS3CheckpointFilter?,
    ): List<AgentCheckpointData> =
        repository.list(sessionId, filter ?: DynamoDbS3CheckpointFilter())

    fun adminClient(): DynamoDbS3CheckpointAdminClient =
        DynamoDbS3CheckpointAdminClient(repository)

    override fun close() {
        clientLifecycle.close()
    }

    companion object {
        fun create(
            block: DynamoDbS3PersistenceConfig.Builder.() -> Unit,
        ): DynamoDbS3PersistenceStorageProvider {
            val config = DynamoDbS3PersistenceConfig.Builder().apply(block).build()
            val dynamoDbClient = newDynamoDbClient(config)
            val s3Client = newS3Client(config)
            return create(
                dynamoDbClient = dynamoDbClient,
                s3Client = s3Client,
                config = config,
                clientLifecycle = ClientLifecycle.Owned(listOf(s3Client, dynamoDbClient)),
            )
        }

        fun create(
            dynamoDbClient: DynamoDbClient,
            s3Client: S3Client,
            block: DynamoDbS3PersistenceConfig.Builder.() -> Unit,
        ): DynamoDbS3PersistenceStorageProvider {
            val config = DynamoDbS3PersistenceConfig.Builder().apply(block).build()
            return create(dynamoDbClient, s3Client, config)
        }

        internal fun create(
            dynamoDbClient: DynamoDbClient,
            s3Client: S3Client,
            config: DynamoDbS3PersistenceConfig,
            clientLifecycle: ClientLifecycle = ClientLifecycle.None,
        ): DynamoDbS3PersistenceStorageProvider {
            val keyFactory = KeyFactory(config)
            val metadataStore = DynamoDbCheckpointMetadataStore(dynamoDbClient, config.tableName, keyFactory)
            val payloadStore = S3CheckpointPayloadStore(s3Client, config)
            val codec = JsonCheckpointCodec(config.compression)
            val repository = CheckpointRepository(metadataStore, payloadStore, codec, keyFactory, config)
            return DynamoDbS3PersistenceStorageProvider(repository, clientLifecycle = clientLifecycle)
        }

        private fun newDynamoDbClient(config: DynamoDbS3PersistenceConfig): DynamoDbClient =
            DynamoDbClient {
                config.region?.let { region = it }
                config.dynamoDbEndpointUrl?.let { endpointUrl = Url.parse(it) }
            }

        private fun newS3Client(config: DynamoDbS3PersistenceConfig): S3Client =
            S3Client {
                config.region?.let { region = it }
                config.s3EndpointUrl?.let { endpointUrl = Url.parse(it) }
                forcePathStyle = config.pathStyleAccess
            }
    }
}

internal sealed interface ClientLifecycle : Closeable {
    data object None : ClientLifecycle {
        override fun close() = Unit
    }

    class Owned(
        private val clients: List<Closeable>,
    ) : ClientLifecycle {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) {
                clients.forEach(Closeable::close)
            }
        }
    }
}
