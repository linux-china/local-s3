package com.robothy.s3.core.service;

import com.robothy.s3.core.storage.Storage;

/**
 * Represent services that perform operations on a {@linkplain Storage}.
 */
public interface StorageApplicable {

  Storage storage();

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

}
