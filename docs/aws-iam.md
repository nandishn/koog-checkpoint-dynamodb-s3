# AWS IAM

This library needs access to one DynamoDB table and one S3 prefix. It does not need broad S3 list permissions for normal checkpoint reads/writes.

Replace `REGION`, `ACCOUNT_ID`, `BUCKET_NAME`, and `PREFIX` before use.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "KoogCheckpointMetadata",
      "Effect": "Allow",
      "Action": [
        "dynamodb:GetItem",
        "dynamodb:Query",
        "dynamodb:TransactWriteItems"
      ],
      "Resource": "arn:aws:dynamodb:REGION:ACCOUNT_ID:table/koog-checkpoints"
    },
    {
      "Sid": "KoogCheckpointPayloads",
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject",
        "s3:PutObjectTagging"
      ],
      "Resource": "arn:aws:s3:::BUCKET_NAME/PREFIX/*"
    }
  ]
}
```

For SSE-KMS, add:

```json
{
  "Sid": "KoogCheckpointKms",
  "Effect": "Allow",
  "Action": [
    "kms:Encrypt",
    "kms:Decrypt",
    "kms:GenerateDataKey"
  ],
  "Resource": "arn:aws:kms:REGION:ACCOUNT_ID:key/KEY_ID"
}
```

Security defaults:

- Session IDs are hashed before they are used in DynamoDB keys, S3 keys, or logs.
- S3 SSE-S3 is enabled by default.
- The library does not log checkpoint payloads.
- The runtime does not create or mutate infrastructure.
