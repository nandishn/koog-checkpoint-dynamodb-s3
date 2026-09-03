package io.github.nandishn.koog.checkpoint.aws

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

data class DynamoDbS3PersistenceConfig(
    val tableName: String,
    val bucketName: String,
    val keyPrefix: String = "koog/checkpoints",
    val applicationName: String = "default",
    val environment: String = "default",
    val region: String? = null,
    val dynamoDbEndpointUrl: String? = null,
    val s3EndpointUrl: String? = null,
    val pathStyleAccess: Boolean = false,
    val ttl: Duration? = DEFAULT_TTL,
    val compression: Compression = Compression.Gzip,
    val s3Encryption: S3Encryption = S3Encryption.SseS3,
    val idHashing: IdHashing = IdHashing.Sha256,
    val consistentReads: Boolean = true,
    val corruptCheckpointPolicy: CorruptCheckpointPolicy = CorruptCheckpointPolicy.SkipAndContinue,
    val maxListPageSize: Int = 25,
    val maxCheckpointsPerList: Int = 1_000,
    val clock: Clock = Clock.System,
    val metrics: CheckpointMetrics = NoopCheckpointMetrics,
) {
    companion object {
        val DEFAULT_TTL: Duration = 30.days
    }

    internal val ttlDays: Long?
        get() = ttl?.inWholeDays

    init {
        require(tableName.isNotBlank()) { "tableName must not be blank" }
        require(bucketName.isNotBlank()) { "bucketName must not be blank" }
        require(keyPrefix.isNotBlank()) { "keyPrefix must not be blank" }
        require(applicationName.isNotBlank()) { "applicationName must not be blank" }
        require(environment.isNotBlank()) { "environment must not be blank" }
        require(maxListPageSize in 1..1_000) { "maxListPageSize must be between 1 and 1000" }
        require(maxCheckpointsPerList >= 1) { "maxCheckpointsPerList must be at least 1" }
        ttl?.let {
            require(!it.isInfinite()) { "ttl must be finite when set" }
            require(it.isPositive()) { "ttl must be positive when set" }
            require(it.inWholeDays >= 1 && it == it.inWholeDays.days) {
                "ttl must be a whole number of days so DynamoDB TTL and S3 lifecycle expiration stay aligned"
            }
        }
    }

    class Builder {
        var tableName: String = ""
        var bucketName: String = ""
        var keyPrefix: String = "koog/checkpoints"
        var applicationName: String = "default"
        var environment: String = "default"
        var region: String? = null
        var dynamoDbEndpointUrl: String? = null
        var s3EndpointUrl: String? = null
        var pathStyleAccess: Boolean = false
        var ttl: Duration? = DEFAULT_TTL
        var compression: Compression = Compression.Gzip
        var s3Encryption: S3Encryption = S3Encryption.SseS3
        var idHashing: IdHashing = IdHashing.Sha256
        var consistentReads: Boolean = true
        var corruptCheckpointPolicy: CorruptCheckpointPolicy = CorruptCheckpointPolicy.SkipAndContinue
        var maxListPageSize: Int = 25
        var maxCheckpointsPerList: Int = 1_000
        var clock: Clock = Clock.System
        var metrics: CheckpointMetrics = NoopCheckpointMetrics

        fun build(): DynamoDbS3PersistenceConfig =
            DynamoDbS3PersistenceConfig(
                tableName = tableName,
                bucketName = bucketName,
                keyPrefix = keyPrefix.trim('/'),
                applicationName = applicationName,
                environment = environment,
                region = region,
                dynamoDbEndpointUrl = dynamoDbEndpointUrl,
                s3EndpointUrl = s3EndpointUrl,
                pathStyleAccess = pathStyleAccess,
                ttl = ttl,
                compression = compression,
                s3Encryption = s3Encryption,
                idHashing = idHashing,
                consistentReads = consistentReads,
                corruptCheckpointPolicy = corruptCheckpointPolicy,
                maxListPageSize = maxListPageSize,
                maxCheckpointsPerList = maxCheckpointsPerList,
                clock = clock,
                metrics = metrics,
            )
    }
}
