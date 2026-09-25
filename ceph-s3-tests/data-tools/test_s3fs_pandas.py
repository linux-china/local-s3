"""
fsspec's s3fs, and pandas on top of it: ``pd.read_parquet("s3://...")`` and the other ``s3://`` paths of pandas go
through s3fs, which sends its requests with aiobotocore, the asyncio port of botocore on aiohttp. That is a client of
its own, apart from boto3: its own HTTP stack, its own chunked uploads with trailing checksums, and the multipart
uploads, range reads and batch deletes that a file system on S3 is made of.
"""

import os

import pandas as pd
import pyarrow.parquet as pq
import pytest
import s3fs

pytestmark = pytest.mark.timeout(120)


@pytest.fixture
def storage_options(local_s3):
    """What pandas hands to s3fs.S3FileSystem, for a LocalS3 that verifies signatures."""
    return {
        "key": local_s3.access_key,
        "secret": local_s3.secret_key,
        "endpoint_url": local_s3.endpoint,
        "client_kwargs": {"region_name": local_s3.region},
    }


@pytest.fixture
def fs(storage_options):
    # A file system of its own for each test, not the instance fsspec caches for the same options.
    return s3fs.S3FileSystem(skip_instance_cache=True, **storage_options)


def frame(rows: int) -> pd.DataFrame:
    return pd.DataFrame({
        "id": range(rows),
        "part": [f"p{i % 3}" for i in range(rows)],
        "label": [f"数据 {i}" for i in range(rows)],
        "amount": [i * 1.5 for i in range(rows)],
    })


def test_pandas_writes_and_reads_parquet_and_csv(bucket, storage_options):
    df = frame(100)
    df.to_parquet(f"s3://{bucket}/frames/one.parquet", storage_options=storage_options)
    df.to_csv(f"s3://{bucket}/frames/one.csv", index=False, storage_options=storage_options)

    pd.testing.assert_frame_equal(pd.read_parquet(f"s3://{bucket}/frames/one.parquet",
                                                  storage_options=storage_options), df)
    pd.testing.assert_frame_equal(pd.read_csv(f"s3://{bucket}/frames/one.csv",
                                              storage_options=storage_options), df)


def test_pandas_reads_a_partitioned_dataset(bucket, storage_options, fs):
    df = frame(90)
    df.to_parquet(f"s3://{bucket}/dataset", partition_cols=["part"], storage_options=storage_options)
    assert sorted(fs.ls(f"{bucket}/dataset")) == [f"{bucket}/dataset/part=p{i}" for i in range(3)]

    read = pd.read_parquet(f"s3://{bucket}/dataset", storage_options=storage_options)
    assert sorted(read["id"].tolist()) == list(range(90))
    assert sorted(read["part"].astype(str).unique().tolist()) == ["p0", "p1", "p2"]

    # A filter pushed down to the partitions reads the files of one partition alone.
    only_p1 = pd.read_parquet(f"s3://{bucket}/dataset", filters=[("part", "==", "p1")],
                              storage_options=storage_options)
    assert sorted(only_p1["id"].tolist()) == list(range(1, 90, 3))


def test_a_large_file_is_written_in_parts_and_read_in_ranges(bucket, fs, local_s3):
    data = os.urandom(12 * 1024 * 1024 + 123)
    # s3fs uploads a file larger than its block size as a multipart upload, a block per part.
    with fs.open(f"{bucket}/large.bin", "wb", block_size=5 * 1024 * 1024) as out:
        out.write(data)
    operations = {request["operation"] for request in local_s3.requests()}
    assert {"CreateMultipartUpload", "UploadPart", "CompleteMultipartUpload"} <= operations, operations

    assert fs.info(f"{bucket}/large.bin")["size"] == len(data)
    assert fs.cat_file(f"{bucket}/large.bin", start=1000, end=2000) == data[1000:2000]
    with fs.open(f"{bucket}/large.bin", "rb", block_size=1024 * 1024) as file:
        file.seek(len(data) - 100)
        assert file.read() == data[-100:]
    assert fs.cat_file(f"{bucket}/large.bin") == data


def test_parquet_is_read_by_row_group_with_range_requests(bucket, fs):
    df = frame(50_000)
    with fs.open(f"{bucket}/grouped.parquet", "wb") as out:
        df.to_parquet(out, row_group_size=10_000)

    with fs.open(f"{bucket}/grouped.parquet", "rb") as file:
        parquet = pq.ParquetFile(file)
        assert parquet.num_row_groups == 5
        assert parquet.read_row_group(3).column("id").to_pylist() == list(range(30_000, 40_000))


def test_the_file_system_operations(bucket, fs, tmp_path):
    local = tmp_path / "local.txt"
    local.write_text("hello")
    fs.put(str(local), f"{bucket}/dir/a.txt")
    fs.pipe(f"{bucket}/dir/sub/b.txt", b"world")
    fs.pipe(f"{bucket}/dir/key with spaces+plus.txt", b"!")

    assert fs.exists(f"{bucket}/dir/a.txt")
    assert fs.isdir(f"{bucket}/dir/sub")
    assert sorted(fs.find(f"{bucket}/dir")) == [
        f"{bucket}/dir/a.txt", f"{bucket}/dir/key with spaces+plus.txt", f"{bucket}/dir/sub/b.txt"]
    assert fs.glob(f"{bucket}/dir/**/*.txt") == sorted(fs.find(f"{bucket}/dir"))
    assert fs.cat(f"{bucket}/dir/key with spaces+plus.txt") == b"!"

    fs.copy(f"{bucket}/dir/a.txt", f"{bucket}/copied/a.txt")
    fs.mv(f"{bucket}/dir/sub/b.txt", f"{bucket}/moved/b.txt")
    assert fs.cat(f"{bucket}/copied/a.txt") == b"hello"
    assert fs.cat(f"{bucket}/moved/b.txt") == b"world"
    assert not fs.exists(f"{bucket}/dir/sub/b.txt")

    fs.get(f"{bucket}/moved/b.txt", str(tmp_path / "b.txt"))
    assert (tmp_path / "b.txt").read_bytes() == b"world"

    # A recursive delete is a DeleteObjects of the keys it found.
    fs.rm(f"{bucket}/dir", recursive=True)
    fs.invalidate_cache()
    assert fs.find(f"{bucket}/dir") == []


def test_wrong_credentials_are_refused(bucket, storage_options):
    wrong = s3fs.S3FileSystem(skip_instance_cache=True, **{**storage_options, "secret": "wrong"})
    with pytest.raises(PermissionError):
        wrong.cat(f"{bucket}/missing.txt")
