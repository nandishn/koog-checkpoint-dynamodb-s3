package io.github.nandishn.koog.checkpoint.aws

enum class CorruptCheckpointPolicy {
    FailFast,
    SkipAndContinue,
}

enum class CheckpointOrder {
    Ascending,
    Descending,
}

sealed interface Compression {
    val storageName: String

    data object None : Compression {
        override val storageName: String = "none"
    }

    data object Gzip : Compression {
        override val storageName: String = "gzip"
    }
}

sealed interface S3Encryption {
    data object None : S3Encryption
    data object SseS3 : S3Encryption
    data class SseKms(val keyId: String) : S3Encryption
}
