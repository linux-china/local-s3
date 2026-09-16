package com.robothy.s3.core.service.manager.vectors;

import com.robothy.s3.core.model.internal.s3vectors.LocalS3VectorsMetadata;
import com.robothy.s3.core.service.BucketGuard;
import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import com.robothy.s3.core.service.loader.vectors.S3VectorsMetadataLoader;
import com.robothy.s3.core.storage.s3vectors.VectorStorage;
import java.nio.file.Path;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/**
 * In-memory implementation of {@linkplain LocalS3VectorsManager}, optionally starting from the vectors of a data path.
 *
 * <p>With initial data, the metadata of the vector buckets of the path is read from the store of the path, without
 * changing it, and the vectors are read from its files through a read-only storage, under an in-memory storage that the
 * service stores its new vectors in; deleting a vector of the path only hides it. So the initial data stays as it is,
 * whatever the service does, like the objects of the data path of an {@code IN_MEMORY} S3 service.
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
   * @param initialDataDirectory the data path of the initial data; {@code null}, or a path that holds no store, to
   *     start without vectors.
   */
  InMemoryLocalS3VectorsManager(Path initialDataDirectory) {
    this.initialDataDirectory = initialDataDirectory;
    this.data = initialData();
    this.s3VectorsService = S3VectorsService.create(() -> data.metadata(), () -> data.storage(), bucketGuard);
  }

  private Data initialData() {
    if (Objects.isNull(initialDataDirectory)) {
      return new Data(new LocalS3VectorsMetadata(), VectorStorage.createInMemory());
    }
    // Reads the store of the path read-only, which answers an empty one for a path that holds none.
    LocalS3VectorsMetadata vectorsMetadata = new S3VectorsMetadataLoader().load(initialDataDirectory);
    VectorStorageIds.seedGenerator(vectorsMetadata);
    VectorStorage storage = VectorStorage.createLayered(VectorStorage.createInMemory(),
        VectorStorage.createReadOnlyFileSystem(
            LocalS3VectorsManager.vectorStorageDirectory(initialDataDirectory)));
    log.info("Loaded {} vector buckets from {}.", vectorsMetadata.getVectorBucketMetadataMap().size(),
        initialDataDirectory);
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
