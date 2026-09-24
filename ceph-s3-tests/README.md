ceph/s3-tests
=============

The S3 compatibility tests of [ceph/s3-tests](https://github.com/ceph/s3-tests), run against the executable jar of
LocalS3 with boto3. They check LocalS3 with a client other than the AWS SDK for Java, against the expectations that
Ceph RGW, MinIO and other S3 implementations are measured with.

```shell
./gradlew :local-s3-standalone:jar
ceph-s3-tests/run.sh                            # or: just s3-tests
ceph-s3-tests/run.sh -k multipart -n 0          # the other arguments go to pytest
ceph-s3-tests/run.sh --update-known-failures    # writes the failed tests to known-failures.txt
```

`run.sh` checks s3-tests out at a pinned commit, syncs the Python environment with uv, starts the jar on a free port
with an access key of its own, and runs `s3tests/functional/test_s3.py` and `test_headers.py` in 8 processes. The
checkout, the log of LocalS3 and a JUnit report go to `ceph-s3-tests/build`. It leaves out the
`AWS_*` and `LOCAL_S3_*` variables of the shell, which would otherwise configure LocalS3 or send boto3 elsewhere.

## Python environment

[uv](https://docs.astral.sh/uv/) manages it: `pyproject.toml` lists the packages that the tests import, `uv.lock` pins
their versions, `.python-version` the Python version, and `run.sh` installs them into `ceph-s3-tests/.venv` with
`uv sync --locked`. A package is added or upgraded with uv, which updates both files:

```shell
cd ceph-s3-tests
uv add 'boto3>=1.44'                  # or: uv lock --upgrade-package boto3
uv lock --upgrade                     # every package to its latest version
```

## Which tests run

The core S3 API with a single access key. `local_s3_plugin.py` deselects:

| Tests | Why |
|---|---|
| `bucket_logging`, `fails_on_rgw`, `target_by_bucket` and the other markers of RGW | RGW's own features |
| `lifecycle_expiration`, `lifecycle_transition`, `cloud_*` | need a lifecycle running in the background; LocalS3 applies one when a test asks for it |
| `auth_aws2` | Signature Version 2, which is obsolete |
| `fails_on_aws` | fail on Amazon S3 as well |
| `bucket_policy`, `iam_*` | access control, which LocalS3 stores without enforcing |
| the tests that use the alt or tenant user, an unsigned client or IAM | LocalS3 has a single access key |
| the tests that call the ACL operations, e.g. `get_object_acl`, or set a bucket policy | LocalS3 stores ACLs and bucket policies without enforcing them |
| the tests that send unsigned HTTP requests of their own | anonymous access |

A test that gives a canned ACL when it creates a bucket or an object still runs: LocalS3 accepts the ACL, and what the
test checks is something else. The IAM, STS, SNS, S3 Select and S3 Control tests of s3-tests don't run at all.

## Known failures

`known-failures.txt` lists the tests that fail on LocalS3 today, which the plugin marks as expected to fail
(`xfail(strict=True)`). A run fails on a test that fails and isn't listed, which is a regression, and on a listed test
that passes, which is to be taken off the list. `--update-known-failures` runs every test without the list and writes
the failed ones to it.

A new version of s3-tests is taken by changing `S3_TESTS_COMMIT` in `run.sh`, running it with
`--update-known-failures` and reviewing the change of `known-failures.txt`.
