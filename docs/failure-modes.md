# Failure Modes

| Scenario | Behavior |
| --- | --- |
| Pod A saves checkpoint, Pod B handles next request | Pod B loads latest metadata from DynamoDB and payload from S3. |
| Pod dies before checkpoint save | Next pod resumes from previous checkpoint. |
| S3 put succeeds and DynamoDB transaction is known not to have committed | Payload delete is attempted; lifecycle rules eventually clean any missed orphans. |
| S3 put succeeds and DynamoDB transaction outcome is unknown | Payload is kept so a committed checkpoint is not broken. |
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

By default, metadata and payload examples use the same 30-day retention window. The library writes the same expiry to DynamoDB metadata and S3 payload metadata/tags. If you set `ttl = null`, the library writes no DynamoDB `expiresAt` attribute and no S3 TTL/expires-at metadata or tags.

S3 deletion is enforced by bucket lifecycle policy, not by per-object metadata. Keep the S3 lifecycle expiration days equal to the configured `ttl`. In Terraform, set `expiration_days = null` to omit S3 lifecycle expiration. In CloudFormation, set `EnableExpiration=false`.
