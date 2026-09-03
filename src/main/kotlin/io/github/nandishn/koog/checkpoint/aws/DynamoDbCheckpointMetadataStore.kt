package io.github.nandishn.koog.checkpoint.aws

import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.sdk.kotlin.services.dynamodb.getItem
import aws.sdk.kotlin.services.dynamodb.model.Delete
import aws.sdk.kotlin.services.dynamodb.model.Put
import aws.sdk.kotlin.services.dynamodb.model.TransactWriteItem
import aws.sdk.kotlin.services.dynamodb.model.TransactionCanceledException
import aws.sdk.kotlin.services.dynamodb.query
import aws.sdk.kotlin.services.dynamodb.transactWriteItems

internal class DynamoDbCheckpointMetadataStore(
    private val dynamoDb: DynamoDbClient,
    private val tableName: String,
    private val keyFactory: KeyFactory,
) : CheckpointMetadataStore {
    override suspend fun putCheckpointAndLookup(metadata: CheckpointMetadata) {
        try {
            dynamoDb.transactWriteItems {
                transactItems = listOf(
                    TransactWriteItem {
                        put = Put {
                            tableName = this@DynamoDbCheckpointMetadataStore.tableName
                            item = CheckpointMetadataDynamoMapper.checkpointItem(metadata)
                            conditionExpression = "attribute_not_exists(#pk) AND attribute_not_exists(#sk)"
                            expressionAttributeNames = keyAttributeNames
                        }
                    },
                    TransactWriteItem {
                        put = Put {
                            tableName = this@DynamoDbCheckpointMetadataStore.tableName
                            item = CheckpointMetadataDynamoMapper.lookupItem(metadata)
                            conditionExpression = "attribute_not_exists(#pk) AND attribute_not_exists(#sk)"
                            expressionAttributeNames = keyAttributeNames
                        }
                    },
                )
                clientRequestToken = dynamoDbClientRequestToken(
                    metadata.sessionPk,
                    metadata.checkpointSk,
                    metadata.checkpointLookupSk,
                    metadata.payloadSha256,
                )
            }
        } catch (e: TransactionCanceledException) {
            val checkpointFailed = e.cancellationReasons.orEmpty().take(2).any {
                it.code == "ConditionalCheckFailed"
            }
            when {
                checkpointFailed -> throw ConditionalCheckpointConflictException(
                    "DynamoDB checkpoint transaction found an existing checkpoint record",
                    e,
                )
                else -> throw DynamoDbS3CheckpointException("DynamoDB checkpoint transaction was cancelled", e)
            }
        }
    }

    override suspend fun getByCheckpointId(
        sessionPk: String,
        checkpointIdHash: String,
        consistentRead: Boolean,
    ): CheckpointMetadata? {
        val lookup = dynamoDb.getItem {
            tableName = this@DynamoDbCheckpointMetadataStore.tableName
            key = mapOf(
                KeyFactory.PK to av(sessionPk),
                KeyFactory.SK to av(keyFactory.checkpointLookupSk(checkpointIdHash)),
            )
            this.consistentRead = consistentRead
        }.item ?: return null

        val targetSk = lookup.requiredString("targetSk")
        val checkpoint = dynamoDb.getItem {
            tableName = this@DynamoDbCheckpointMetadataStore.tableName
            key = mapOf(
                KeyFactory.PK to av(sessionPk),
                KeyFactory.SK to av(targetSk),
            )
            this.consistentRead = consistentRead
        }.item ?: return null

        return CheckpointMetadataDynamoMapper.metadataFromItem(checkpoint)
    }

    override suspend fun queryCheckpoints(sessionPk: String, query: CheckpointQuery): CheckpointMetadataPage {
        val response = dynamoDb.query {
            tableName = this@DynamoDbCheckpointMetadataStore.tableName
            keyConditionExpression = "#pk = :pk AND begins_with(#sk, :skPrefix)"
            expressionAttributeNames = keyAttributeNames
            expressionAttributeValues = mapOf(
                ":pk" to av(sessionPk),
                ":skPrefix" to av(KeyFactory.CHECKPOINT_SK_PREFIX),
            )
            scanIndexForward = query.order == CheckpointOrder.Ascending
            limit = query.limit
            consistentRead = query.consistentRead
            exclusiveStartKey = query.cursor?.toDynamoKey()
        }

        return CheckpointMetadataPage(
            items = response.items.orEmpty()
                .filter { it.optionalString("entityType") == "checkpoint" }
                .map(CheckpointMetadataDynamoMapper::metadataFromItem),
            nextCursor = response.lastEvaluatedKey?.mapValues { it.value.stringValue(it.key) },
        )
    }

    override suspend fun deleteCheckpointMetadata(metadata: CheckpointMetadata) {
        dynamoDb.transactWriteItems {
            transactItems = listOf(
                TransactWriteItem {
                    delete = Delete {
                        tableName = this@DynamoDbCheckpointMetadataStore.tableName
                        key = mapOf(
                            KeyFactory.PK to av(metadata.sessionPk),
                            KeyFactory.SK to av(metadata.checkpointSk),
                        )
                    }
                },
                TransactWriteItem {
                    delete = Delete {
                        tableName = this@DynamoDbCheckpointMetadataStore.tableName
                        key = mapOf(
                            KeyFactory.PK to av(metadata.sessionPk),
                            KeyFactory.SK to av(metadata.checkpointLookupSk),
                        )
                    }
                },
            )
            clientRequestToken = dynamoDbClientRequestToken(
                "delete",
                metadata.sessionPk,
                metadata.checkpointSk,
                metadata.checkpointLookupSk,
            )
        }
    }

    private companion object {
        val keyAttributeNames = mapOf(
            "#pk" to KeyFactory.PK,
            "#sk" to KeyFactory.SK,
        )
    }
}
