terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

variable "name" {
  type    = string
  default = "koog-checkpoints"
}

variable "bucket_name" {
  type = string
}

variable "checkpoint_prefix" {
  type    = string
  default = "koog/checkpoints"
}

variable "expiration_days" {
  type    = number
  default = 30
}

resource "aws_dynamodb_table" "checkpoints" {
  name         = var.name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pk"
  range_key    = "sk"

  attribute {
    name = "pk"
    type = "S"
  }

  attribute {
    name = "sk"
    type = "S"
  }

  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled = true
  }
}

resource "aws_s3_bucket" "payloads" {
  bucket = var.bucket_name
}

resource "aws_s3_bucket_public_access_block" "payloads" {
  bucket                  = aws_s3_bucket.payloads.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "payloads" {
  bucket = aws_s3_bucket.payloads.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "payloads" {
  bucket = aws_s3_bucket.payloads.id

  rule {
    id     = "expire-koog-checkpoints"
    status = "Enabled"

    filter {
      prefix = var.checkpoint_prefix
    }

    expiration {
      days = var.expiration_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

output "dynamodb_table_name" {
  value = aws_dynamodb_table.checkpoints.name
}

output "s3_bucket_name" {
  value = aws_s3_bucket.payloads.bucket
}
