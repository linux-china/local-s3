package com.robothy.s3.core.service;

import com.robothy.s3.core.storage.HeapContent;
import com.robothy.s3.core.storage.Storage;
import com.robothy.s3.core.util.Checksums;
import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
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
    return storeContent(content, contentFile, null);
  }

  /**
   * Store the content of a request like {@linkplain #storeContent(InputStream, Path)}, computing a checksum of it as
   * well.
   *
   * @param content the content; may be {@code null} if {@code contentFile} is given.
   * @param contentFile a file that holds exactly the content; {@code null} if there is none.
   * @param checksumAlgorithm the algorithm of the checksum to compute; {@code null} to compute none.
   * @return the stored content.
   */
  default StoredContent storeContent(InputStream content, Path contentFile, CheckSumAlgorithm checksumAlgorithm) {
    return storeContent(content, contentFile, null, checksumAlgorithm);
  }

  /**
   * Store the content of a request like {@linkplain #storeContent(InputStream, Path, CheckSumAlgorithm)}, which may
   * have been received into the heap rather than into a file. Like a file, content in the heap is only read to measure
   * it, and the storage it was received for takes it over instead of copying it.
   *
   * @param content the content; may be {@code null} if {@code contentFile} or {@code heapContent} is given.
   * @param contentFile a file that holds exactly the content; {@code null} if there is none.
   * @param heapContent the content received into the heap; {@code null} if there is none.
   * @param checksumAlgorithm the algorithm of the checksum to compute; {@code null} to compute none.
   * @return the stored content.
   */
  default StoredContent storeContent(InputStream content, Path contentFile, HeapContent heapContent,
                                     CheckSumAlgorithm checksumAlgorithm) {
    Checksums.Calculator calculator = Objects.isNull(checksumAlgorithm) ? null : Checksums.calculator(checksumAlgorithm);
    if (Objects.isNull(contentFile) && Objects.isNull(heapContent)) {
      MeasuredInputStream measured = S3ObjectUtils.measuringStream(checksummed(content, calculator));
      Long fileId = storage().put(measured);
      return new StoredContent(fileId, measured.getSize(), measured.etag(), digest(calculator));
    }

    MeasuredInputStream measured;
    try {
      InputStream source = Objects.nonNull(content) ? content
          : Objects.nonNull(contentFile) ? Files.newInputStream(contentFile) : heapContent.newInputStream();
      measured = S3ObjectUtils.measuringStream(checksummed(source, calculator));
      try (InputStream in = measured) {
        in.transferTo(OutputStream.nullOutputStream());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the content"
          + (Objects.nonNull(contentFile) ? " in " + contentFile : "") + ".", e);
    }
    Long fileId = Objects.nonNull(contentFile) ? storage().put(contentFile) : storage().put(heapContent);
    return new StoredContent(fileId, measured.getSize(), measured.etag(), digest(calculator));
  }

  private static InputStream checksummed(InputStream content, Checksums.Calculator calculator) {
    return Objects.isNull(calculator) ? content : Checksums.checksumStream(content, calculator);
  }

  private static byte[] digest(Checksums.Calculator calculator) {
    return Objects.isNull(calculator) ? null : calculator.digest();
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
   * @param checksum the checksum of the content; {@code null} if none was computed.
   */
  record StoredContent(Long fileId, long size, String md5, byte[] checksum) {
  }

}
