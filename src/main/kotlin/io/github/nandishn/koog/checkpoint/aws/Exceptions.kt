package io.github.nandishn.koog.checkpoint.aws

open class KoogAwsPersistenceException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class CheckpointAlreadyExistsException(
    agentIdHash: String,
    checkpointIdHash: String,
    cause: Throwable? = null,
) : KoogAwsPersistenceException(
    "Checkpoint already exists for agentHash=$agentIdHash checkpointHash=$checkpointIdHash with different payload",
    cause,
)

internal class ConditionalCheckpointConflictException(
    message: String,
    cause: Throwable? = null,
) : KoogAwsPersistenceException(message, cause)

class CorruptCheckpointException(
    agentIdHash: String,
    checkpointIdHash: String,
    expectedSha256: String,
    actualSha256: String,
) : KoogAwsPersistenceException(
    "Checkpoint payload checksum mismatch for agentHash=$agentIdHash checkpointHash=$checkpointIdHash " +
        "expected=$expectedSha256 actual=$actualSha256",
)

class MissingCheckpointPayloadException(
    agentIdHash: String,
    checkpointIdHash: String,
    bucket: String,
    key: String,
    cause: Throwable? = null,
) : KoogAwsPersistenceException(
    "Checkpoint payload is missing for agentHash=$agentIdHash checkpointHash=$checkpointIdHash at s3://$bucket/$key",
    cause,
)

class TooManyCheckpointsException(
    agentIdHash: String,
    maxCheckpointsPerList: Int,
) : KoogAwsPersistenceException(
    "Checkpoint query for agentHash=$agentIdHash exceeded maxCheckpointsPerList=$maxCheckpointsPerList",
)
