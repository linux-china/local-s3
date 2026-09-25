Python data tools
=================

The Python clients that data engineers point at S3, run against the executable jar of LocalS3: each reaches S3 with
an HTTP stack of its own, apart from the AWS SDK for Java that the other tests use.

| Test | Client | What it checks |
|---|---|---|
| `test_pyiceberg.py` | PyIceberg on the built-in Iceberg REST catalog, PyArrow's S3 file system | create, append, scan, time travel, delete, overwrite, partition pruning, schema evolution, rename and drop, with the storage vended by the catalog |
| `test_delta.py` | delta-rs (`deltalake`) and Polars, Rust's `object_store` | write, append, delete, merge, overwrite, time travel; writers racing for a version, whose commits are `PUT`s with `If-None-Match: *` |
| `test_s3fs_pandas.py` | fsspec's s3fs on aiobotocore, pandas | `pd.read_parquet` / `to_parquet` / `read_csv`, partitioned datasets, multipart uploads, range reads, copy, move, recursive delete |

```shell
./gradlew :local-s3-standalone:jar
ceph-s3-tests/data-tools/run.sh               # or: just data-tools-py
ceph-s3-tests/data-tools/run.sh -k delta      # the arguments go to pytest
```

`conftest.py` starts the jar once, on a free port, with an access key of its own, so every request has to be signed
right, and with the Iceberg REST catalog on; its log and a JUnit report go to `ceph-s3-tests/build`. The `AWS_*`
variables of the shell are taken out of the environment first: every one of these clients would otherwise take the
credentials, the region or the endpoint of a real account from there. `LOCAL_S3_JAR` overrides the jar.

The environment is a uv project of its own, beside the one of [ceph/s3-tests](../README.md): s3fs runs on
aiobotocore, which pins an older botocore than s3-tests asks for, so the two can't share a lock file.

```shell
cd ceph-s3-tests/data-tools
uv lock --upgrade                     # every package to its latest version
```

How to configure each client is in [docs/data-tools.md](../../docs/data-tools.md#python).
