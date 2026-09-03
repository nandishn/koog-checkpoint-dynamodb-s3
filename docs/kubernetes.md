# Stateless And Multi-Pod Deployments

Checkpoint storage makes Koog state portable across pods. It does not, by itself, serialize execution for one conversation.

```kotlin
val provider = DynamoDbS3PersistenceStorageProvider.create {
    region = "us-west-2"
    tableName = "koog-checkpoints"
    bucketName = "my-agent-checkpoints"
    keyPrefix = "prod/support"
}

suspend fun handleMessage(sessionId: String, message: String): String {
    val agent = createKoogAgent(sessionId, provider)
    return agent.run(message)
}
```

Required application rule:

- Compute the same stable `sessionId` for the same logical conversation/workflow on every pod.

Recommended production behavior:

- Serialize turns for the same session in your HTTP layer, message queue, or workflow engine if concurrent execution would be unsafe.
- Use idempotency keys for external tool calls.
- Set readiness to false before pod shutdown.
- Keep node clocks synchronized.
- Keep the checkpoint `ttl` value aligned with S3 lifecycle expiration days, or disable both intentionally.

What is protected:

- Any pod can resume from checkpoints written by another pod.
- Duplicate checkpoint writes with the same checkpoint ID and checksum are idempotent.

What remains application-owned:

- Exactly-once external tool side effects.
- Concurrent message ordering.
- Merging two conversation turns that were started concurrently.
