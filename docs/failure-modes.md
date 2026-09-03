# Failure Modes

| Scenario | Behavior |
| --- | --- |
| Pod A saves checkpoint, Pod B handles next request | Pod B loads latest metadata from DynamoDB and payload from S3. |
| Pod dies before checkpoint save | Next pod resumes from previous checkpoint. |
| S3 put succeeds and DynamoDB transaction fails | Payload delete is attempted; lifecycle rules eventually clean orphans. |
| DynamoDB metadata exists but S3 payload is missing | Policy either fails fast or skips to an older checkpoint. |
| Same checkpoint is retried after timeout | Same checkpoint ID and checksum is treated as success. |
| Same checkpoint ID has different payload | Save is rejected. |
| Two pods process the same session at the same time | Both checkpoints may exist; latest selection is deterministic, but business execution may fork. |
| External tool called twice | Application must use idempotency keys or an outbox. |

Default recovery posture favors availability:

```kotlin
corruptCheckpointPolicy = CorruptCheckpointPolicy.SkipAndContinue
```

For compliance-heavy workloads, prefer:

```kotlin
corruptCheckpointPolicy = CorruptCheckpointPolicy.FailFast
```
