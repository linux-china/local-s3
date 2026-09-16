package com.robothy.s3.core.service.loader;

import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadataRef;
import com.robothy.s3.core.model.internal.ObjectPartMetadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.internal.UploadPartMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.storage.MVStoreBucketMetadataStore;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.util.IdUtils;
import com.robothy.s3.core.util.PathUtils;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;

/**
 * Default implementation of {@linkplain FileSystemS3MetadataLoader}.
 * Load the {@linkplain LocalS3Metadata} instance from a give path.
 */
public class DefaultFileSystemS3MetadataLoader implements FileSystemS3MetadataLoader {

  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(DefaultFileSystemS3MetadataLoader.class);

  @Override
  public LocalS3Metadata load(Path s3DataPath) {
    Objects.requireNonNull(s3DataPath);
    PathUtils.createDirectoryIfNotExist(s3DataPath);
    // Read-only, so that loading the initial data of a path doesn't lock it against the services that start from it.
    try (LocalS3Store store = LocalS3Store.readOnly(s3DataPath)) {
      return load(MVStoreBucketMetadataStore.create(store));
    }
  }

  @Override
  public LocalS3Metadata load(MetadataStore<BucketMetadata> bucketMetaStore) {
    Objects.requireNonNull(bucketMetaStore);
    LocalS3Metadata s3Metadata = new LocalS3Metadata();
    bucketMetaStore.fetchAll().forEach(s3Metadata::addBucketMetadata);
    seedIdGenerator(s3Metadata);
    return s3Metadata;
  }

  /**
   * Keep the generated IDs above the loaded ones, so that a version added to a loaded object is ordered
   * as the most recent one, and stored content doesn't overwrite a loaded file. LocalS3 used to compute
   * the timestamp of an ID against an epoch expressed in seconds, which makes the persisted IDs far
   * larger than the ones generated now.
   *
   * <p>A bucket records the greatest ID it references as it is written, see
   * {@linkplain BucketMetadata#getMaxId()}, so this normally reads one number per bucket rather than the metadata of
   * every object. A bucket written by a LocalS3 that didn't record it is walked once, which reads all of its objects;
   * the number is recorded the next time the bucket is written, so a data directory pays that only until it is
   * changed.
   *
   * @param s3Metadata the loaded metadata.
   */
  private void seedIdGenerator(LocalS3Metadata s3Metadata) {
    long maxId = -1L;
    for (BucketMetadata bucketMetadata : s3Metadata.getBucketMetadataMap().values()) {
      Long recorded = bucketMetadata.getMaxId();
      maxId = Math.max(maxId, recorded == null ? scanMaxId(bucketMetadata) : recorded);
    }

    IdUtils.defaultGenerator().ensureGreaterThan(maxId);
  }

  /**
   * The greatest generated ID that a bucket references, by reading the metadata of every object and upload it holds:
   * for the buckets that were written before the number was recorded.
   */
  private static long scanMaxId(BucketMetadata bucketMetadata) {
    log.debug("Bucket {} records no greatest ID, reading its objects to find it.", bucketMetadata.getBucketName());
    long maxId = -1L;
    for (ObjectMetadataRef ref : bucketMetadata.getObjectMap().values()) {
      // Read rather than kept, so that opening a directory doesn't fill the heap with what it walked.
      ObjectMetadata objectMetadata = ref.read();
      for (Map.Entry<String, VersionedObjectMetadata> version : objectMetadata.getVersionedObjectMap().entrySet()) {
        maxId = Math.max(maxId, toId(version.getKey()));
        maxId = Math.max(maxId, toId(version.getValue().getFileId()));
        // The content of a version completed from a multipart upload is stored in its parts.
        for (ObjectPartMetadata part : version.getValue().getParts().orElse(List.of())) {
          maxId = Math.max(maxId, toId(part.getFileId()));
        }
      }
    }

    for (NavigableMap<String, UploadMetadata> uploadsOfKey : bucketMetadata.getUploads().values()) {
      for (Map.Entry<String, UploadMetadata> upload : uploadsOfKey.entrySet()) {
        maxId = Math.max(maxId, toId(upload.getKey()));
        for (UploadPartMetadata part : upload.getValue().getParts().values()) {
          maxId = Math.max(maxId, toId(part.getFileId()));
        }
      }
    }
    return maxId;
  }

  private static long toId(String id) {
    try {
      return Long.parseLong(id);
    } catch (NumberFormatException e) {
      // Not an ID that the generator produced.
      return -1L;
    }
  }

  private static long toId(Long id) {
    return Objects.isNull(id) ? -1L : id;
  }

}
