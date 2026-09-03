# Changelog

## 0.1.0

Initial release:

- Koog `PersistenceStorageProvider` backed by DynamoDB metadata and S3 payloads.
- Content-addressed S3 payloads with checksum verification.
- Strongly consistent DynamoDB reads by default for immediate pod handoff.
- Admin inspect, verify, and delete operations that expose hashed IDs only.
- Terraform and CloudFormation starter infrastructure.
