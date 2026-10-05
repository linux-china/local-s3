# LocalS3 + Spark + Trino with Docker Compose

A local lakehouse in one `docker compose up`: LocalS3 serves both the object storage and the
[built-in Iceberg REST catalog](../../docs/data-tools.md#the-built-in-iceberg-rest-catalog), and Spark and Trino
write and read the same Iceberg tables through it.

| Service    | Image                                | Reached from the host at                  |
|------------|--------------------------------------|-------------------------------------------|
| `local-s3` | `luofuxiang/local-s3:2.5.0-SNAPSHOT` | `http://localhost:29090` (catalog: `/iceberg`) |
| `spark`    | `apache/spark:3.5.6`                 | Spark UI at `http://localhost:4040` while a job runs |
| `trino`    | `trinodb/trino:476`                  | `http://localhost:8080`                   |

## Start

The Iceberg catalog is new in 2.5.0, which isn't released yet, so the LocalS3 image is built from this repository:

```shell
./gradlew :local-s3-standalone:jar          # from the root of the repository
cd examples/lakehouse
docker compose up -d --build
```

Spark and Trino wait for the health check of LocalS3. Trino takes some 20 seconds more to accept queries.

## Spark

```shell
docker compose exec spark /opt/spark/bin/spark-sql -f /opt/sql/spark.sql
```

[`sql/spark.sql`](sql/spark.sql) creates the namespace `db` and the table `db.events`, appends three rows and lists
the snapshots. The first run downloads the Iceberg jars from Maven Central into the container; the later ones reuse
them. For an interactive shell, leave out `-f ...`.

The whole Spark configuration is [`spark/spark-defaults.conf`](spark/spark-defaults.conf): the catalog URI. The
S3 endpoint, path-style access, the region and the credentials are vended by the catalog.

## Trino

```shell
docker compose exec trino trino -f /opt/sql/trino.sql
```

[`sql/trino.sql`](sql/trino.sql) reads the table that Spark wrote, appends a row, and lists the snapshots of both
engines. For an interactive shell: `docker compose exec trino trino --catalog iceberg --schema db`.

Trino reads and writes data files with its own S3 client, which
[`trino/catalog/iceberg.properties`](trino/catalog/iceberg.properties) configures with the endpoint and the
credentials of LocalS3.

## From the host

The catalog vends the host that a request addressed, so a client on the host that uses `http://localhost:29090/iceberg`
is given `http://localhost:29090` as the S3 endpoint, and one in a container that uses `http://local-s3:29090/iceberg`
is given `http://local-s3:29090`. The same tables are therefore reachable from DuckDB or PyIceberg on the host:

```sql
INSTALL iceberg;
LOAD iceberg;
ATTACH 'warehouse' AS ice (TYPE ICEBERG, ENDPOINT 'http://localhost:29090/iceberg', AUTHORIZATION_TYPE 'none');
SELECT * FROM ice.db.events;
```

The console at `http://localhost:29090/_admin/ui` shows the files under the `warehouse` bucket.

## Data and cleanup

LocalS3 runs in `PERSISTENCE` mode on the volume `local-s3-data`, so the tables survive `docker compose down`.
To start from nothing:

```shell
docker compose down -v
```

## Security

The ports are published on `127.0.0.1` alone. The catalog answers anonymous requests and vends the credentials of the
service (`admin` / `admin`) to whoever loads a table, and LocalS3 logs a warning that says so; see
[Docker](../../docs/deployment.md#docker). Don't publish these ports on a shared network.
