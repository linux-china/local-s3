package com.robothy.s3.core.service;

import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.s3.core.util.S3ObjectUtils.MeasuredInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Represent services that perform operations on a {@linkplain Storage}.
 */
public interface StorageApplicable {

  Storage storage();

  /**
   * Store the content of a request, measuring its length and its MD5 digest.
   *
   * <p>Without a file, the content is stored as it is read. With a file that holds the content, e.g. the file that a
   * large request body was buffered in, the content is only read to measure it, and the storage takes the file
   * over, which a storage on the same file system does by renaming it: the content isn't written a second time.
   *
   * @param content the content; may be {@code null} if {@code contentFile} is given.
   * @param contentFile a file that holds exactly the content; {@code null} if there is none.
   * @return the stored content.
   */
  default StoredContent storeContent(InputStream content, Path contentFile) {
    if (Objects.isNull(contentFile)) {
      MeasuredInputStream measured = S3ObjectUtils.measuringStream(content);
      Long fileId = storage().put(measured);
      return new StoredContent(fileId, measured.getSize(), measured.etag());
    }

    MeasuredInputStream measured;
    try {
      measured = S3ObjectUtils.measuringStream(Objects.nonNull(content) ? content : Files.newInputStream(contentFile));
      try (InputStream in = measured) {
        in.transferTo(OutputStream.nullOutputStream());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the content in " + contentFile + ".", e);
    }
    Long fileId = storage().put(contentFile);
    return new StoredContent(fileId, measured.getSize(), measured.etag());
  }

  /**
   * Delete content that was stored for a request that failed afterwards. Within a storage transaction, the
   * transaction deletes it when it is rolled back.
   *
   * @param fileId the ID of the stored content.
   * @param cause the failure of the request, to which a failure to delete the content is added.
   */
  default void discardStoredContent(Long fileId, Throwable cause) {
    try {
      if (storage().isExist(fileId)) {
        storage().delete(fileId);
      }
    } catch (RuntimeException e) {
      cause.addSuppressed(e);
    }
  }

  /**
   * Content that {@linkplain #storeContent} stored.
   *
   * @param fileId the ID of the content in the storage.
   * @param size the number of bytes of the content.
   * @param md5 the hex encoded MD5 digest of the content.
   */
  record StoredContent(Long fileId, long size, String md5) {
  }

}
