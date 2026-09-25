"""
delta-rs, the Delta Lake of Python (``deltalake``) and of Polars, on LocalS3.

delta-rs reaches S3 with the ``object_store`` crate of Rust, a client of its own, apart from the AWS SDKs. A Delta
commit is the creation of ``_delta_log/<version>.json``, which must fail if another writer created it first; with
``conditional_put = etag``, the default of delta-rs 1.x on S3, that create is a ``PUT`` with ``If-None-Match: *``, which
LocalS3 answers with ``412`` when the key is taken, and delta-rs needs no lock service (DynamoDB) beside it.
"""

import threading

import polars as pl
import pyarrow as pa
import pytest
from deltalake import DeltaTable, write_deltalake

pytestmark = pytest.mark.timeout(120)


@pytest.fixture
def storage_options(local_s3):
    return {
        "AWS_ENDPOINT_URL": local_s3.endpoint,
        "AWS_ACCESS_KEY_ID": local_s3.access_key,
        "AWS_SECRET_ACCESS_KEY": local_s3.secret_key,
        "AWS_REGION": local_s3.region,
        "AWS_ALLOW_HTTP": "true",
        # The commit is a PUT with If-None-Match: *, the lock that S3 has had since 2024.
        "conditional_put": "etag",
    }


def rows(first: int, last: int) -> pa.Table:
    ids = list(range(first, last + 1))
    return pa.table({"id": pa.array(ids, pa.int64()), "name": [f"row-{i}" for i in ids]})


def test_write_append_and_read(bucket, storage_options, local_s3):
    uri = f"s3://{bucket}/tables/events"
    write_deltalake(uri, rows(1, 3), storage_options=storage_options)
    write_deltalake(uri, rows(4, 5), mode="append", storage_options=storage_options)

    table = DeltaTable(uri, storage_options=storage_options)
    assert table.version() == 1
    assert sorted(table.to_pyarrow_table()["id"].to_pylist()) == [1, 2, 3, 4, 5]
    assert DeltaTable(uri, version=0, storage_options=storage_options).to_pyarrow_table().num_rows == 3

    keys = [o["Key"] for o in local_s3.s3().list_objects_v2(Bucket=bucket, Prefix="tables/events/_delta_log/")
            .get("Contents", [])]
    assert "tables/events/_delta_log/00000000000000000000.json" in keys
    assert "tables/events/_delta_log/00000000000000000001.json" in keys


@pytest.mark.parametrize("conditional_put", ["etag", None], ids=["conditional_put=etag", "default"])
def test_writers_racing_for_a_version_all_keep_their_rows(bucket, storage_options, local_s3, conditional_put):
    """
    Writers that load the table at the same version and append at once race for the same log file: the first
    create wins, the others get 412 Precondition Failed, and delta-rs, seeing that an append doesn't conflict with
    an append, commits again at the next version instead of overwriting the winner.

    delta-rs 1.x creates the log file with If-None-Match: * on S3 by default as well, so ``conditional_put = etag``
    only makes explicit what it does anyway; the older versions refused to write to S3 without it, or a lock.
    """
    if conditional_put is None:
        storage_options = {name: value for name, value in storage_options.items() if name != "conditional_put"}
    uri = f"s3://{bucket}/tables/raced"
    write_deltalake(uri, rows(0, 0), storage_options=storage_options)
    writers = [DeltaTable(uri, storage_options=storage_options) for _ in range(4)]
    barrier = threading.Barrier(len(writers))
    errors = []

    def append(index: int, table: DeltaTable):
        try:
            barrier.wait()
            write_deltalake(table, rows(100 * (index + 1), 100 * (index + 1) + 9), mode="append")
        except Exception as e:  # noqa: BLE001, reported below
            errors.append(e)

    threads = [threading.Thread(target=append, args=(i, table)) for i, table in enumerate(writers)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert errors == []

    table = DeltaTable(uri, storage_options=storage_options)
    assert table.version() == len(writers)
    assert table.to_pyarrow_table().num_rows == 1 + 10 * len(writers)
    refused = [request for request in local_s3.requests()
               if request["status"] == 412 and f"/{bucket}/tables/raced/_delta_log/" in request["uri"]]
    assert refused, "The writers that lost the race should have been refused a commit."


def test_overwrite_delete_and_merge(bucket, storage_options):
    uri = f"s3://{bucket}/tables/changing"
    write_deltalake(uri, rows(1, 10), storage_options=storage_options)
    table = DeltaTable(uri, storage_options=storage_options)

    table.delete("id > 8")
    (table.merge(source=pa.table({"id": pa.array([2, 11], pa.int64()), "name": ["two", "eleven"]}),
                 predicate="target.id = source.id", source_alias="source", target_alias="target")
     .when_matched_update_all()
     .when_not_matched_insert_all()
     .execute())

    result = DeltaTable(uri, storage_options=storage_options).to_pyarrow_table().sort_by("id")
    assert result["id"].to_pylist() == [1, 2, 3, 4, 5, 6, 7, 8, 11]
    assert result["name"].to_pylist()[1] == "two"

    write_deltalake(uri, rows(100, 101), mode="overwrite", storage_options=storage_options)
    assert sorted(DeltaTable(uri, storage_options=storage_options).to_pyarrow_table()["id"].to_pylist()) == [100, 101]


def test_polars_writes_and_reads_a_delta_table(bucket, storage_options):
    uri = f"s3://{bucket}/tables/polars"
    df = pl.DataFrame({"id": range(1000), "part": [f"p{i % 4}" for i in range(1000)]})
    df.write_delta(uri, storage_options=storage_options, delta_write_options={"partition_by": ["part"]})
    df.head(10).write_delta(uri, mode="append", storage_options=storage_options)

    read = pl.read_delta(uri, storage_options=storage_options)
    assert read.height == 1010
    scanned = pl.scan_delta(uri, storage_options=storage_options).filter(pl.col("part") == "p1").select(pl.len())
    # 250 of the first write, and ids 1, 5 and 9 of the append.
    assert scanned.collect().item() == 250 + 3
    assert pl.read_delta(uri, version=0, storage_options=storage_options).height == 1000
