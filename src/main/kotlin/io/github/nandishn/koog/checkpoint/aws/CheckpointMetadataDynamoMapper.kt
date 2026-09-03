package io.github.nandishn.koog.checkpoint.aws

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import kotlin.time.Instant

internal object CheckpointMetadataDynamoMapper {
    fun checkpointItem(metadata: CheckpointMetadata): Map<String, AttributeValue> =
        buildMap {
            put(KeyFactory.PK, av(metadata.sessionPk))
            put(KeyFactory.SK, av(metadata.checkpointSk))
            put("entityType", av("checkpoint"))
            put("agentIdHash", av(metadata.agentIdHash))
            put("checkpointIdHash", av(metadata.checkpointIdHash))
            put("version", av(metadata.version))
            put("createdAt", av(metadata.createdAt))
            put("createdAtEpochMillis", av(metadata.createdAt.toEpochMilliseconds()))
            put("storedAt", av(metadata.storedAt))
            put("storedAtEpochMillis", av(metadata.storedAt.toEpochMilliseconds()))
            put("nodePath", av(metadata.nodePath))
            put("isTombstone", av(metadata.isTombstone))
            put("s3Bucket", av(metadata.s3Bucket))
            put("s3Key", av(metadata.s3Key))
            put("payloadSha256", av(metadata.payloadSha256))
            put("payloadBytes", av(metadata.payloadBytes))
            put("uncompressedPayloadBytes", av(metadata.uncompressedPayloadBytes))
            put("codec", av(metadata.codec))
            put("compression", av(metadata.compression))
            put("schemaVersion", av(metadata.schemaVersion))
            metadata.expiresAtEpochSeconds?.let { put("expiresAt", av(it)) }
        }

    fun lookupItem(metadata: CheckpointMetadata): Map<String, AttributeValue> =
        buildMap {
            put(KeyFactory.PK, av(metadata.sessionPk))
            put(KeyFactory.SK, av(metadata.checkpointLookupSk))
            put("entityType", av("checkpointLookup"))
            put("agentIdHash", av(metadata.agentIdHash))
            put("checkpointIdHash", av(metadata.checkpointIdHash))
            put("targetSk", av(metadata.checkpointSk))
            put("payloadSha256", av(metadata.payloadSha256))
            metadata.expiresAtEpochSeconds?.let { put("expiresAt", av(it)) }
        }

    fun metadataFromItem(item: Map<String, AttributeValue>): CheckpointMetadata =
        CheckpointMetadata(
            agentIdHash = item.requiredString("agentIdHash"),
            checkpointIdHash = item.requiredString("checkpointIdHash"),
            sessionPk = item.requiredString(KeyFactory.PK),
            checkpointSk = item.requiredString(KeyFactory.SK),
            checkpointLookupSk = KeyFactory.CHECKPOINT_LOOKUP_SK_PREFIX + item.requiredString("checkpointIdHash"),
            version = item.requiredLong("version"),
            createdAt = Instant.parse(item.requiredString("createdAt")),
            storedAt = Instant.parse(item.requiredString("storedAt")),
            nodePath = item.requiredString("nodePath"),
            isTombstone = item.requiredBoolean("isTombstone"),
            s3Bucket = item.requiredString("s3Bucket"),
            s3Key = item.requiredString("s3Key"),
            payloadSha256 = item.requiredString("payloadSha256"),
            payloadBytes = item.requiredInt("payloadBytes"),
            uncompressedPayloadBytes = item.requiredInt("uncompressedPayloadBytes"),
            codec = item.requiredString("codec"),
            compression = item.requiredString("compression"),
            schemaVersion = item.requiredInt("schemaVersion"),
            expiresAtEpochSeconds = item.optionalLong("expiresAt"),
        )
}
