package com.robothy.s3.core.service.loader;

import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.LocalS3Metadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.internal.UploadPartMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.storage.FileSystemBucketMetadataStore;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.util.IdUtils;
import com.robothy.s3.core.util.PathUtils;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;

/**
 * Default implementation of {@linkplain FileSystemS3MetadataLoader}.
 * Load the {@linkplain LocalS3Metadata} instance from a give path.
 */
public class DefaultFileSystemS3MetadataLoader implements FileSystemS3MetadataLoader {

  private static final String VERSION_FILE_NAME = "version";

  @Override
  public LocalS3Metadata load(Path s3DataPath) {
    Objects.requireNonNull(s3DataPath);
    PathUtils.createDirectoryIfNotExit(s3DataPath);
    File versionFile = new File(s3DataPath.toFile(), VERSION_FILE_NAME);
    LocalS3Metadata s3Metadata = new LocalS3Metadata();
    if (!versionFile.exists()) {
      try {
        Files.write(versionFile.toPath(), String.valueOf(LocalS3Metadata.VERSION).getBytes(StandardCharsets.UTF_8));
      } catch (IOException e) {
        throw new IllegalStateException("Failed to load S3 metadata from " + s3DataPath);
      }
    }

    MetadataStore<BucketMetadata> bucketMetaStore = FileSystemBucketMetadataStore.create(s3DataPath);
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
   * @param s3Metadata the loaded metadata.
   */
  private void seedIdGenerator(LocalS3Metadata s3Metadata) {
    long maxId = -1L;
    for (BucketMetadata bucketMetadata : s3Metadata.getBucketMetadataMap().values()) {
      for (ObjectMetadata objectMetadata : bucketMetadata.getObjectMap().values()) {
        for (Map.Entry<String, VersionedObjectMetadata> version : objectMetadata.getVersionedObjectMap().entrySet()) {
          maxId = Math.max(maxId, toId(version.getKey()));
          maxId = Math.max(maxId, toId(version.getValue().getFileId()));
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
    }

    IdUtils.defaultGenerator().ensureGreaterThan(maxId);
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
