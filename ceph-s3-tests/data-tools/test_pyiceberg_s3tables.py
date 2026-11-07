"""
PyIceberg on the Iceberg REST endpoint of an S3 Tables table bucket of LocalS3.

The catalog is configured the way Amazon documents for its own endpoint
(https://docs.aws.amazon.com/AmazonS3/latest/userguide/s3-tables-integrating-open-source.html): the ARN of the table
bucket as the warehouse and SigV4 with the signing name ``s3tables``. Only the URI, pointed at LocalS3, and the keys,
which a real account would take from its environment, differ. The tests then check that the S3 Tables API and the
REST endpoint are two views of one catalog.
"""

import uuid

import boto3
import pyarrow as pa
import pytest
from botocore.exceptions import ClientError
from pyiceberg.catalog import load_catalog
from pyiceberg.exceptions import ForbiddenError, NamespaceAlreadyExistsError, NoSuchTableError
from pyiceberg.expressions import EqualTo
from pyiceberg.schema import Schema
from pyiceberg.types import DoubleType, LongType, NestedField, StringType

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


def s3tables_catalog(local_s3, arn: str, name: str = "s3tables", secret_key: str | None = None):
    """The configuration of the AWS documentation, with the URI of LocalS3 and its keys."""
    return load_catalog(name, **{
        "type": "rest",
        "warehouse": arn,
        "uri": local_s3.iceberg_uri,
        "rest.sigv4-enabled": "true",
        "rest.signing-name": "s3tables",
        "rest.signing-region": local_s3.region,
        "client.region": local_s3.region,
        "client.access-key-id": local_s3.access_key,
        "client.secret-access-key": secret_key or local_s3.secret_key,
    })


@pytest.fixture
def tables(local_s3):
    """A boto3 client of the S3 Tables API, signed for ``s3tables`` and so told apart from S3 by its scope."""
    return boto3.client("s3tables", endpoint_url=local_s3.endpoint, region_name=local_s3.region,
                        aws_access_key_id=local_s3.access_key, aws_secret_access_key=local_s3.secret_key)


@pytest.fixture
def table_bucket(tables) -> str:
    """The ARN of a table bucket of its own for a test."""
    arn = tables.create_table_bucket(name=f"tb-{uuid.uuid4().hex[:12]}")["arn"]
    yield arn
    empty_and_delete(tables, arn)


def empty_and_delete(tables, arn: str) -> None:
    # Like Amazon S3 Tables, a table bucket is deleted only once it has no namespaces left, and a namespace once it has
    # no tables left.
    for namespace in tables.list_namespaces(tableBucketARN=arn)["namespaces"]:
        name = namespace["namespace"][0]
        for table in tables.list_tables(tableBucketARN=arn, namespace=name)["tables"]:
            tables.delete_table(tableBucketARN=arn, namespace=name, name=table["name"])
        tables.delete_namespace(tableBucketARN=arn, namespace=name)
    tables.delete_table_bucket(tableBucketARN=arn)


@pytest.fixture
def catalog(local_s3, table_bucket):
    return s3tables_catalog(local_s3, table_bucket)


def bucket_name(arn: str) -> str:
    return arn.rsplit("/", 1)[1]


def test_the_table_bucket_is_the_prefix_of_the_catalog(catalog, table_bucket, local_s3):
    # GET /v1/config answered the ARN, encoded as one path segment, as the prefix of every request.
    assert catalog.properties["prefix"] == table_bucket.replace(":", "%3A").replace("/", "%2F")
    assert catalog.properties["s3.endpoint"].rstrip("/") == local_s3.endpoint
    assert catalog.list_namespaces() == []


def test_a_wrong_signature_is_refused(local_s3, table_bucket):
    with pytest.raises(ForbiddenError):
        s3tables_catalog(local_s3, table_bucket, secret_key="not-the-secret").list_namespaces()


def test_namespaces_are_those_of_the_s3_tables_api(catalog, tables, table_bucket):
    catalog.create_namespace("sales")
    tables.create_namespace(tableBucketARN=table_bucket, namespace=["marketing"])

    assert sorted(catalog.list_namespaces()) == [("marketing",), ("sales",)]
    assert sorted(n["namespace"][0] for n in tables.list_namespaces(tableBucketARN=table_bucket)["namespaces"]) \
        == ["marketing", "sales"]
    assert catalog.namespace_exists("marketing")
    with pytest.raises(NamespaceAlreadyExistsError):
        catalog.create_namespace("marketing")

    catalog.drop_namespace("marketing")
    assert catalog.list_namespaces() == [("sales",)]
    with pytest.raises(ClientError) as error:
        tables.get_namespace(tableBucketARN=table_bucket, namespace="marketing")
    assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 404


def test_a_table_created_by_pyiceberg_is_one_of_the_s3_tables_api(catalog, tables, table_bucket, local_s3):
    catalog.create_namespace("db")
    table = catalog.create_table("db.events", schema=SCHEMA)
    table.append(rows(1, 3, "info"))
    table.append(rows(4, 5, "warn"))

    assert table.scan(row_filter=EqualTo("level", "warn")).to_arrow()["id"].to_pylist() == [4, 5]
    assert len(table.snapshots()) == 2

    described = tables.get_table(tableBucketARN=table_bucket, namespace="db", name="events")
    assert described["format"] == "ICEBERG"
    assert described["metadataLocation"] == table.metadata_location
    assert [t["name"] for t in tables.list_tables(tableBucketARN=table_bucket, namespace="db")["tables"]] \
        == ["events"]
    assert tables.get_table_metadata_location(tableBucketARN=table_bucket, namespace="db", name="events")[
        "metadataLocation"] == table.metadata_location

    # The files are in the ordinary bucket that LocalS3 keeps beside the table bucket, where an S3 client sees them.
    files = f"{bucket_name(table_bucket)}--table-s3"
    assert table.location().startswith(f"s3://{files}/")
    prefix = table.location().removeprefix(f"s3://{files}/")
    keys = [o["Key"] for o in local_s3.s3().list_objects_v2(Bucket=files, Prefix=prefix).get("Contents", [])]
    assert any(key.endswith(".metadata.json") for key in keys), keys
    assert sum(key.endswith(".parquet") for key in keys) == 2, keys

    # Read back through a catalog of its own, which shares nothing with the writer but LocalS3.
    reader = s3tables_catalog(local_s3, table_bucket, name="reader")
    assert reader.load_table("db.events").scan(selected_fields=("id",)).to_arrow().num_rows == 5


def test_a_table_created_by_the_s3_tables_api_is_written_by_pyiceberg(catalog, tables, table_bucket):
    tables.create_namespace(tableBucketARN=table_bucket, namespace=["db"])
    tables.create_table(tableBucketARN=table_bucket, namespace="db", name="orders", format="ICEBERG", metadata={
        "iceberg": {"schema": {"fields": [
            {"name": "id", "type": "long", "required": True},
            {"name": "total", "type": "double"},
        ]}}})
    before = tables.get_table(tableBucketARN=table_bucket, namespace="db", name="orders")["versionToken"]

    assert catalog.list_tables("db") == [("db", "orders")]
    table = catalog.load_table("db.orders")
    assert [(f.name, f.field_type, f.required) for f in table.schema().fields] \
        == [("id", LongType(), True), ("total", DoubleType(), False)]
    table.append(pa.table({"id": pa.array([1, 2], pa.int64()), "total": [9.5, 20.0]},
                          schema=table.schema().as_arrow()))

    # The commit over REST moved the pointer, so the S3 Tables API draws a new version token.
    after = tables.get_table(tableBucketARN=table_bucket, namespace="db", name="orders")
    assert after["versionToken"] != before
    assert after["metadataLocation"] == table.metadata_location
    assert sorted(catalog.load_table("db.orders").scan().to_arrow()["total"].to_pylist()) == [9.5, 20.0]

    # A commit of the S3 Tables API made with the token from before that commit loses.
    with pytest.raises(ClientError) as error:
        tables.update_table_metadata_location(tableBucketARN=table_bucket, namespace="db", name="orders",
                                              versionToken=before, metadataLocation=table.metadata_location)
    assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 409


def test_a_stale_pyiceberg_commit_is_refused_and_retried(catalog, local_s3, table_bucket):
    catalog.create_namespace("db")
    catalog.create_table("db.racing", schema=SCHEMA)
    first = catalog.load_table("db.racing")
    second = s3tables_catalog(local_s3, table_bucket, name="second").load_table("db.racing")

    first.append(rows(1, 1, "first"))
    # The commit of the stale table is answered 409, and PyIceberg refreshes and commits again on top of the first.
    second.append(rows(2, 2, "second"))

    assert sorted(catalog.load_table("db.racing").scan().to_arrow()["id"].to_pylist()) == [1, 2]
    refused = [request for request in local_s3.requests()
               if request["status"] == 409 and request["method"] == "POST"
               and request["uri"].endswith("/namespaces/db/tables/racing")]
    assert refused, "The stale commit should have been refused before PyIceberg retried it."


def test_schema_evolution(catalog, tables, table_bucket):
    catalog.create_namespace("db")
    table = catalog.create_table("db.evolving", schema=SCHEMA)
    table.append(rows(1, 2, "info"))

    with table.update_schema() as update:
        update.add_column("score", DoubleType())
        update.rename_column("level", "severity")

    table = catalog.load_table("db.evolving")
    assert [field.name for field in table.schema().fields] == ["id", "name", "severity", "score"]
    table.append(pa.table({"id": pa.array([3], pa.int64()), "name": ["row-3"], "severity": ["warn"],
                           "score": [1.5]}, schema=table.schema().as_arrow()))
    assert table.scan().to_arrow().sort_by("id")["score"].to_pylist() == [None, None, 1.5]
    assert tables.get_table(tableBucketARN=table_bucket, namespace="db", name="evolving")["metadataLocation"] \
        == table.metadata_location


def test_rename_and_purge(catalog, tables, table_bucket, local_s3):
    catalog.create_namespace("db")
    catalog.create_namespace("archive")
    table = catalog.create_table("db.draft", schema=SCHEMA)
    table.append(rows(1, 1, "info"))

    catalog.rename_table("db.draft", "archive.final")
    assert catalog.list_tables("db") == []
    assert catalog.list_tables("archive") == [("archive", "final")]
    assert [t["name"] for t in tables.list_tables(tableBucketARN=table_bucket, namespace="archive")["tables"]] \
        == ["final"]
    assert catalog.load_table("archive.final").scan().to_arrow().num_rows == 1

    files = f"{bucket_name(table_bucket)}--table-s3"
    prefix = table.location().removeprefix(f"s3://{files}/") + "/"
    # Amazon S3 Tables drops a table only with its files: the purge of PyIceberg.
    catalog.purge_table("archive.final")
    with pytest.raises(NoSuchTableError):
        catalog.load_table("archive.final")
    with pytest.raises(ClientError) as error:
        tables.get_table(tableBucketARN=table_bucket, namespace="archive", name="final")
    assert error.value.response["ResponseMetadata"]["HTTPStatusCode"] == 404
    assert local_s3.s3().list_objects_v2(Bucket=files, Prefix=prefix).get("KeyCount", 0) == 0


def test_two_table_buckets_are_two_catalogs(local_s3, tables, catalog):
    other = tables.create_table_bucket(name=f"tb-{uuid.uuid4().hex[:12]}")["arn"]
    try:
        catalog.create_namespace("db")
        catalog.create_table("db.same", schema=SCHEMA).append(rows(1, 3, "one"))

        second = s3tables_catalog(local_s3, other, name="other")
        second.create_namespace("db")
        second.create_table("db.same", schema=SCHEMA).append(rows(1, 1, "two"))

        assert catalog.load_table("db.same").scan().to_arrow().num_rows == 3
        assert second.load_table("db.same").scan().to_arrow()["level"].to_pylist() == ["two"]
    finally:
        empty_and_delete(tables, other)
