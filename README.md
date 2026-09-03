# koog-checkpoint-dynamodb-s3

DynamoDB + S3 checkpoint persistence for [Koog](https://github.com/JetBrains/koog) agents.

Use this when a Koog agent runs in stateless JVM services, Kubernetes, ECS, Lambda, or any horizontally scaled backend where local checkpoint files are not durable.

This project is independent and is not affiliated with or endorsed by JetBrains, Koog, Amazon Web Services, or AWS.

## Install

Once released to Maven Central:

```kotlin
dependencies {
    implementation("io.github.nandishn:koog-checkpoint-dynamodb-s3:0.1.0")
}
```

Local development:

```bash
./gradlew publishToMavenLocal
```

## Usage

```kotlin
val provider = DynamoDbS3PersistenceStorageProvider.create {
    region = "us-west-2"
    tableName = "koog-checkpoints"
    bucketName = "my-agent-checkpoints"
    keyPrefix = "prod/support"
}

suspend fun handleMessage(agentId: String, userMessage: String): String {
    val agent = createKoogAgent(agentId) {
        install(Persistence) {
            storage = provider
            enableAutomaticPersistence = true
        }
    }

    return agent.run(userMessage)
}
```

## Checkpointing Is Not Chat Memory

This library stores Koog execution checkpoints so an interrupted agent can resume or roll back. For normal conversation history between independent requests, use Koog chat memory/history APIs.

## Architecture

- DynamoDB stores checkpoint metadata, ordering, lookup records, TTL, and S3 payload references.
- S3 stores immutable, content-addressed checkpoint payloads.
- Strongly consistent DynamoDB reads are enabled by default for immediate pod handoff.
- Checkpoints expire after 30 days by default. The same value is written to DynamoDB TTL metadata and S3 retention metadata/tags; set `ttl = null` to omit both.
- The library does not serialize concurrent requests for the same session; handle turn ordering in your application.

See the docs below for deployment guidance, IAM policy shape, testing, and failure modes.

## Admin Checks

```kotlin
val admin = provider.adminClient()

val summary = admin.inspectSession("tenant:123:conversation:456")
val verification = admin.verifySession("tenant:123:conversation:456")

check(verification.isHealthy)
```

Admin reports expose hashed session/checkpoint IDs only.

## Sensitive Session IDs

The default `Sha256` hashing keeps raw session and checkpoint IDs out of DynamoDB keys, S3 keys, and admin reports. If your session IDs are predictable or sensitive, use HMAC hashing with a secret loaded from your runtime secret manager:

```kotlin
val hashSecret = requireNotNull(System.getenv("KOOG_CHECKPOINT_HASH_SECRET"))

val provider = DynamoDbS3PersistenceStorageProvider.create {
    region = "us-west-2"
    tableName = "koog-checkpoints"
    bucketName = "my-agent-checkpoints"
    idHashing = IdHashing.HmacSha256 { hashSecret.encodeToByteArray() }
}
```

Changing the hashing strategy changes lookup keys, so existing checkpoints will not be discoverable unless you migrate or keep the same strategy.

## Test From Source

```bash
./gradlew check
```

LocalStack integration tests are opt-in:

```bash
KOOG_AWS_INTEGRATION_TESTS=true ./gradlew integrationTest
```

If Docker Desktop context discovery is flaky, use an externally started LocalStack:

```bash
LOCALSTACK_ENDPOINT=http://localhost:4566 KOOG_AWS_INTEGRATION_TESTS=true ./gradlew integrationTest
```

## Infrastructure

Use the templates in:

- [infra/terraform](infra/terraform)
- [infra/cloudformation](infra/cloudformation/koog-checkpoint-dynamodb-s3.yaml)

## Docs

- [AWS IAM](docs/aws-iam.md)
- [Stateless and multi-pod deployments](docs/kubernetes.md)
- [Checkpointing vs chat memory](docs/checkpointing-vs-chat-memory.md)
- [Failure modes](docs/failure-modes.md)
- [Testing](docs/testing.md)
- [Releasing](docs/releasing.md)

## License

Apache-2.0.
