#!/usr/bin/env bash
# Creates the AOT cache of the JVM based image: starts the service with -XX:AOTCacheOutput, sends it the common
# requests, so that the classes they load (Netty, Jackson, the controllers) are loaded and linked ahead of time and
# their methods profiled too, and stops it. The JVM writes the cache when it exits. Run by the Dockerfile, with the JDK,
# the jar path and the JVM options of the image, which a cache is only valid for: the Dockerfile passes the options that
# the image always runs with, e.g. -XX:+UseCompactObjectHeaders, which a cache must be created with to be used.
#
# The service is trained the way the image runs it and clients reach it: PERSISTENCE mode, the default of the image,
# so that the classes of the store and the file system storage are archived, and with credentials, since the clients
# (the AWS SDKs, DuckDB, s5cmd, the S3FileIO of Iceberg) sign their requests, e.g. with the AWS_ACCESS_KEY_ID that the
# image reads as the credentials of the service. The requests are signed with SigV4 by curl, and cover a body larger
# than the 4 MiB held in memory, the aws-chunked bodies, signed and unsigned, and a presigned URL.
#
# Usage: aot-training.sh <jar> <cache> [<JVM option>...]
set -euo pipefail

JAR="$1"
ARCHIVE="$2"
shift 2
PORT=29199
HOST="127.0.0.1:${PORT}"
ENDPOINT="http://${HOST}"
REGION="us-east-1"
ACCESS_KEY="aot"
SECRET_KEY="aot"
WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# The "Skipping ..." warnings of the classes that can't be cached, e.g. dynamic proxies and JFR events, are expected.
env -u AWS_ACCESS_KEY_ID -u AWS_SECRET_ACCESS_KEY \
  LOCAL_S3_MODE=PERSISTENCE LOCAL_S3_DATA_PATH="$WORK_DIR/data" \
  LOCAL_S3_ACCESS_KEY_ID="$ACCESS_KEY" LOCAL_S3_SECRET_ACCESS_KEY="$SECRET_KEY" \
  LOCAL_S3_ICEBERG_CATALOG=true LOCAL_S3_HOST=127.0.0.1 LOCAL_S3_PORT="$PORT" \
  java "$@" -XX:AOTCacheOutput="$ARCHIVE" -Xlog:aot=error -jar "$JAR" >"$WORK_DIR/service.log" 2>&1 &
PID=$!

for _ in $(seq 1 300); do
  curl -sf -o /dev/null "$ENDPOINT/_health" && break
  kill -0 "$PID" 2>/dev/null || { cat "$WORK_DIR/service.log"; exit 1; }
  sleep 0.1
done

# A request signed with SigV4 by curl, for S3 by default; a failed request doesn't fail the training.
signed() { curl -s --aws-sigv4 "aws:amz:${REGION}:${SIGNING_NAME:-s3}" --user "${ACCESS_KEY}:${SECRET_KEY}" "$@"; }
s3() { signed -o /dev/null "$@" || true; }

# S3
s3 "$ENDPOINT/"
s3 -X PUT "$ENDPOINT/training"
s3 -I "$ENDPOINT/training"
s3 -X PUT -H 'Content-Type: text/plain' -H 'x-amz-meta-k: v' --data 'hello' "$ENDPOINT/training/a.txt"
s3 -I "$ENDPOINT/training/a.txt"
s3 "$ENDPOINT/training/a.txt"
s3 -H 'Range: bytes=0-1' "$ENDPOINT/training/a.txt"
s3 -H 'Range: bytes=-2' "$ENDPOINT/training/a.txt"
s3 -X PUT -H 'If-None-Match: *' --data 'hello' "$ENDPOINT/training/a.txt"
s3 -X PUT -H 'x-amz-copy-source: /training/a.txt' "$ENDPOINT/training/b.txt"
s3 "$ENDPOINT/training?list-type=2&prefix=a"
s3 "$ENDPOINT/training?list-type=2&delimiter=/&encoding-type=url"
s3 "$ENDPOINT/training?delimiter=/"
s3 "$ENDPOINT/training?versions"
s3 "$ENDPOINT/training?location"
s3 -X PUT --data '<VersioningConfiguration><Status>Enabled</Status></VersioningConfiguration>' "$ENDPOINT/training?versioning"
s3 -X PUT --data '<Tagging><TagSet><Tag><Key>k</Key><Value>v</Value></Tag></TagSet></Tagging>' "$ENDPOINT/training/a.txt?tagging"
s3 "$ENDPOINT/training/a.txt?tagging"
# An unsigned request, which a service with credentials refuses.
curl -s -o /dev/null "$ENDPOINT/training/a.txt" || true

# A body larger than the 4 MiB that a request holds in memory, which goes to a file of its own.
head -c 6000000 /dev/zero >"$WORK_DIR/big.bin"
s3 -T "$WORK_DIR/big.bin" "$ENDPOINT/training/big-put.bin"
s3 -H 'Range: bytes=-1024' "$ENDPOINT/training/big-put.bin"

# aws-chunked with unsigned chunks and a checksum trailer, which the AWS SDKs send over HTTPS; curl signs the headers
# with the x-amz-content-sha256 given.
printf '5\r\nhello\r\n0\r\nx-amz-checksum-crc32:NhCmhg==\r\n\r\n' >"$WORK_DIR/unsigned-chunks"
s3 -X PUT --data-binary "@$WORK_DIR/unsigned-chunks" -H 'x-amz-content-sha256: STREAMING-UNSIGNED-PAYLOAD-TRAILER' \
  -H 'Content-Encoding: aws-chunked' -H 'x-amz-decoded-content-length: 5' -H 'x-amz-trailer: x-amz-checksum-crc32' \
  "$ENDPOINT/training/unsigned-chunks.txt"

# aws-chunked with signed chunks, which the AWS SDKs send over plain HTTP. curl doesn't sign chunks, so the request is
# signed here, with openssl.
sha256_hex() { openssl dgst -sha256 -hex | sed 's/^.* //'; }
hmac_hex() { printf '%s' "$2" | openssl dgst -sha256 -hex -mac HMAC -macopt "hexkey:$1" | sed 's/^.* //'; }
put_signed_chunks() {
  local path="/training/signed-chunks.txt" data="hello" date day scope key headers signed_headers canonical signature
  date="$(date -u +%Y%m%dT%H%M%SZ)"
  day="${date:0:8}"
  scope="${day}/${REGION}/s3/aws4_request"
  key="$(printf '%s' "AWS4${SECRET_KEY}" | od -An -tx1 | tr -d ' \n')"
  for part in "$day" "$REGION" s3 aws4_request; do key="$(hmac_hex "$key" "$part")"; done
  headers="content-encoding:aws-chunked
host:${HOST}
x-amz-content-sha256:STREAMING-AWS4-HMAC-SHA256-PAYLOAD
x-amz-date:${date}
x-amz-decoded-content-length:${#data}"
  signed_headers="content-encoding;host;x-amz-content-sha256;x-amz-date;x-amz-decoded-content-length"
  canonical="$(printf 'PUT\n%s\n\n%s\n\n%s\nSTREAMING-AWS4-HMAC-SHA256-PAYLOAD' "$path" "$headers" "$signed_headers")"
  signature="$(hmac_hex "$key" "$(printf 'AWS4-HMAC-SHA256\n%s\n%s\n%s' "$date" "$scope" \
    "$(printf '%s' "$canonical" | sha256_hex)")")"
  local empty_hash previous="$signature"
  empty_hash="$(printf '' | sha256_hex)"
  : >"$WORK_DIR/signed-chunks"
  # The chunk of the data, then the empty one that ends the body; each signs the signature before it.
  for chunk in "$data" ""; do
    previous="$(hmac_hex "$key" "$(printf 'AWS4-HMAC-SHA256-PAYLOAD\n%s\n%s\n%s\n%s\n%s' "$date" "$scope" "$previous" \
      "$empty_hash" "$(printf '%s' "$chunk" | sha256_hex)")")"
    printf '%x;chunk-signature=%s\r\n%s\r\n' "${#chunk}" "$previous" "$chunk" >>"$WORK_DIR/signed-chunks"
  done
  curl -s -o /dev/null -X PUT --data-binary "@$WORK_DIR/signed-chunks" \
    -H 'Content-Encoding: aws-chunked' -H 'x-amz-content-sha256: STREAMING-AWS4-HMAC-SHA256-PAYLOAD' \
    -H "x-amz-date: ${date}" -H "x-amz-decoded-content-length: ${#data}" \
    -H "Authorization: AWS4-HMAC-SHA256 Credential=${ACCESS_KEY}/${scope}, SignedHeaders=${signed_headers}, Signature=${signature}" \
    "$ENDPOINT$path" || true
}
put_signed_chunks

UPLOAD_ID=$(signed -X POST "$ENDPOINT/training/big.bin?uploads" | sed -n 's:.*<UploadId>\(.*\)</UploadId>.*:\1:p' || true)
ETAG=$(signed -D - -o /dev/null -X PUT --data 'part-1' "$ENDPOINT/training/big.bin?partNumber=1&uploadId=${UPLOAD_ID}" \
  | tr -d '\r' | sed -n 's/^[Ee][Tt]ag: *//p' || true)
s3 "$ENDPOINT/training/big.bin?uploadId=${UPLOAD_ID}"
s3 -X POST --data "<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>${ETAG}</ETag></Part></CompleteMultipartUpload>" \
  "$ENDPOINT/training/big.bin?uploadId=${UPLOAD_ID}"
s3 -I "$ENDPOINT/training/big.bin?partNumber=1"

# A presigned URL, which the console signs with the credentials of the service; the console takes them as the user
# name and the password of basic authentication.
console() { curl -s --user "${ACCESS_KEY}:${SECRET_KEY}" "$@"; }
PRESIGNED_URL=$(console "$ENDPOINT/_admin/ui/presign?bucket=training&key=a.txt" | sed -n 's:.*"url" *\: *"\([^"]*\)".*:\1:p' || true)
[ -n "$PRESIGNED_URL" ] && curl -s -o /dev/null "$PRESIGNED_URL" || true

s3 -X POST --data '<Delete><Object><Key>b.txt</Key></Object></Delete>' "$ENDPOINT/training?delete"
s3 -X DELETE "$ENDPOINT/training/a.txt"
s3 "$ENDPOINT/training/missing"

# S3 Vectors
vectors() { SIGNING_NAME=s3vectors s3 -X POST -H 'Content-Type: application/json' --data "$2" "$ENDPOINT/$1"; }
vectors CreateVectorBucket '{"vectorBucketName":"training"}'
vectors CreateIndex '{"vectorBucketName":"training","indexName":"idx","dataType":"float32","dimension":3,"distanceMetric":"cosine"}'
vectors PutVectors '{"vectorBucketName":"training","indexName":"idx","vectors":[{"key":"k","data":{"float32":[1,0,0]},"metadata":{"m":"v"}}]}'
vectors QueryVectors '{"vectorBucketName":"training","indexName":"idx","queryVector":{"float32":[1,0,0]},"topK":1,"returnMetadata":true}'
vectors ListVectorBuckets '{}'

# Iceberg REST catalog and the console
s3 "$ENDPOINT/iceberg/v1/config"
s3 -X POST -H 'Content-Type: application/json' --data '{"namespace":["training"]}' "$ENDPOINT/iceberg/v1/namespaces"
s3 "$ENDPOINT/iceberg/v1/namespaces"
console -o /dev/null "$ENDPOINT/_admin/ui" || true
s3 "$ENDPOINT/_admin/stats"

kill -TERM "$PID"
wait "$PID" || true
test -s "$ARCHIVE" || { cat "$WORK_DIR/service.log"; exit 1; }
