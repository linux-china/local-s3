"""
PyIceberg on the built-in Iceberg REST catalog of LocalS3.

The catalog is given its URI and nothing else: the S3 endpoint, the path-style addressing and the credentials reach
PyIceberg through the credential vending of the REST protocol, and its ``FileIO`` (PyArrow's S3 file system) writes
and reads the data files with them against a LocalS3 that verifies every signature.
"""

from datetime import datetime, timezone

import pyarrow as pa
import pytest
from pyiceberg.catalog import load_catalog
from pyiceberg.exceptions import NoSuchTableError
from pyiceberg.expressions import EqualTo, GreaterThanOrEqual
from pyiceberg.partitioning import PartitionField, PartitionSpec
from pyiceberg.schema import Schema
from pyiceberg.transforms import BucketTransform, DayTransform
from pyiceberg.types import DoubleType, LongType, NestedField, StringType, TimestamptzType

pytestmark = pytest.mark.timeout(120)

SCHEMA = Schema(
    NestedField(1, "id", LongType(), required=True),
    NestedField(2, "name", StringType()),
    NestedField(3, "level", StringType()),
)


def rows(first: int, last: int, level: str) -> pa.Table:
    ids = list(range(first, last + 1))
    return pa.table({
        "id": pa.array(ids, pa.int64()),
        "name": [f"row-{i}" for i in ids],
        "level": [level] * len(ids),
    }, schema=SCHEMA.as_arrow())


@pytest.fixture
def catalog(local_s3):
    # The URI is the whole configuration: no s3.endpoint, no key.
    return load_catalog("local", type="rest", uri=local_s3.iceberg_uri)


@pytest.fixture
def namespace(catalog, request):
    name = request.node.name.removeprefix("test_")[:40]
    catalog.create_namespace(name)
    return name


def test_the_catalog_vends_the_storage_to_pyiceberg(catalog, local_s3):
    properties = catalog.properties
    assert properties["s3.endpoint"].rstrip("/") == local_s3.endpoint
    assert properties["s3.access-key-id"] == local_s3.access_key


def test_create_append_and_scan(catalog, namespace, local_s3):
    table = catalog.create_table(f"{namespace}.events", schema=SCHEMA)
    assert table.location() == f"s3://warehouse/{namespace}/events"

    table.append(rows(1, 3, "info"))
    table.append(rows(4, 5, "warn"))

    scanned = table.scan().to_arrow().sort_by("id")
    assert scanned["id"].to_pylist() == [1, 2, 3, 4, 5]
    assert table.scan(row_filter=EqualTo("level", "warn")).to_arrow()["id"].to_pylist() == [4, 5]
    assert len(table.snapshots()) == 2

    # Read back through a catalog of its own, which shares nothing with the writer but LocalS3.
    reloaded = load_catalog("reader", type="rest", uri=local_s3.iceberg_uri).load_table(f"{namespace}.events")
    assert reloaded.scan(selected_fields=("id",)).to_arrow().num_rows == 5

    # The metadata and the data files are objects of the warehouse bucket.
    keys = [o["Key"] for o in local_s3.s3().list_objects_v2(Bucket="warehouse", Prefix=f"{namespace}/events/")
            .get("Contents", [])]
    assert any(key.endswith(".metadata.json") for key in keys), keys
    assert sum(key.endswith(".parquet") for key in keys) == 2, keys


def test_time_travel_to_an_earlier_snapshot(catalog, namespace):
    table = catalog.create_table(f"{namespace}.travel", schema=SCHEMA)
    table.append(rows(1, 2, "first"))
    first = table.current_snapshot().snapshot_id
    table.append(rows(3, 4, "second"))

    assert table.scan(snapshot_id=first).to_arrow()["id"].to_pylist() == [1, 2]
    assert table.scan().to_arrow().num_rows == 4


def test_overwrite_and_delete(catalog, namespace):
    table = catalog.create_table(f"{namespace}.changing", schema=SCHEMA)
    table.append(rows(1, 10, "info"))

    table.delete(delete_filter=GreaterThanOrEqual("id", 8))
    assert sorted(table.scan().to_arrow()["id"].to_pylist()) == list(range(1, 8))

    table.overwrite(rows(100, 101, "error"))
    assert sorted(table.scan().to_arrow()["id"].to_pylist()) == [100, 101]


def test_a_partitioned_table_prunes_its_files(catalog, namespace):
    schema = Schema(
        NestedField(1, "id", LongType(), required=True),
        NestedField(2, "at", TimestamptzType(), required=True),
        NestedField(3, "value", DoubleType()),
    )
    spec = PartitionSpec(
        PartitionField(source_id=2, field_id=1000, transform=DayTransform(), name="at_day"),
        PartitionField(source_id=1, field_id=1001, transform=BucketTransform(4), name="id_bucket"),
    )
    table = catalog.create_table(f"{namespace}.metrics", schema=schema, partition_spec=spec)
    table.append(pa.table({
        "id": pa.array(range(40), pa.int64()),
        "at": pa.array([datetime(2026, 1, 1 + i % 4, 12, tzinfo=timezone.utc) for i in range(40)],
                       pa.timestamp("us", "UTC")),
        "value": [i * 0.5 for i in range(40)],
    }, schema=schema.as_arrow()))

    every_file = len(list(table.scan().plan_files()))
    one_day = table.scan(row_filter="at >= '2026-01-04T00:00:00+00:00'")
    assert 0 < len(list(one_day.plan_files())) < every_file
    assert one_day.to_arrow().num_rows == 10


def test_schema_evolution(catalog, namespace):
    table = catalog.create_table(f"{namespace}.evolving", schema=SCHEMA)
    table.append(rows(1, 2, "info"))

    with table.update_schema() as update:
        update.add_column("score", DoubleType())
        update.rename_column("level", "severity")

    table = catalog.load_table(f"{namespace}.evolving")
    assert [field.name for field in table.schema().fields] == ["id", "name", "severity", "score"]
    table.append(pa.table({"id": pa.array([3], pa.int64()), "name": ["row-3"], "severity": ["warn"],
                           "score": [1.5]}, schema=table.schema().as_arrow()))

    scanned = table.scan().to_arrow().sort_by("id")
    assert scanned["severity"].to_pylist() == ["info", "info", "warn"]
    assert scanned["score"].to_pylist() == [None, None, 1.5]


def test_rename_and_drop(catalog, namespace):
    catalog.create_table(f"{namespace}.draft", schema=SCHEMA).append(rows(1, 1, "info"))
    catalog.rename_table(f"{namespace}.draft", f"{namespace}.final")
    assert catalog.list_tables(namespace) == [(namespace, "final")]
    assert catalog.load_table(f"{namespace}.final").scan().to_arrow().num_rows == 1

    catalog.drop_table(f"{namespace}.final")
    with pytest.raises(NoSuchTableError):
        catalog.load_table(f"{namespace}.final")
    catalog.drop_namespace(namespace)
    assert (namespace,) not in catalog.list_namespaces()
