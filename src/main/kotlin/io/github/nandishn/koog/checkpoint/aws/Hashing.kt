package io.github.nandishn.koog.checkpoint.aws

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

sealed interface IdHashing {
    fun hash(value: String): String

    data object Sha256 : IdHashing {
        override fun hash(value: String): String = sha256Hex(value.toByteArray(Charsets.UTF_8))
    }

    class HmacSha256(
        private val secretProvider: () -> ByteArray,
    ) : IdHashing {
        override fun hash(value: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secretProvider(), "HmacSHA256"))
            return mac.doFinal(value.toByteArray(Charsets.UTF_8)).toHex()
        }
    }
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun dynamoDbClientRequestToken(vararg parts: String): String =
    sha256Hex(parts.joinToString(separator = "|").toByteArray(Charsets.UTF_8)).take(36)

internal fun ByteArray.toHex(): String =
    joinToString(separator = "") { "%02x".format(it) }
