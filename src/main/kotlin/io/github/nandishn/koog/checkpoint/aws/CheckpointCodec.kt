package io.github.nandishn.koog.checkpoint.aws

import ai.koog.agents.snapshot.feature.AgentCheckpointData
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal interface CheckpointCodec {
    fun encode(checkpoint: AgentCheckpointData): EncodedCheckpoint
    fun decode(encoded: ByteArray, metadata: CheckpointMetadata): AgentCheckpointData
}

internal data class EncodedCheckpoint(
    val bytes: ByteArray,
    val uncompressedBytes: Int,
    val compressedBytes: Int,
    val sha256: String,
    val codec: String,
    val compression: Compression,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is EncodedCheckpoint &&
            bytes.contentEquals(other.bytes) &&
            uncompressedBytes == other.uncompressedBytes &&
            compressedBytes == other.compressedBytes &&
            sha256 == other.sha256 &&
            codec == other.codec &&
            compression == other.compression

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + uncompressedBytes
        result = 31 * result + compressedBytes
        result = 31 * result + sha256.hashCode()
        result = 31 * result + codec.hashCode()
        result = 31 * result + compression.hashCode()
        return result
    }
}

internal class JsonCheckpointCodec(
    private val compression: Compression = Compression.Gzip,
    private val json: Json = defaultJson,
) : CheckpointCodec {
    override fun encode(checkpoint: AgentCheckpointData): EncodedCheckpoint {
        val uncompressed = json.encodeToString(checkpoint).toByteArray(Charsets.UTF_8)
        val compressed = when (compression) {
            Compression.None -> uncompressed
            Compression.Gzip -> gzip(uncompressed)
        }
        return EncodedCheckpoint(
            bytes = compressed,
            uncompressedBytes = uncompressed.size,
            compressedBytes = compressed.size,
            sha256 = sha256Hex(compressed),
            codec = CODEC_NAME,
            compression = compression,
        )
    }

    override fun decode(encoded: ByteArray, metadata: CheckpointMetadata): AgentCheckpointData {
        val raw = when (metadata.compression) {
            Compression.None.storageName -> encoded
            Compression.Gzip.storageName -> gunzip(encoded)
            else -> throw DynamoDbS3CheckpointException(
                "Unsupported checkpoint compression '${metadata.compression}' for checkpointHash=${metadata.checkpointIdHash}",
            )
        }
        return json.decodeFromString(AgentCheckpointData.serializer(), raw.toString(Charsets.UTF_8))
    }

    companion object {
        const val CODEC_NAME = "kotlinx-json"

        val defaultJson: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

private fun gzip(bytes: ByteArray): ByteArray {
    val output = ByteArrayOutputStream()
    GZIPOutputStream(output).use { it.write(bytes) }
    return output.toByteArray()
}

private fun gunzip(bytes: ByteArray): ByteArray =
    GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
