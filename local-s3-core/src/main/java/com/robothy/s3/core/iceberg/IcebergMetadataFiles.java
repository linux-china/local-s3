package com.robothy.s3.core.iceberg;

import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.answers.ListObjectsV2Ans;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.datatypes.response.S3Object;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads and writes the metadata files of the Iceberg catalog, which are objects of the LocalS3 service itself.
 *
 * <p>An Iceberg catalog splits a table in two: the catalog keeps a pointer, and the table itself is a
 * {@code metadata.json} in the object store that the pointer names. LocalS3 is that object store, so the files are
 * written through {@linkplain ObjectService} directly rather than over HTTP to itself: no socket, no signature, no
 * second copy of the bytes, and the write lands in the same bucket that the engine then reads it from with its own
 * {@code S3FileIO}.
 *
 * <p>The locations are {@code s3://bucket/key}. A location that names a scheme LocalS3 doesn't serve, e.g.
 * {@code file:} or {@code gs:}, is refused where it is given rather than written somewhere unexpected.
 */
public final class IcebergMetadataFiles {

  private static final Logger log = LoggerFactory.getLogger(IcebergMetadataFiles.class);

  /**
   * The schemes of a location that LocalS3 serves. {@code s3a} and {@code s3n} are the Hadoop spellings of the same
   * thing, which an engine configured through Hadoop may hand back.
   */
  private static final String[] SCHEMES = {"s3://", "s3a://", "s3n://"};

  /**
   * The content type of a metadata file, so that a client that reads one over HTTP gets a sensible one.
   */
  private static final String CONTENT_TYPE = "application/json";

  /**
   * The number of objects that one page of a purge deletes.
   */
  private static final int PURGE_PAGE_SIZE = 1000;

  private final BucketService bucketService;

  private final ObjectService objectService;

  /**
   * Create the file access of a catalog.
   *
   * @param bucketService the buckets of the service, which the warehouse bucket is created through.
   * @param objectService the objects of the service, which the metadata files are.
   */
  public IcebergMetadataFiles(BucketService bucketService, ObjectService objectService) {
    this.bucketService = Objects.requireNonNull(bucketService, "bucketService");
    this.objectService = Objects.requireNonNull(objectService, "objectService");
  }

  /**
   * Write a metadata file.
   *
   * @param location the {@code s3://} location of the file.
   * @param content the JSON of the file.
   * @throws IcebergCatalogException if the location isn't one that LocalS3 serves, or its bucket doesn't exist.
   */
  public void write(String location, String content) {
    Location parsed = parse(location);
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    try {
      objectService.putObject(parsed.bucket(), parsed.key(), PutObjectOptions.builder()
          .contentType(CONTENT_TYPE)
          .size(bytes.length)
          .content(new ByteArrayInputStream(bytes))
          .build());
    } catch (BucketNotExistException e) {
      throw IcebergCatalogException.badRequest("The bucket " + parsed.bucket() + " of " + location
          + " does not exist. Create it, or start LocalS3 with it among its buckets.");
    }
  }

  /**
   * Read a metadata file.
   *
   * @param location the {@code s3://} location of the file.
   * @return the JSON of the file.
   * @throws IcebergCatalogException if the file doesn't exist, or can't be read.
   */
  public String read(String location) {
    Location parsed = parse(location);
    try {
      GetObjectAns object = objectService.getObject(parsed.bucket(), parsed.key(), GetObjectOptions.builder().build());
      try (InputStream content = object.getContent()) {
        return new String(content.readAllBytes(), StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException("Failed to read " + location + ".", e);
      }
    } catch (LocalS3Exception e) {
      throw new IcebergCatalogException(404, "NoSuchTableException",
          "Failed to read the table metadata " + location + ": " + e.getMessage());
    }
  }

  /**
   * Create the warehouse bucket if it doesn't exist, so that a service configured with an Iceberg catalog can be
   * written to without the test first having to create the bucket by hand.
   *
   * @param location the {@code s3://} location of the warehouse.
   * @return the name of the bucket.
   */
  public String createBucketIfAbsent(String location) {
    String bucket = parse(location).bucket();
    try {
      bucketService.getBucket(bucket);
    } catch (BucketNotExistException e) {
      bucketService.createBucket(bucket);
      log.info("Created the warehouse bucket '{}' of the Iceberg catalog.", bucket);
    }
    return bucket;
  }

  /**
   * Delete everything under a location, which is what purging a dropped table does: its metadata files, its manifests
   * and its data files all live under the location of the table.
   *
   * <p>A failure to delete is logged rather than raised: the table is already gone from the catalog, and a drop that
   * reported a failure after that would leave the caller with nothing to do about it.
   *
   * @param location the {@code s3://} location of the table.
   */
  public void purge(String location) {
    Location parsed;
    try {
      parsed = parse(location);
    } catch (IcebergCatalogException e) {
      log.warn("Skipped purging {}: {}", location, e.getMessage());
      return;
    }
    String prefix = parsed.key().isEmpty() ? "" : IcebergJson.stripTrailingSlash(parsed.key()) + "/";
    try {
      String continuationToken = null;
      do {
        ListObjectsV2Ans page = objectService.listObjectsV2(parsed.bucket(), continuationToken, null, null,
            false, PURGE_PAGE_SIZE, prefix, null);
        for (S3Object object : page.getObjects()) {
          objectService.deleteObject(parsed.bucket(), object.getKey());
        }
        continuationToken = page.isTruncated() ? page.getNextContinuationToken().orElse(null) : null;
      } while (continuationToken != null);
    } catch (RuntimeException e) {
      log.warn("Failed to purge {} of the dropped Iceberg table: {}", location, e.toString());
    }
  }

  /**
   * The bucket and the key of an {@code s3://} location.
   *
   * @param location the location.
   * @return the bucket and the key.
   * @throws IcebergCatalogException if the location isn't one that LocalS3 serves.
   */
  public static Location parse(String location) {
    Objects.requireNonNull(location, "location");
    for (String scheme : SCHEMES) {
      if (location.startsWith(scheme)) {
        String rest = location.substring(scheme.length());
        int slash = rest.indexOf('/');
        if (slash < 0) {
          return new Location(rest, "");
        }
        String bucket = rest.substring(0, slash);
        if (bucket.isEmpty()) {
          break;
        }
        return new Location(bucket, rest.substring(slash + 1));
      }
    }
    throw IcebergCatalogException.badRequest("The Iceberg catalog of LocalS3 stores tables in LocalS3 itself, so a"
        + " location must be an s3:// URI of one of its buckets; got: " + location);
  }

  /**
   * A location of the object store.
   *
   * @param bucket the bucket name.
   * @param key the object key, which is empty for the bucket itself.
   */
  public record Location(String bucket, String key) {
  }

}
