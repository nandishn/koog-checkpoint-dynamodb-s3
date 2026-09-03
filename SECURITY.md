# Security Policy

Please report security issues privately to the repository owner.

Do not open public issues for:

- Credential leaks.
- Checkpoint payload exposure.
- IAM privilege escalation.
- Encryption bypass.
- Tenant/session ID leakage.

Security expectations:

- Raw checkpoint payloads must never be logged.
- Raw session IDs should not appear in S3 keys or DynamoDB keys.
- Raw checkpoint IDs should not appear in DynamoDB metadata records.
- Predictable or sensitive session IDs should use `IdHashing.HmacSha256` with a secret stored outside the repository.
- KMS support should preserve caller-provided key IDs.
- IAM examples should remain least-privilege.
