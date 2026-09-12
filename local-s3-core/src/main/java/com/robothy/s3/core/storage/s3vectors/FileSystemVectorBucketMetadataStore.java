package com.robothy.s3.core.storage.s3vectors;

import com.robothy.s3.core.exception.InvalidBucketNameException;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.storage.MetadataStore;
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

/**
 * File system implementation of {@linkplain MetadataStore} for {@linkplain VectorBucketMetadata}.
 * Follows the same pattern as {@link com.robothy.s3.core.storage.FileSystemBucketMetadataStore}
 * for consistency with existing LocalS3 storage architecture.
 */
@Slf4j
public class FileSystemVectorBucketMetadataStore implements MetadataStore<VectorBucketMetadata> {

  private static final String VECTOR_BUCKET_METADATA_FILE_SUFFIX = ".vectorbucket.meta";

  /**
   * Create a {@linkplain FileSystemVectorBucketMetadataStore} instance.
   * 
   * @param dataPath the path where metadata files will be stored
   * @return a new metadata store instance
   * @throws IllegalStateException if the directory cannot be created
   */
  public static MetadataStore<VectorBucketMetadata> create(Path dataPath) {
    Objects.requireNonNull(dataPath);
    File file = dataPath.toFile();
    if (!file.exists() || !file.isDirectory()) {
      if (!file.mkdirs()) {
        throw new IllegalStateException("Failed to create directory " + dataPath.toAbsolutePath());
      }
    }
    JsonUtils.deleteTempFiles(dataPath);
    return new FileSystemVectorBucketMetadataStore(dataPath);
  }

  private final Path dataPath;

  private FileSystemVectorBucketMetadataStore(Path path) {
    this.dataPath = path;
  }

  /**
   * Resolve the metadata file of a vector bucket, which is always a file of {@linkplain #dataPath}.
   *
   * @param vectorBucketName the vector bucket name.
   * @return the metadata file of the vector bucket.
   * @throws InvalidBucketNameException if the name resolves to a file outside {@linkplain #dataPath}.
   */
  private File metadataFile(String vectorBucketName) {
    try {
      return PathUtils.resolveChild(dataPath, vectorBucketName + VECTOR_BUCKET_METADATA_FILE_SUFFIX).toFile();
    } catch (IllegalArgumentException e) {
      // A vector bucket name that traverses out of the data path is invalid, like a blank one.
      throw new InvalidBucketNameException(vectorBucketName);
    }
  }

  @SneakyThrows
  @Override
  public VectorBucketMetadata fetch(String vectorBucketName) {
    log.debug("Fetching metadata of vector bucket {}.", vectorBucketName);
    return JsonUtils.fromJson(metadataFile(vectorBucketName), VectorBucketMetadata.class);
  }

  @Override
  public boolean exists(String vectorBucketName) {
    return metadataFile(vectorBucketName).isFile();
  }

  @Override
  public String store(String vectorBucketName, VectorBucketMetadata vectorBucketMetadata) {
    if (StringUtils.isBlank(vectorBucketMetadata.getVectorBucketName())) {
      throw new IllegalArgumentException("Invalid vector bucket name '" + vectorBucketMetadata.getVectorBucketName() + "'.");
    }

    JsonUtils.toJson(metadataFile(vectorBucketMetadata.getVectorBucketName()), vectorBucketMetadata);
    return vectorBucketMetadata.getVectorBucketName();
  }

  @Override
  public void delete(String vectorBucketName) {
    if (!metadataFile(vectorBucketName).delete()) {
      throw new IllegalStateException("Failed to delete metadata of vector bucket " + vectorBucketName);
    }
  }

  @Override
  @SneakyThrows
  public List<VectorBucketMetadata> fetchAll() {
    try (Stream<Path> pathStream = Files.walk(dataPath, 1)) {
      return pathStream
          .filter(path -> path.toString().endsWith(VECTOR_BUCKET_METADATA_FILE_SUFFIX))
          .map(path -> path.getFileName().toString())
          .map(fileName -> fileName.substring(0, fileName.lastIndexOf(VECTOR_BUCKET_METADATA_FILE_SUFFIX)))
          .map(this::fetch)
          .collect(Collectors.toList());
    }
  }
}
