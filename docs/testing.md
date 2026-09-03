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
- LocalStack integration test compilation during `check`.

Run LocalStack integration tests:

```bash
KOOG_AWS_INTEGRATION_TESTS=true ./gradlew integrationTest
```

If Testcontainers cannot discover Docker Desktop, start LocalStack yourself and point tests at it:

```bash
docker run --rm -p 4566:4566 -e SERVICES=s3,dynamodb localstack/localstack:4.12
LOCALSTACK_ENDPOINT=http://localhost:4566 KOOG_AWS_INTEGRATION_TESTS=true ./gradlew integrationTest
```

The integration suite covers:

- S3 + DynamoDB end-to-end checkpoint save/load.
- Two-provider handoff through shared DynamoDB and S3 resources.
