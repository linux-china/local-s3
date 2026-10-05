CREATE NAMESPACE IF NOT EXISTS db;

CREATE TABLE IF NOT EXISTS db.events (
    id   BIGINT,
    kind STRING,
    ts   TIMESTAMP
) USING iceberg
PARTITIONED BY (days(ts));

INSERT INTO db.events VALUES
    (1, 'click', TIMESTAMP '2026-10-01 10:00:00'),
    (2, 'view',  TIMESTAMP '2026-10-01 11:30:00'),
    (3, 'click', TIMESTAMP '2026-10-02 09:15:00');

SELECT kind, count(*) AS n FROM db.events GROUP BY kind ORDER BY kind;

SELECT snapshot_id, operation, summary['added-records'] AS added FROM db.events.snapshots;
