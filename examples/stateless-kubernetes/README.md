# Stateless Kubernetes Koog Checkpointing

This example shows the intended storage shape for Kubernetes. Every pod builds the same provider configuration and computes the same stable session ID for a logical conversation.

```kotlin
val provider = DynamoDbS3PersistenceStorageProvider.create {
    region = "us-west-2"
    tableName = "koog-checkpoints"
    bucketName = "my-agent-checkpoints"
    keyPrefix = "prod/support"
    applicationName = "support-agent"
    environment = "prod"
}

suspend fun handleTurn(sessionId: String, message: String): String {
    val agent = createKoogAgent(sessionId, provider)
    return agent.run(message)
}
```

Production rules:

- Route by stable `sessionId`; do not use pod-local IDs.
- Serialize turns outside this library if a session must not run concurrently.
- Use idempotency keys for external tool calls.
- Close the root provider during application shutdown when using the convenience constructor.
- Checkpoints expire after 30 days by default; change `ttl` and S3 lifecycle expiration days together.
