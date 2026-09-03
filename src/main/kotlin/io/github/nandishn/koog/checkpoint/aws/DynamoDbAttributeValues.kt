package io.github.nandishn.koog.checkpoint.aws

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import kotlin.time.Instant

internal fun av(value: String): AttributeValue = AttributeValue.S(value)
internal fun av(value: Long): AttributeValue = AttributeValue.N(value.toString())
internal fun av(value: Int): AttributeValue = AttributeValue.N(value.toString())
internal fun av(value: Boolean): AttributeValue = AttributeValue.Bool(value)
internal fun av(value: Instant): AttributeValue = AttributeValue.S(value.toString())

internal fun AttributeValue.stringValue(name: String): String =
    (this as? AttributeValue.S)?.value ?: error("DynamoDB attribute '$name' is not a string")

internal fun AttributeValue.longValue(name: String): Long =
    (this as? AttributeValue.N)?.value?.toLong() ?: error("DynamoDB attribute '$name' is not a number")

internal fun AttributeValue.intValue(name: String): Int =
    longValue(name).toInt()

internal fun AttributeValue.booleanValue(name: String): Boolean =
    (this as? AttributeValue.Bool)?.value ?: error("DynamoDB attribute '$name' is not a boolean")

internal fun Map<String, AttributeValue>.requiredString(name: String): String =
    requireNotNull(this[name]) { "Missing DynamoDB attribute '$name'" }.stringValue(name)

internal fun Map<String, AttributeValue>.optionalString(name: String): String? =
    this[name]?.stringValue(name)

internal fun Map<String, AttributeValue>.requiredLong(name: String): Long =
    requireNotNull(this[name]) { "Missing DynamoDB attribute '$name'" }.longValue(name)

internal fun Map<String, AttributeValue>.optionalLong(name: String): Long? =
    this[name]?.longValue(name)

internal fun Map<String, AttributeValue>.requiredInt(name: String): Int =
    requireNotNull(this[name]) { "Missing DynamoDB attribute '$name'" }.intValue(name)

internal fun Map<String, AttributeValue>.requiredBoolean(name: String): Boolean =
    requireNotNull(this[name]) { "Missing DynamoDB attribute '$name'" }.booleanValue(name)

internal fun Map<String, String>.toDynamoKey(): Map<String, AttributeValue> =
    mapValues { av(it.value) }
