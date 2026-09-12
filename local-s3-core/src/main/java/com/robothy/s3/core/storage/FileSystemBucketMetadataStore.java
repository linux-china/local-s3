package com.robothy.s3.core.storage;

import com.robothy.s3.core.exception.InvalidBucketNameException;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.util.JsonUtils;
import com.robothy.s3.core.util.PathUtils;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

@Slf4j
public class FileSystemBucketMetadataStore implements MetadataStore<BucketMetadata> {

  private static final String BUCKET_METADATA_FILE_SUFFIX = ".bucket.meta";

  public static MetadataStore<BucketMetadata> create(Path dataPath) {
    Objects.requireNonNull(dataPath);
    File file = dataPath.toFile();
    if (!file.exists() || !file.isDirectory()) {
      if (!file.mkdirs()) {
        throw new IllegalStateException("Failed to create directory " + dataPath.toAbsolutePath());
      }
    }
    JsonUtils.deleteTempFiles(dataPath);
    return new FileSystemBucketMetadataStore(dataPath);
  }

  private final Path dataPath;

  private FileSystemBucketMetadataStore(Path path) {
    this.dataPath = path;
  }

  /**
   * Resolve the metadata file of a bucket, which is always a file of {@linkplain #dataPath}.
   *
   * @param bucketName the bucket name.
   * @return the metadata file of the bucket.
   * @throws InvalidBucketNameException if the bucket name resolves to a file outside {@linkplain #dataPath}.
   */
  private File metadataFile(String bucketName) {
    try {
      return PathUtils.resolveChild(dataPath, bucketName + BUCKET_METADATA_FILE_SUFFIX).toFile();
    } catch (IllegalArgumentException e) {
      // A bucket name that traverses out of the data path is invalid, like a blank one.
      throw new InvalidBucketNameException(bucketName);
    }
  }

  @SneakyThrows
  @Override
  public BucketMetadata fetch(String bucketName) {
    log.debug("Fetching metadata of bucket {}.", bucketName);
    return JsonUtils.fromJson(metadataFile(bucketName), BucketMetadata.class);
  }

  @Override
  public boolean exists(String bucketName) {
    return metadataFile(bucketName).isFile();
  }

  @Override
  public String store(String bucketName, BucketMetadata bucketMetadata) {
    if (StringUtils.isBlank(bucketMetadata.getBucketName())) {
      throw new IllegalArgumentException("Invalid bucket name '" + bucketMetadata.getBucketName() + "'.");
    }

    JsonUtils.toJson(metadataFile(bucketMetadata.getBucketName()), bucketMetadata);
    return bucketMetadata.getBucketName();
  }

  @Override
  public void delete(String bucketName) {
    if (!metadataFile(bucketName).delete()) {
      throw new IllegalStateException("Failed to delete metadata of bucket " + bucketName);
    }
  }

  @Override
  @SneakyThrows
  public List<BucketMetadata> fetchAll() {
    try (Stream<Path> pathStream = Files.walk(dataPath, 1)) {
      return pathStream
          .filter(path -> path.toString().endsWith(BUCKET_METADATA_FILE_SUFFIX))
          .map(path -> path.getFileName().toString())
          .map(fileName -> fileName.substring(0, fileName.lastIndexOf(BUCKET_METADATA_FILE_SUFFIX)))
          .map(this::fetch)
          .collect(Collectors.toList());
    }
  }
}
