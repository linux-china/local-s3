-- The table that sql/spark.sql created: Trino and Spark share the catalog and the files.
SELECT kind, count(*) AS n FROM iceberg.db.events GROUP BY kind ORDER BY kind;

INSERT INTO iceberg.db.events VALUES (4, 'purchase', TIMESTAMP '2026-10-03 18:45:00 UTC');

SELECT snapshot_id, operation FROM iceberg.db."events$snapshots" ORDER BY committed_at;
