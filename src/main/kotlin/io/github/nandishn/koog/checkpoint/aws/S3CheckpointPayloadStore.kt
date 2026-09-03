package io.github.nandishn.koog.checkpoint.aws

import aws.sdk.kotlin.services.s3.S3Client
import aws.sdk.kotlin.services.s3.deleteObject
import aws.sdk.kotlin.services.s3.model.DeleteObjectRequest
import aws.sdk.kotlin.services.s3.model.GetObjectRequest
import aws.sdk.kotlin.services.s3.model.NoSuchKey
import aws.sdk.kotlin.services.s3.model.NotFound
import aws.sdk.kotlin.services.s3.model.PutObjectRequest
import aws.sdk.kotlin.services.s3.model.ServerSideEncryption
import aws.sdk.kotlin.services.s3.putObject
import aws.smithy.kotlin.runtime.content.ByteStream
import aws.smithy.kotlin.runtime.content.toByteArray
import java.net.URLEncoder

internal class S3CheckpointPayloadStore(
    private val s3: S3Client,
    private val config: DynamoDbS3PersistenceConfig,
) : CheckpointPayloadStore {
    override suspend fun put(ref: PayloadRef, bytes: ByteArray, metadata: PayloadMetadata) {
        val request = PutObjectRequest {
            bucket = ref.bucket
            key = ref.key
            body = ByteStream.fromBytes(bytes)
            contentType = "application/json"
            contentEncoding = if (metadata.compression == Compression.Gzip.storageName) "gzip" else null
            this.metadata = buildMap {
                put("koog-schema-version", metadata.schemaVersion.toString())
                put("koog-agent-id-hash", ref.agentIdHash)
                put("koog-checkpoint-id-hash", ref.checkpointIdHash)
                put("koog-payload-sha256", metadata.sha256)
                put("koog-codec", metadata.codec)
                put("koog-compression", metadata.compression)
                metadata.ttlDays?.let { put("koog-ttl-days", it.toString()) }
                metadata.expiresAtEpochSeconds?.let { put("koog-expires-at", it.toString()) }
            }
            tagging = metadata.tags.toTaggingHeader()

            when (val encryption = config.s3Encryption) {
                S3Encryption.None -> Unit
                S3Encryption.SseS3 -> serverSideEncryption = ServerSideEncryption.fromValue("AES256")
                is S3Encryption.SseKms -> {
                    serverSideEncryption = ServerSideEncryption.fromValue("aws:kms")
                    ssekmsKeyId = encryption.keyId
                }
            }
        }
        s3.putObject(request)
    }

    override suspend fun get(ref: PayloadRef): ByteArray {
        val request = GetObjectRequest {
            bucket = ref.bucket
            key = ref.key
        }
        return try {
            s3.getObject(request) { response ->
                response.body?.toByteArray() ?: ByteArray(0)
            }
        } catch (e: NoSuchKey) {
            throw missingPayload(ref, e)
        } catch (e: NotFound) {
            throw missingPayload(ref, e)
        }
    }

    override suspend fun deleteBestEffort(ref: PayloadRef) {
        runCatching {
            s3.deleteObject(
                DeleteObjectRequest {
                    bucket = ref.bucket
                    key = ref.key
                },
            )
        }
    }

    private fun Map<String, String>.toTaggingHeader(): String =
        entries.joinToString("&") { (key, value) ->
            "${key.urlEncode()}=${value.urlEncode()}"
        }

    private fun String.urlEncode(): String =
        URLEncoder.encode(this, Charsets.UTF_8)

    private fun missingPayload(ref: PayloadRef, cause: Throwable): MissingCheckpointPayloadException =
        MissingCheckpointPayloadException(
            agentIdHash = ref.agentIdHash,
            checkpointIdHash = ref.checkpointIdHash,
            cause = cause,
        )
}
