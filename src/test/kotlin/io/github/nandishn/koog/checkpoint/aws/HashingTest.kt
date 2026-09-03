package io.github.nandishn.koog.checkpoint.aws

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HashingTest {
    @Test
    fun `dynamodb transaction token fits aws limit`() {
        val parts = arrayOf(
            "APP#test#ENV#prod#SESSION#${"a".repeat(64)}",
            "CP#V#00000000000000000001#T#00000001725369120000#S#00000001725369120001#ID#${"b".repeat(64)}",
            "CPID#${"b".repeat(64)}",
            "payload-${"c".repeat(64)}",
        )

        val first = dynamoDbClientRequestToken(*parts)
        val second = dynamoDbClientRequestToken(*parts)
        val changed = dynamoDbClientRequestToken(*parts.copyOf().also { it[3] = "different-payload" })
        val tableA = dynamoDbClientRequestToken("put", "table-a", *parts)
        val tableB = dynamoDbClientRequestToken("put", "table-b", *parts)

        assertTrue(first.length in 1..36)
        assertEquals(first, second)
        assertNotEquals(first, changed)
        assertNotEquals(tableA, tableB)
    }
}
