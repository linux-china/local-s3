package com.robothy.s3.core.service.manager.vectors;

import com.robothy.s3.core.model.internal.s3vectors.LocalS3VectorsMetadata;
import com.robothy.s3.core.service.BucketGuard;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.core.storage.s3vectors.FileSystemVectorBucketMetadataStore;
import com.robothy.s3.core.storage.s3vectors.VectorStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory implementation of {@linkplain LocalS3VectorsManager}, optionally starting from the vectors of a data path.
 *
 * <p>With initial data, the metadata of the vector buckets of the path is loaded without changing the path, and the
 * vectors are read from its files through a read-only storage, under an in-memory storage that the service stores its
 * new vectors in; deleting a vector of the path only hides it. So the initial data stays as it is, whatever the service
 * does, like the objects of the data path of an {@code IN_MEMORY} S3 service.
 *
 * <p>The service is created once, so that a service that is started again serves the vectors it held.
 */
@Slf4j
final class InMemoryLocalS3VectorsManager implements LocalS3VectorsManager {

  private final Path initialDataDirectory;

  private final BucketGuard bucketGuard = BucketGuard.inMemory();

  /**
   * The vectors of the service, replaced as a whole by {@linkplain #reset()} within an exclusive operation of the
   * {@linkplain #bucketGuard}.
   */
  private volatile Data data;

  private final S3VectorsService s3VectorsService;

  private record Data(LocalS3VectorsMetadata metadata, VectorStorage storage) {
  }

  /**
   * Create a manager.
   *
   * @param initialDataDirectory the vectors data path of the initial data; {@code null}, or a path that doesn't exist,
   *     to start without vectors.
   */
  InMemoryLocalS3VectorsManager(Path initialDataDirectory) {
    this.initialDataDirectory = initialDataDirectory;
    this.data = initialData();
    this.s3VectorsService = S3VectorsService.create(() -> data.metadata(), () -> data.storage(), bucketGuard);
  }

  private Data initialData() {
    LocalS3VectorsMetadata vectorsMetadata = new LocalS3VectorsMetadata();
    VectorStorage storage = VectorStorage.createInMemory();
    if (Objects.nonNull(initialDataDirectory) && Files.isDirectory(initialDataDirectory)) {
      FileSystemVectorBucketMetadataStore.readAll(initialDataDirectory)
          .forEach(vectorsMetadata::addVectorBucketMetadata);
      VectorStorageIds.seedGenerator(vectorsMetadata);
      storage = VectorStorage.createLayered(storage, VectorStorage.createReadOnlyFileSystem(
          initialDataDirectory.resolve(VECTOR_STORAGE_DIRECTORY)));
      log.info("Loaded {} vector buckets from {}.", vectorsMetadata.getVectorBucketMetadataMap().size(),
          initialDataDirectory);
    }
    return new Data(vectorsMetadata, storage);
  }

  @Override
  public S3VectorsService s3VectorsService() {
    return s3VectorsService;
  }

  /**
   * Replace the vectors with the ones that the service started with, loading the initial data again.
   */
  @Override
  public void reset() {
    bucketGuard.exclusive(() -> {
      data = initialData();
      return null;
    });
  }

}
