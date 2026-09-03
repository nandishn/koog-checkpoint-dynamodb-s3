# Testing

Run unit tests:

```bash
./gradlew test
```

Run all checks:

```bash
./gradlew check
```

Current test coverage includes:

- Checkpoint codec round-trip.
- S3 key secrecy.
- Same-version sibling sort keys.
- Save/list/latest repository behavior.
- Idempotent duplicate checkpoint retry.
- Corrupt payload skip behavior.
- Admin inspect/verify/delete.
- DynamoDB transaction client request token length.
- Metadata privacy for raw checkpoint IDs.
- Ambiguous DynamoDB write failure handling.
- Default TTL, custom TTL, and TTL opt-out metadata behavior for DynamoDB and S3 payloads.
- LocalStack integration test compilation during `check`.

Run LocalStack integration tests:

```bash
KOOG_AWS_INTEGRATION_TESTS=true ./gradlew integrationTest
```

If Testcontainers cannot discover Docker Desktop, start LocalStack yourself and point tests at it:

```bash
docker run -d --rm --name koog-checkpoint-localstack -p 4566:4566 -e SERVICES=s3,dynamodb localstack/localstack:4.12
LOCALSTACK_ENDPOINT=http://localhost:4566 KOOG_AWS_INTEGRATION_TESTS=true ./gradlew integrationTest
docker stop koog-checkpoint-localstack
```

The integration suite covers:

- S3 + DynamoDB end-to-end checkpoint save/load.
- Two-provider handoff through shared DynamoDB and S3 resources.
- Multiple checkpoint ordering, limits, and filters.
- Duplicate checkpoint idempotency and conflict behavior.
- DynamoDB TTL alignment with S3 object metadata and tags.
- TTL opt-out behavior for DynamoDB and S3.
- Admin verify and delete behavior.
- Missing and corrupt S3 payload recovery policies.
- Fallback from a bad latest payload to a previous valid checkpoint.
- DynamoDB pagination and scan-cap behavior.
