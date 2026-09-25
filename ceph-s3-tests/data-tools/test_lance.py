"""
Lance, the columnar format of vector data (``pylance``), and LanceDB, the vector database on top of it, on LocalS3.

Both reach S3 with the ``object_store`` crate of Rust, as delta-rs does. A Lance commit is the creation of the manifest
of the next version, under ``_versions/``, which must fail if another writer created it first: on S3, Lance creates it
with ``If-None-Match: *``, which LocalS3 answers with ``412`` when the key is taken, and the writer that lost retries on
top of the version that won, with no lock service (DynamoDB) beside it.
"""

import threading
from datetime import timedelta

import lance
import lancedb
import numpy as np
import pyarrow as pa
import pytest

pytestmark = pytest.mark.timeout(180)

DIMENSION = 16


@pytest.fixture
def storage_options(local_s3):
    return {
        "aws_endpoint": local_s3.endpoint,
        "aws_access_key_id": local_s3.access_key,
        "aws_secret_access_key": local_s3.secret_key,
        "aws_region": local_s3.region,
        "allow_http": "true",
    }


def rows(first: int, last: int) -> pa.Table:
    ids = list(range(first, last + 1))
    return pa.table({"id": pa.array(ids, pa.int64()), "name": [f"row-{i}" for i in ids]})


def vectors(count: int, seed: int = 42) -> pa.Table:
    values = np.random.default_rng(seed).random((count, DIMENSION), dtype=np.float32)
    return pa.table({
        "id": pa.array(range(count), pa.int64()),
        "vector": pa.FixedSizeListArray.from_arrays(pa.array(values.ravel(), pa.float32()), DIMENSION),
    })


def keys(local_s3, bucket: str, prefix: str) -> list[str]:
    pages = local_s3.s3().get_paginator("list_objects_v2").paginate(Bucket=bucket, Prefix=prefix)
    return [o["Key"] for page in pages for o in page.get("Contents", [])]


def test_write_append_update_delete_and_time_travel(bucket, storage_options, local_s3):
    uri = f"s3://{bucket}/lance/events.lance"
    lance.write_dataset(rows(1, 3), uri, storage_options=storage_options)
    lance.write_dataset(rows(4, 5), uri, mode="append", storage_options=storage_options)

    dataset = lance.dataset(uri, storage_options=storage_options)
    assert dataset.version == 2
    assert sorted(dataset.to_table()["id"].to_pylist()) == [1, 2, 3, 4, 5]

    dataset.delete("id = 3")
    dataset.update({"name": "'two'"}, where="id = 2")
    dataset = lance.dataset(uri, storage_options=storage_options)
    table = dataset.to_table().sort_by("id")
    assert table["id"].to_pylist() == [1, 2, 4, 5]
    assert table["name"].to_pylist()[1] == "two"
    assert dataset.to_table(filter="id > 3").num_rows == 2

    assert lance.dataset(uri, version=1, storage_options=storage_options).count_rows() == 3
    assert [v["version"] for v in dataset.versions()] == [1, 2, 3, 4]
    assert any(key.startswith("lance/events.lance/_versions/") for key in keys(local_s3, bucket, "lance/"))

    lance.write_dataset(rows(100, 101), uri, mode="overwrite", storage_options=storage_options)
    assert sorted(lance.dataset(uri, storage_options=storage_options).to_table()["id"].to_pylist()) == [100, 101]


def test_compaction_and_cleanup_of_old_versions(bucket, storage_options, local_s3):
    uri = f"s3://{bucket}/lance/small-files.lance"
    for batch in range(5):
        lance.write_dataset(rows(batch * 10, batch * 10 + 9), uri, mode="append" if batch else "create",
                            storage_options=storage_options)
    dataset = lance.dataset(uri, storage_options=storage_options)
    assert len(dataset.get_fragments()) == 5
    files_before = keys(local_s3, bucket, "lance/small-files.lance/data/")

    metrics = dataset.optimize.compact_files(target_rows_per_fragment=1000)
    assert metrics.fragments_removed == 5
    assert metrics.fragments_added == 1
    dataset = lance.dataset(uri, storage_options=storage_options)
    assert len(dataset.get_fragments()) == 1
    assert sorted(dataset.to_table()["id"].to_pylist()) == list(range(50))

    # The files of the fragments that the compaction replaced go with the versions that refer to them.
    stats = dataset.cleanup_old_versions(older_than=timedelta(0), delete_unverified=True)
    assert stats.data_files_removed == 5
    files_after = keys(local_s3, bucket, "lance/small-files.lance/data/")
    assert len(files_after) == 1
    assert not set(files_after) & set(files_before)
    assert lance.dataset(uri, storage_options=storage_options).count_rows() == 50


def test_vector_index_and_nearest_neighbour_search(bucket, storage_options):
    uri = f"s3://{bucket}/lance/embeddings.lance"
    data = vectors(1000)
    dataset = lance.write_dataset(data, uri, storage_options=storage_options)
    dataset.create_index("vector", index_type="IVF_PQ", num_partitions=4, num_sub_vectors=4)

    dataset = lance.dataset(uri, storage_options=storage_options)
    assert [(index.name, index.num_rows_indexed) for index in dataset.describe_indices()] == [("vector_idx", 1000)]
    query = data["vector"][123].values.to_numpy()
    nearest = dataset.to_table(nearest={"column": "vector", "q": query, "k": 5, "nprobes": 4, "refine_factor": 10})
    assert nearest.num_rows == 5
    assert nearest["id"][0].as_py() == 123
    assert nearest["_distance"][0].as_py() == pytest.approx(0.0, abs=1e-5)


def test_writers_racing_for_a_version_all_keep_their_rows(bucket, storage_options, local_s3):
    """
    Writers that append at once race for the manifest of the same version: the first create wins, the others get
    412 Precondition Failed, and Lance, seeing that an append doesn't conflict with an append, commits again at the
    next version instead of overwriting the winner.
    """
    uri = f"s3://{bucket}/lance/raced.lance"
    lance.write_dataset(rows(0, 0), uri, storage_options=storage_options)
    writers = 4
    barrier = threading.Barrier(writers)
    errors = []

    def append(index: int):
        try:
            barrier.wait()
            lance.write_dataset(rows(100 * (index + 1), 100 * (index + 1) + 9), uri, mode="append",
                                storage_options=storage_options)
        except Exception as e:  # noqa: BLE001, reported below
            errors.append(e)

    threads = [threading.Thread(target=append, args=(i,)) for i in range(writers)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    assert errors == []

    dataset = lance.dataset(uri, storage_options=storage_options)
    assert dataset.version == 1 + writers
    assert dataset.count_rows() == 1 + 10 * writers
    refused = [request for request in local_s3.requests()
               if request["status"] == 412 and f"/{bucket}/lance/raced.lance/_versions/" in request["uri"]]
    assert refused, "The writers that lost the race should have been refused a commit."


def test_lancedb_creates_searches_optimizes_and_drops_a_table(bucket, storage_options, local_s3):
    db = lancedb.connect(f"s3://{bucket}/lancedb", storage_options=storage_options)
    data = vectors(300)
    table = db.create_table("docs", data)
    table.add(vectors(20, seed=7).set_column(0, "id", pa.array(range(300, 320), pa.int64())))
    assert db.list_tables().tables == ["docs"]
    assert table.count_rows() == 320

    query = data["vector"][42].values.to_numpy()
    hits = table.search(query).limit(3).to_list()
    assert hits[0]["id"] == 42
    assert table.count_rows("id >= 300") == 20

    table.delete("id < 10")
    table.optimize(cleanup_older_than=timedelta(0))
    assert db.open_table("docs").count_rows() == 310

    db.drop_table("docs")
    assert db.list_tables().tables == []
    assert keys(local_s3, bucket, "lancedb/docs.lance/") == []
