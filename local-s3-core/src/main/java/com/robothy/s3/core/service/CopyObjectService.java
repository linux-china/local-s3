package com.robothy.s3.core.service;

import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.answers.CopyObjectAns;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.internal.SystemMetadata;
import com.robothy.s3.core.model.request.CopyObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import java.util.Map;

public interface CopyObjectService extends GetObjectService, PutObjectService, LocalS3MetadataApplicable, StorageApplicable {

  /**
   * Creates a copy of an object that is already stored in Local S3. Neither bucket is locked while the content
   * is copied, so that copying a large object doesn't block the other requests to them: the source object is
   * resolved under the read lock of the source bucket, its content is copied without a lock, and only the
   * commit of {@linkplain #putObject} holds the write lock of the destination bucket.
   *
   * @param bucket destination bucket.
   * @param key destination object key.
   * @param options copy options.
   * @return copy result.
   */
  default CopyObjectAns copyObject(String bucket, String key, CopyObjectOptions options) {
    // The object that putObject stores is created by CopyObject.
    return asOperation("CopyObject", () -> copy(bucket, key, options));
  }

  private CopyObjectAns copy(String bucket, String key, CopyObjectOptions options) {
    String srcVersion = options.getSourceVersion().orElse(null);
    // getCopySource read locks the source bucket while the source object is resolved and its conditions are
    // evaluated; the content is read without the lock.
    GetObjectAns srcObjectAns = getCopySource(options.getSourceBucket(), options.getSourceKey(), srcVersion, null,
        options.getSourcePreconditions());

    if (srcObjectAns.isDeleteMarker()) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidRequest,
          "The source of a copy request may not specifically refer to a delete marker by version id.");
    }

    // The metadata of the source object is copied, unless the directive replaces all of it, the content type and
    // the system-defined metadata included, with the one of the request.
    boolean replaceMetadata = options.getMetadataDirective() == CopyObjectOptions.MetadataDirective.REPLACE;
    Map<String, String> metadataToUse = replaceMetadata ? options.getUserMetadata() : srcObjectAns.getUserMetadata();
    String contentTypeToUse = replaceMetadata ? options.getContentType() : srcObjectAns.getContentType();
    SystemMetadata systemMetadataToUse = replaceMetadata ? options.getSystemMetadata()
        : srcObjectAns.getSystemMetadata();

    // The tagging of the source object is copied, unless the directive replaces it with the requested one.
    String[][] taggingToUse = options.getTaggingDirective() == CopyObjectOptions.TaggingDirective.REPLACE
        ? options.getTagging().orElse(null)
        : srcObjectAns.getTagging();

    // putObject stores the content before commitPutObject write locks the destination bucket.
    PutObjectAns putObjectAns = putObject(bucket, key, PutObjectOptions.builder()
        .content(srcObjectAns.getContent())
        .contentType(contentTypeToUse)
        .systemMetadata(systemMetadataToUse)
        .size(srcObjectAns.getSize())
        .userMetadata(metadataToUse)
        .tagging(taggingToUse)
        // Evaluated by commitPutObject, under the write lock of the destination bucket that the copy is added under.
        .preconditions(options.getPreconditions())
        .build());

    return CopyObjectAns.builder()
        .sourceVersionId(srcObjectAns.getVersionId())
        .versionId(putObjectAns.getVersionId())
        .lastModified(putObjectAns.getCreationDate())
        .etag(putObjectAns.getEtag())
        .size(putObjectAns.getSize())
        .build();
  }

}
