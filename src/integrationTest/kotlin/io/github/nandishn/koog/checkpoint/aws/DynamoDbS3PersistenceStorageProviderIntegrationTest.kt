package io.github.nandishn.koog.checkpoint.aws

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import ai.koog.agents.snapshot.feature.GraphCheckpointProperties
import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition
import aws.sdk.kotlin.services.dynamodb.model.BillingMode
import aws.sdk.kotlin.services.dynamodb.model.CreateTableRequest
import aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement
import aws.sdk.kotlin.services.dynamodb.model.KeyType
import aws.sdk.kotlin.services.dynamodb.model.ScalarAttributeType
import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.model.CreateBucketRequest
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Instant

class DynamoDbS3PersistenceStorageProviderIntegrationTest {
    @Test
    fun `separate providers hand off checkpoints through dynamodb and s3`() = runBlocking {
        withAwsEndpoints { endpoints ->
            val tableName = uniqueName("koog-checkpoints")
            val bucketName = uniqueName("koog-checkpoint-payloads")
            val clients = awsClients(endpoints)
            clients.use {
                createResources(it.dynamoDb, it.s3, tableName, bucketName)

                val providerA = provider(it.dynamoDb, it.s3, tableName, bucketName)
                val providerB = provider(it.dynamoDb, it.s3, tableName, bucketName)

                providerA.saveCheckpoint("tenant-1:conversation-1", checkpoint("first", 1))

                val loaded = providerB.getLatestCheckpoint("tenant-1:conversation-1")

                assertNotNull(loaded)
                assertEquals("first", loaded.checkpointId)
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

        val image = DockerImageName.parse(System.getenv("LOCALSTACK_IMAGE") ?: "localstack/localstack:4.12")
        val stack = try {
            LocalStackContainer(image)
                .withServices(LocalStackContainer.Service.DYNAMODB, LocalStackContainer.Service.S3)
        } catch (e: IllegalStateException) {
            assumeTrue(false, "Docker is not available for LocalStack integration tests: ${e.message}")
            return
        }
        stack.start()
        try {
            block(
                AwsEndpoints(
                    region = stack.region,
                    dynamoDbEndpoint = stack.getEndpointOverride(LocalStackContainer.Service.DYNAMODB).toString(),
                    s3Endpoint = stack.getEndpointOverride(LocalStackContainer.Service.S3).toString(),
                    accessKey = stack.accessKey,
                    secretKey = stack.secretKey,
                ),
            )
        } finally {
            stack.stop()
        }
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

    private fun provider(
        dynamoDb: DynamoDbClient,
        s3: S3Client,
        tableName: String,
        bucketName: String,
    ): DynamoDbS3PersistenceStorageProvider =
        DynamoDbS3PersistenceStorageProvider.create(
            dynamoDbClient = dynamoDb,
            s3Client = s3,
        ) {
            region = "us-east-1"
            this.tableName = tableName
            this.bucketName = bucketName
            keyPrefix = "integration/checkpoints"
            applicationName = "integration-test"
            environment = "localstack"
            pathStyleAccess = true
        }

    private fun checkpoint(id: String, version: Long): AgentCheckpointData =
        AgentCheckpointData(
            checkpointId = id,
            createdAt = Instant.parse("2026-09-03T12:00:00Z"),
            messageHistory = emptyList(),
            llmParams = null,
            version = version,
            graphProperties = GraphCheckpointProperties(nodePath = "node-$version"),
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
}
