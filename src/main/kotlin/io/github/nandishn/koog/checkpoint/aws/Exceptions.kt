package io.github.nandishn.koog.checkpoint.aws

open class DynamoDbS3CheckpointException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class CheckpointAlreadyExistsException(
    agentIdHash: String,
    checkpointIdHash: String,
    cause: Throwable? = null,
) : DynamoDbS3CheckpointException(
    "Checkpoint already exists for agentHash=$agentIdHash checkpointHash=$checkpointIdHash with different payload",
    cause,
)

internal class ConditionalCheckpointConflictException(
    message: String,
    cause: Throwable? = null,
) : DynamoDbS3CheckpointException(message, cause)

class CorruptCheckpointException(
    agentIdHash: String,
    checkpointIdHash: String,
    expectedSha256: String,
    actualSha256: String,
) : DynamoDbS3CheckpointException(
    "Checkpoint payload checksum mismatch for agentHash=$agentIdHash checkpointHash=$checkpointIdHash " +
        "expected=$expectedSha256 actual=$actualSha256",
)

class MissingCheckpointPayloadException(
    agentIdHash: String,
    checkpointIdHash: String,
    cause: Throwable? = null,
) : DynamoDbS3CheckpointException(
    "Checkpoint payload is missing for agentHash=$agentIdHash checkpointHash=$checkpointIdHash",
    cause,
)

class TooManyCheckpointsException(
    agentIdHash: String,
    maxCheckpointsPerList: Int,
) : DynamoDbS3CheckpointException(
    "Checkpoint query for agentHash=$agentIdHash exceeded maxCheckpointsPerList=$maxCheckpointsPerList",
)
