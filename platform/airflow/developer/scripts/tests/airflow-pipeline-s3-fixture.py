#!/usr/bin/env python3
"""Create or remove the isolated landing object used by the live DAG test."""

import argparse
import os
import time

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

CUSTOMER_CSV = """customer_id,customer_name,email,country
1001,Ada Lovelace,ada@example.test,GB
1002,Grace Hopper,grace@example.test,US
1003,Edsger Dijkstra,edsger@example.test,NL
"""


def client():
    """Build the fixture client from the Spark identity already in container env."""
    return boto3.client(
        "s3",
        endpoint_url=os.environ["CEPH_RGW_ENDPOINT"],
        verify=os.environ["AWS_CA_BUNDLE"],
        config=Config(s3={"addressing_style": "path"}),
    )


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("put", "delete"))
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--key", required=True)
    args = parser.parse_args()
    started_ns = time.monotonic_ns()
    s3 = client()

    if args.operation == "put":
        body = CUSTOMER_CSV.encode("utf-8")
        s3.put_object(Bucket=args.bucket, Key=args.key, Body=body, ContentType="text/csv")
        observed = s3.head_object(Bucket=args.bucket, Key=args.key)["ContentLength"]
        if observed != len(body):
            raise RuntimeError(f"landing object size mismatch: expected {len(body)}, got {observed}")
        status = f"bytes={observed}"
    else:
        s3.delete_object(Bucket=args.bucket, Key=args.key)
        try:
            s3.head_object(Bucket=args.bucket, Key=args.key)
        except ClientError as failure:
            if failure.response.get("ResponseMetadata", {}).get("HTTPStatusCode") != 404:
                raise
        else:
            raise RuntimeError("landing object still exists after deletion")
        status = "remaining=0"

    elapsed_ms = (time.monotonic_ns() - started_ns) // 1_000_000
    print(
        "event=airflow_pipeline_fixture_completed "
        f"operation={args.operation} bucket={args.bucket} key={args.key} "
        f"{status} elapsedMs={elapsed_ms}"
    )


if __name__ == "__main__":
    main()
