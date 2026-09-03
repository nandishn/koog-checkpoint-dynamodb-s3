# Contributing

Thanks for helping make Koog checkpointing boring in production.

Before opening a pull request:

- Run `./gradlew check`.
- Run `KOOG_AWS_INTEGRATION_TESTS=true ./gradlew integrationTest` when changing AWS request semantics.
- Add or update tests for behavior changes.
- Keep session IDs and checkpoint payloads out of logs.
- Keep core code free of Spring dependencies.
- Prefer small, reviewable changes.

Design principles:

- DynamoDB is the metadata source of truth.
- S3 stores immutable checkpoint payloads.
- Checkpointing is not chat memory.
