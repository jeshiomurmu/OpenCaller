# Generates presigned-URL (SigV4 query) vectors for SigV4SignerTest with botocore's S3SigV4QueryAuth.
import datetime, sys
from unittest import mock
import botocore
from botocore.auth import S3SigV4QueryAuth
from botocore.awsrequest import AWSRequest
from botocore.credentials import Credentials

creds = Credentials("test-access-key", "test-secret-key/not+real")
cases = [
    ("PUT", "http://minio:9000/opencaller/avatars/0f0e/abc.jpg", "image/jpeg", "20261101T120000Z", "us-east-1", 600),
    ("GET", "http://minio:9000/opencaller/avatars/0f0e/abc.jpg", "", "20261101T120000Z", "us-east-1", 900),
    ("GET", "https://s3.example.com/my-bucket/a%20b/c%2Bd.jpg", "", "20260101T000000Z", "eu-west-1", 60),
]
print("# AWS Signature V4 presigned URLs (query auth) for SigV4SignerTest, generated with botocore %s (S3SigV4QueryAuth)" % botocore.__version__)
print("# by gen_presign_vectors.py next to this file. Fake credentials: test-access-key / test-secret-key/not+real.")
print("# method,url,content_type,amz_date,region,expires,presigned_url")
for method, url, ctype, date, region, expires in cases:
    headers = {"Content-Type": ctype} if ctype else {}
    req = AWSRequest(method=method, url=url, headers=headers)
    fixed = datetime.datetime.strptime(date, "%Y%m%dT%H%M%SZ")
    with mock.patch("botocore.auth.get_current_datetime", return_value=fixed):
        S3SigV4QueryAuth(creds, "s3", region, expires=expires).add_auth(req)
    print(",".join([method, url, ctype, date, region, str(expires), req.url]))
