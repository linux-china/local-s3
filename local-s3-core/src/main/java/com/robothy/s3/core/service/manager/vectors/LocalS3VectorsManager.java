package com.robothy.s3.core.service.manager.vectors;

import com.robothy.s3.core.service.s3vectors.S3VectorsService;
import java.nio.file.Path;

public interface LocalS3VectorsManager {

  /**
   * The directory of the vector data files, relative to the vectors data path.
   */
  String VECTOR_STORAGE_DIRECTORY = ".storage";

  static LocalS3VectorsManager createInMemory() {
    return new InMemoryLocalS3VectorsManager(null);
  }

  /**
   * Create an in-memory manager that starts from the vectors of a vectors data path, like an {@code IN_MEMORY} service
   * starts from the objects of its data path. The vector buckets, indexes and vectors of the path are loaded, and the
   * changes of the service are kept in memory: the path is never changed.
   *
   * @param initialDataDirectory the vectors data path of the initial data; {@code null}, or a path that doesn't exist,
   *     to start without vectors.
   * @return an in-memory manager.
   */
  static LocalS3VectorsManager createInMemory(Path initialDataDirectory) {
    return new InMemoryLocalS3VectorsManager(initialDataDirectory);
  }

  static LocalS3VectorsManager createFileSystem(Path s3VectorsDataDirectory) {
    return new FileSystemLocalS3VectorsManager(s3VectorsDataDirectory);
  }

  S3VectorsService s3VectorsService();

}
