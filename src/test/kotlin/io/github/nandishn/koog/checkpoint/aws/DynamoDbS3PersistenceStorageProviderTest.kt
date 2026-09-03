package io.github.nandishn.koog.checkpoint.aws

import java.io.Closeable
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamoDbS3PersistenceStorageProviderTest {
    @Test
    fun `closing owned provider closes clients once`() {
        val config = testConfig()
        val client = CountingCloseable()
        val provider = DynamoDbS3PersistenceStorageProvider(
            repository = testRepository(config),
            clientLifecycle = ClientLifecycle.Owned(listOf(client)),
        )

        provider.close()
        provider.close()
        assertEquals(1, client.closeCount)
    }

    private class CountingCloseable : Closeable {
        var closeCount: Int = 0
            private set

        override fun close() {
            closeCount += 1
        }
    }
}
