#!/usr/bin/env bash
# Creates the AppCDS archive of the JVM based image: starts the service with -XX:ArchiveClassesAtExit, sends it the
# common requests, so that the classes they load (Netty, Jackson, the controllers) are archived too, and stops it.
# The JVM writes the archive when it exits. Run by the Dockerfile, with the JDK and the jar path of the image, which an
# archive is only valid for.
#
# Usage: cds-training.sh <jar> <archive>
set -euo pipefail

JAR="$1"
ARCHIVE="$2"
PORT=29199
ENDPOINT="http://127.0.0.1:${PORT}"

# The "Skipping ...: Unsupported location" warnings of the dynamic proxies and lambda forms are expected.
LOCAL_S3_MODE=IN_MEMORY LOCAL_S3_ICEBERG_CATALOG=true LOCAL_S3_HOST=127.0.0.1 LOCAL_S3_PORT="$PORT" \
  java -XX:ArchiveClassesAtExit="$ARCHIVE" -Xlog:cds=error -jar "$JAR" >/tmp/cds-training.log 2>&1 &
PID=$!

for _ in $(seq 1 300); do
  curl -sf -o /dev/null "$ENDPOINT/_health" && break
  kill -0 "$PID" 2>/dev/null || { cat /tmp/cds-training.log; exit 1; }
  sleep 0.1
done

s3() { curl -s -o /dev/null "$@" || true; }

# S3
s3 "$ENDPOINT/"
s3 -X PUT "$ENDPOINT/training"
s3 -I "$ENDPOINT/training"
s3 -X PUT -H 'Content-Type: text/plain' -H 'x-amz-meta-k: v' --data 'hello' "$ENDPOINT/training/a.txt"
s3 -I "$ENDPOINT/training/a.txt"
s3 "$ENDPOINT/training/a.txt"
s3 -H 'Range: bytes=0-1' "$ENDPOINT/training/a.txt"
s3 -X PUT -H 'x-amz-copy-source: /training/a.txt' "$ENDPOINT/training/b.txt"
s3 "$ENDPOINT/training?list-type=2&prefix=a"
s3 "$ENDPOINT/training?delimiter=/"
s3 "$ENDPOINT/training?versions"
s3 "$ENDPOINT/training?location"
s3 -X PUT --data '<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>' "$ENDPOINT/training?versioning"
s3 -X PUT --data '<Tagging><TagSet><Tag><Key>k</Key><Value>v</Value></Tag></TagSet></Tagging>' "$ENDPOINT/training/a.txt?tagging"
s3 "$ENDPOINT/training/a.txt?tagging"
UPLOAD_ID=$(curl -s -X POST "$ENDPOINT/training/big.bin?uploads" | sed -n 's:.*<UploadId>\(.*\)</UploadId>.*:\1:p')
ETAG=$(curl -s -D - -o /dev/null -X PUT --data 'part-1' "$ENDPOINT/training/big.bin?partNumber=1&uploadId=${UPLOAD_ID}" \
  | tr -d '\r' | sed -n 's/^[Ee][Tt]ag: *//p')
s3 "$ENDPOINT/training/big.bin?uploadId=${UPLOAD_ID}"
s3 -X POST --data "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>${ETAG}</ETag></Part></CompleteMultipartUpload>" \
  "$ENDPOINT/training/big.bin?uploadId=${UPLOAD_ID}"
s3 -X POST --data '<Delete><Object><Key>b.txt</Key></Object></Delete>' "$ENDPOINT/training?delete"
s3 -X DELETE "$ENDPOINT/training/a.txt"
s3 "$ENDPOINT/training/missing"

# S3 Vectors
vectors() { s3 -X POST -H 'Content-Type: application/json' --data "$2" "$ENDPOINT/$1"; }
vectors CreateVectorBucket '{"vectorBucketName":"training"}'
vectors CreateIndex '{"vectorBucketName":"training","indexName":"idx","dataType":"float32","dimension":3,"distanceMetric":"cosine"}'
vectors PutVectors '{"vectorBucketName":"training","indexName":"idx","vectors":[{"key":"k","data":{"float32":[1,0,0]},"metadata":{"m":"v"}}]}'
vectors QueryVectors '{"vectorBucketName":"training","indexName":"idx","queryVector":{"float32":[1,0,0]},"topK":1,"returnMetadata":true}'
vectors ListVectorBuckets '{}'

# Iceberg REST catalog and the console
s3 "$ENDPOINT/iceberg/v1/config"
s3 -X POST -H 'Content-Type: application/json' --data '{"namespace":["training"]}' "$ENDPOINT/iceberg/v1/namespaces"
s3 "$ENDPOINT/iceberg/v1/namespaces"
s3 "$ENDPOINT/_admin/ui"
s3 "$ENDPOINT/_admin/stats"

kill -TERM "$PID"
wait "$PID" || true
test -s "$ARCHIVE" || { cat /tmp/cds-training.log; exit 1; }
rm -f /tmp/cds-training.log
