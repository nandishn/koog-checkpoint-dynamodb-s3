package io.github.nandishn.koog.checkpoint.aws

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.feature.GraphCheckpointProperties
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.BillingMode
import aws.sdk.kotlin.services.dynamodb.model.CreateTableRequest
import aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement
import aws.sdk.kotlin.services.dynamodb.model.KeyType
import aws.sdk.kotlin.services.dynamodb.model.ScalarAttributeType
import aws.sdk.kotlin.services.dynamodb.query
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.getObjectTagging
import aws.sdk.kotlin.services.s3.headObject
import aws.sdk.kotlin.services.s3.model.CreateBucketRequest
import aws.sdk.kotlin.services.s3.model.ServerSideEncryption
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

class DynamoDbS3PersistenceStorageProviderIntegrationTest {
    @Test
    fun `separate providers hand off checkpoints through dynamodb and s3`() = runBlocking {
        withAwsResources {
            val providerA = provider()
            val providerB = provider()

            providerA.saveCheckpoint("tenant-1:conversation-1", checkpoint("first", 1))

            val loaded = providerB.getLatestCheckpoint("tenant-1:conversation-1")

            assertNotNull(loaded)
            assertEquals("first", loaded.checkpointId)
        }
    }

    @Test
    fun `multiple checkpoints preserve latest list ordering limits and filters`() = runBlocking {
        withAwsResources {
            val provider = provider()
            val sessionId = "tenant-1:ordering"

            provider.saveCheckpoint(sessionId, checkpoint("first", 1, nodePath = "start"))
            provider.saveCheckpoint(sessionId, checkpoint("second", 2, nodePath = "branch"))
            provider.saveCheckpoint(sessionId, checkpoint("third", 3, nodePath = "finish"))

            assertEquals("third", provider.getLatestCheckpoint(sessionId)?.checkpointId)
            assertEquals(
                listOf("first", "second", "third"),
                provider.getCheckpoints(sessionId).map { it.checkpointId },
            )
            assertEquals(
                listOf("third", "second"),
                provider.getCheckpoints(
                    sessionId,
                    DynamoDbS3CheckpointFilter(limit = 2, order = CheckpointOrder.Descending),
                ).map { it.checkpointId },
            )
            assertEquals(
                listOf("second"),
                provider.getCheckpoints(sessionId, DynamoDbS3CheckpointFilter(nodePath = "branch")).map { it.checkpointId },
            )
            assertEquals(
                listOf("second", "third"),
                provider.getCheckpoints(sessionId, DynamoDbS3CheckpointFilter(minVersion = 2)).map { it.checkpointId },
            )
        }
    }

    @Test
    fun `duplicate checkpoint saves are idempotent only for the same payload`() = runBlocking {
        withAwsResources {
            val provider = provider()
            val sessionId = "tenant-1:duplicates"
            val first = checkpoint("same-checkpoint-id", 1)

            provider.saveCheckpoint(sessionId, first)
            provider.saveCheckpoint(sessionId, first)

            val conflict = assertFailsWith<CheckpointAlreadyExistsException> {
                provider.saveCheckpoint(sessionId, checkpoint("same-checkpoint-id", 2))
            }

            assertTrue(conflict.message.orEmpty().contains("Checkpoint already exists"))
            assertEquals(listOf("same-checkpoint-id"), provider.getCheckpoints(sessionId).map { it.checkpointId })
        }
    }

    @Test
    fun `saved checkpoint writes aligned dynamodb ttl s3 metadata tags and encryption`() = runBlocking {
        withAwsResources {
            val storedAt = Instant.parse("2026-09-03T12:00:00Z")
            val provider = provider {
                clock = FixedClock(storedAt)
            }
            val sessionId = "tenant-1:ttl-default"

            provider.saveCheckpoint(sessionId, checkpoint("checkpoint-1", 1))

            val metadataItem = singleCheckpointItem(sessionId)
            val s3Key = metadataItem.stringAttr("s3Key")
            val expiresAt = storedAt.plus(30.days).epochSeconds.toString()
            val head = s3.headObject {
                bucket = bucketName
                key = s3Key
            }
            val tags = s3.getObjectTagging {
                bucket = bucketName
                key = s3Key
            }.tagsByKey()

            assertEquals(expiresAt, metadataItem.numberAttr("expiresAt"))
            assertEquals("application/json", head.contentType)
            assertEquals("gzip", head.contentEncoding)
            assertEquals(ServerSideEncryption.fromValue("AES256"), head.serverSideEncryption)
            assertEquals("30", head.metadata.orEmpty()["koog-ttl-days"])
            assertEquals(expiresAt, head.metadata.orEmpty()["koog-expires-at"])
            assertEquals("30", tags["koog-ttl-days"])
            assertEquals(expiresAt, tags["koog-expires-at"])
            assertEquals(metadataItem.stringAttr("payloadSha256"), head.metadata.orEmpty()["koog-payload-sha256"])
        }
    }

    @Test
    fun `disabled ttl omits expiration attributes metadata and tags`() = runBlocking {
        withAwsResources {
            val provider = provider {
                ttl = null
            }
            val sessionId = "tenant-1:ttl-disabled"

            provider.saveCheckpoint(sessionId, checkpoint("checkpoint-1", 1))

            val sessionItems = sessionItems(sessionId)
            val metadataItem = sessionItems.single { it.stringAttr("entityType") == "checkpoint" }
            val s3Key = metadataItem.stringAttr("s3Key")
            val head = s3.headObject {
                bucket = bucketName
                key = s3Key
            }
            val tags = s3.getObjectTagging {
                bucket = bucketName
                key = s3Key
            }.tagsByKey()

            assertTrue(sessionItems.none { it.containsKey("expiresAt") })
            assertFalse(head.metadata.orEmpty().containsKey("koog-ttl-days"))
            assertFalse(head.metadata.orEmpty().containsKey("koog-expires-at"))
            assertFalse(tags.containsKey("koog-ttl-days"))
            assertFalse(tags.containsKey("koog-expires-at"))
        }
    }

    @Test
    fun `admin client verifies and deletes stored checkpoints`() = runBlocking {
        withAwsResources {
            val provider = provider()
            val admin = provider.adminClient()
            val sessionId = "tenant-1:admin-delete"

            provider.saveCheckpoint(sessionId, checkpoint("checkpoint-1", 1))
            val s3Key = singleCheckpointItem(sessionId).stringAttr("s3Key")

            assertTrue(admin.verifySession(sessionId).isHealthy)
            assertTrue(admin.deleteCheckpoint(sessionId, "checkpoint-1"))
            assertFalse(admin.deleteCheckpoint(sessionId, "checkpoint-1"))
            assertEquals(0, admin.inspectSession(sessionId).checkpointCount)
            assertNull(provider.getLatestCheckpoint(sessionId))
            assertTrue(sessionItems(sessionId).isEmpty())
            assertTrue(
                runCatching {
                    s3.headObject {
                        bucket = bucketName
                        key = s3Key
                    }
                }.isFailure,
            )
        }
    }

    @Test
    fun `missing payload is reported by admin and respects load policy`() = runBlocking {
        withAwsResources {
            val sessionId = "tenant-1:missing-payload"
            val skipProvider = provider {
                corruptCheckpointPolicy = CorruptCheckpointPolicy.SkipAndContinue
            }
            val failProvider = provider {
                corruptCheckpointPolicy = CorruptCheckpointPolicy.FailFast
            }

            skipProvider.saveCheckpoint(sessionId, checkpoint("checkpoint-1", 1))
            val s3Key = singleCheckpointItem(sessionId).stringAttr("s3Key")
            s3.deleteObject {
                bucket = bucketName
                key = s3Key
            }

            val report = skipProvider.adminClient().verifySession(sessionId)

            assertFalse(report.isHealthy)
            assertEquals(1, report.checkedCount)
            assertEquals(0, report.validCount)
            assertTrue(report.problems.single().problem.contains("missing", ignoreCase = true))
            assertNull(skipProvider.getLatestCheckpoint(sessionId))
            assertFailsWith<MissingCheckpointPayloadException> {
                failProvider.getLatestCheckpoint(sessionId)
            }
        }
    }

    @Test
    fun `corrupt payload is reported by admin and respects load policy`() = runBlocking {
        withAwsResources {
            val sessionId = "tenant-1:corrupt-payload"
            val skipProvider = provider {
                corruptCheckpointPolicy = CorruptCheckpointPolicy.SkipAndContinue
            }
            val failProvider = provider {
                corruptCheckpointPolicy = CorruptCheckpointPolicy.FailFast
            }

            skipProvider.saveCheckpoint(sessionId, checkpoint("checkpoint-1", 1))
            val s3Key = singleCheckpointItem(sessionId).stringAttr("s3Key")
            s3.putObject {
                bucket = bucketName
                key = s3Key
                body = ByteStream.fromBytes("not the original checkpoint".encodeToByteArray())
            }

            val report = skipProvider.adminClient().verifySession(sessionId)

            assertFalse(report.isHealthy)
            assertEquals(1, report.checkedCount)
            assertEquals(0, report.validCount)
            assertTrue(report.problems.single().problem.contains("checksum mismatch"))
            assertNull(skipProvider.getLatestCheckpoint(sessionId))
            assertFailsWith<CorruptCheckpointException> {
                failProvider.getLatestCheckpoint(sessionId)
            }
        }
    }

    @Test
    fun `paginated dynamodb queries continue across pages and enforce scan cap`() = runBlocking {
        withAwsResources {
            val provider = provider {
                maxListPageSize = 2
                maxCheckpointsPerList = 5
            }
            val cappedProvider = provider {
                maxListPageSize = 2
                maxCheckpointsPerList = 3
            }
            val sessionId = "tenant-1:pagination"

            for (version in 1L..5L) {
                provider.saveCheckpoint(sessionId, checkpoint("checkpoint-$version", version))
            }

            assertEquals(
                listOf("checkpoint-1", "checkpoint-2", "checkpoint-3", "checkpoint-4", "checkpoint-5"),
                provider.getCheckpoints(sessionId, DynamoDbS3CheckpointFilter(limit = 5)).map { it.checkpointId },
            )
            assertEquals(
                "checkpoint-1",
                provider.getLatestCheckpoint(sessionId, DynamoDbS3CheckpointFilter(nodePath = "node-1"))?.checkpointId,
            )
            assertFailsWith<TooManyCheckpointsException> {
                cappedProvider.getLatestCheckpoint(sessionId, DynamoDbS3CheckpointFilter(nodePath = "missing"))
            }
        }
    }

    private suspend fun withAwsResources(block: suspend AwsTestContext.() -> Unit) {
        withAwsEndpoints { endpoints ->
            val tableName = uniqueName("koog-checkpoints")
            val bucketName = uniqueName("koog-checkpoint-payloads")
            val clients = awsClients(endpoints)
            clients.use {
                createResources(it.dynamoDb, it.s3, tableName, bucketName)
                AwsTestContext(endpoints, it.dynamoDb, it.s3, tableName, bucketName).block()
            }
        }
    }

    private suspend fun withAwsEndpoints(block: suspend (AwsEndpoints) -> Unit) {
        assumeTrue(System.getenv("KOOG_AWS_INTEGRATION_TESTS").toBoolean())

        val externalEndpoint = System.getenv("LOCALSTACK_ENDPOINT")
        if (!externalEndpoint.isNullOrBlank()) {
            block(
                AwsEndpoints(
                    region = System.getenv("LOCALSTACK_REGION") ?: "us-east-1",
                    dynamoDbEndpoint = System.getenv("LOCALSTACK_DYNAMODB_ENDPOINT") ?: externalEndpoint,
                    s3Endpoint = System.getenv("LOCALSTACK_S3_ENDPOINT") ?: externalEndpoint,
                    accessKey = System.getenv("LOCALSTACK_ACCESS_KEY") ?: "test",
                    secretKey = System.getenv("LOCALSTACK_SECRET_KEY") ?: "test",
                ),
            )
            return
        }

        val stack = localStack()
        block(
            AwsEndpoints(
                region = stack.region,
                dynamoDbEndpoint = stack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString(),
                s3Endpoint = stack.getEndpointOverride(LocalStackContainer.Service.S3).toString(),
                accessKey = stack.accessKey,
                secretKey = stack.secretKey,
            ),
        )
    }

    private fun awsClients(endpoints: AwsEndpoints): AwsClients =
        AwsClients(
            dynamoDb = DynamoDbClient {
                region = endpoints.region
                endpointUrl = Url.parse(endpoints.dynamoDbEndpoint)
                credentialsProvider = StaticCredentialsProvider {
                    accessKeyId = endpoints.accessKey
                    secretAccessKey = endpoints.secretKey
                }
            },
            s3 = S3Client {
                region = endpoints.region
                endpointUrl = Url.parse(endpoints.s3Endpoint)
                credentialsProvider = StaticCredentialsProvider {
                    accessKeyId = endpoints.accessKey
                    secretAccessKey = endpoints.secretKey
                }
                forcePathStyle = true
            },
        )

    private suspend fun createResources(
        dynamoDb: DynamoDbClient,
        s3: S3Client,
        tableName: String,
        bucketName: String,
    ) {
        dynamoDb.createTable(
            CreateTableRequest {
                this.tableName = tableName
                billingMode = BillingMode.PayPerRequest
                attributeDefinitions = listOf(
                    AttributeDefinition {
                        attributeName = "pk"
                        attributeType = ScalarAttributeType.S
                    },
                    AttributeDefinition {
                        attributeName = "sk"
                        attributeType = ScalarAttributeType.S
                    },
                )
                keySchema = listOf(
                    KeySchemaElement {
                        attributeName = "pk"
                        keyType = KeyType.Hash
                    },
                    KeySchemaElement {
                        attributeName = "sk"
                        keyType = KeyType.Range
                    },
                )
            },
        )

        s3.createBucket(
            CreateBucketRequest {
                bucket = bucketName
            },
        )
    }

    private data class AwsTestContext(
        val endpoints: AwsEndpoints,
        val dynamoDb: DynamoDbClient,
        val s3: S3Client,
        val tableName: String,
        val bucketName: String,
    ) {
        fun provider(
            block: DynamoDbS3PersistenceConfig.Builder.() -> Unit = {},
        ): DynamoDbS3PersistenceStorageProvider =
            DynamoDbS3PersistenceStorageProvider.create(
                dynamoDbClient = dynamoDb,
                s3Client = s3,
            ) {
                region = endpoints.region
                this.tableName = this@AwsTestContext.tableName
                this.bucketName = this@AwsTestContext.bucketName
                keyPrefix = "integration/checkpoints"
                applicationName = "integration-test"
                environment = "localstack"
                pathStyleAccess = true
                block()
            }

        suspend fun singleCheckpointItem(sessionId: String): Map<String, AttributeValue> =
            sessionItems(sessionId).single { it.stringAttr("entityType") == "checkpoint" }

        suspend fun sessionItems(sessionId: String): List<Map<String, AttributeValue>> {
            val summary = provider().adminClient().inspectSession(sessionId)
            val sessionPk = "APP#integration-test#ENV#localstack#SESSION#${summary.sessionIdHash}"
            return dynamoDb.query {
                tableName = this@AwsTestContext.tableName
                keyConditionExpression = "#pk = :pk"
                expressionAttributeNames = mapOf("#pk" to "pk")
                expressionAttributeValues = mapOf(":pk" to AttributeValue.S(sessionPk))
                consistentRead = true
            }.items.orEmpty()
        }

    }

    private fun checkpoint(
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

    private fun uniqueName(prefix: String): String =
        "$prefix-${UUID.randomUUID()}".lowercase()

    private data class AwsEndpoints(
        val region: String,
        val dynamoDbEndpoint: String,
        val s3Endpoint: String,
        val accessKey: String,
        val secretKey: String,
    )

    private data class AwsClients(
        val dynamoDb: DynamoDbClient,
        val s3: S3Client,
    ) : AutoCloseable {
        override fun close() {
            s3.close()
            dynamoDb.close()
        }
    }

    private class FixedClock(
        private val instant: Instant,
    ) : Clock {
        override fun now(): Instant = instant
    }

    companion object {
        private var sharedLocalStack: LocalStackContainer? = null

        @AfterAll
        @JvmStatic
        fun stopLocalStack() {
            sharedLocalStack?.stop()
            sharedLocalStack = null
        }

        @Synchronized
        private fun localStack(): LocalStackContainer {
            sharedLocalStack?.let { return it }

            val image = DockerImageName.parse(System.getenv("LOCALSTACK_IMAGE") ?: "localstack/localstack:4.12")
            return try {
                LocalStackContainer(image)
                    .withServices(LocalStackContainer.Service.DYNAMODB, LocalStackContainer.Service.S3)
                    .also { it.start() }
                    .also { sharedLocalStack = it }
            } catch (e: IllegalStateException) {
                throw AssertionError("Docker is not available for LocalStack integration tests", e)
            }
        }
    }
}

private fun Map<String, AttributeValue>.stringAttr(name: String): String =
    (getValue(name) as AttributeValue.S).value

private fun Map<String, AttributeValue>.numberAttr(name: String): String =
    (getValue(name) as AttributeValue.N).value

private fun aws.sdk.kotlin.services.s3.model.GetObjectTaggingResponse.tagsByKey(): Map<String, String> =
    tagSet.orEmpty().associate { tag ->
        requireNotNull(tag.key) to requireNotNull(tag.value)
    }
