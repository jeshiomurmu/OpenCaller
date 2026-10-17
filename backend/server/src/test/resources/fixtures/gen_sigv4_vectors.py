# Generates sigv4_vectors.csv for SigV4SignerTest with botocore's S3SigV4Auth.
# Run: python3 -m venv v && v/bin/pip install botocore && v/bin/python -I gen_sigv4_vectors.py
import datetime, hashlib, sys
from unittest import mock
import botocore
from botocore.auth import S3SigV4Auth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials

creds = Credentials("test-access-key", "test-secret-key/not+real")
cases = [
    ("PUT", "http://minio:9000/opencaller/lists/BD/3/full.ocsl", b"hello list", "20261016T120000Z", "us-east-1"),
    ("GET", "http://minio:9000/opencaller/lists/BD/3/full.ocsl", b"", "20261016T120000Z", "us-east-1"),
    ("PUT", "http://minio:9000/opencaller", b"", "20261231T235959Z", "us-east-1"),
    ("GET", "https://s3.example.com/my-bucket/a%20b/c%2Bd.txt", b"", "20260101T000000Z", "eu-west-1"),
]
print("# method,url,payload_utf8,amz_date,region,signature  (botocore %s, S3SigV4Auth)" % botocore.__version__)
for method, url, body, date, region in cases:
    req = AWSRequest(method=method, url=url, data=body)
    fixed = datetime.datetime.strptime(date, "%Y%m%dT%H%M%SZ")
    with mock.patch("botocore.auth.get_current_datetime", return_value=fixed):
        S3SigV4Auth(creds, "s3", region).add_auth(req)
    assert req.headers["X-Amz-Date"] == date, req.headers["X-Amz-Date"]
    assert req.headers["X-Amz-Content-SHA256"] == hashlib.sha256(body).hexdigest()
    signature = req.headers["Authorization"].rsplit("Signature=", 1)[1]
    print(",".join([method, url, body.decode(), date, region, signature]))
