package com.robothy.s3.core.service;

import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.answers.CopyObjectAns;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.internal.SystemMetadata;
import com.robothy.s3.core.model.request.CopyObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.internal.ObjectChecksum;
import com.robothy.s3.core.model.request.RequestChecksum;
import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public interface CopyObjectService extends GetObjectService, PutObjectService, LocalS3MetadataApplicable, StorageApplicable {

  /**
   * See {@code docs/semantics.md#copies}. Neither bucket is locked while the content is copied: only the commit of
   * {@linkplain #putObject} holds the write lock of the destination.
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
        options.getSourcePreconditions(), options.getSourceCustomerEncryption());

    if (srcObjectAns.isDeleteMarker()) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidRequest,
          "The source of a copy request may not specifically refer to a delete marker by version id.");
    }
    if (isCopyToItselfWithoutChanges(bucket, key, options)) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidRequest, "This copy request is illegal because it is "
          + "trying to copy an object to itself without changing the object's metadata, storage class, website "
          + "redirect location or encryption attributes.");
    }

    boolean replaceMetadata = options.getMetadataDirective() == CopyObjectOptions.MetadataDirective.REPLACE;
    Map<String, String> metadataToUse = replaceMetadata ? options.getUserMetadata() : srcObjectAns.getUserMetadata();
    String contentTypeToUse = replaceMetadata ? options.getContentType() : srcObjectAns.getContentType();
    SystemMetadata systemMetadataToUse = SystemMetadata.withWebsiteRedirectLocation(
        SystemMetadata.withStorageClass(replaceMetadata ? options.getSystemMetadata()
            : srcObjectAns.getSystemMetadata(), options.getStorageClass()), options.getWebsiteRedirectLocation());

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
        .checksum(copyChecksum(options, srcObjectAns))
        .objectLock(options.getObjectLock())
        .customerEncryption(options.getCustomerEncryption())
        .serverSideEncryption(options.getServerSideEncryption())
        .build());

    return CopyObjectAns.builder()
        .sourceVersionId(srcObjectAns.getVersionId())
        .versionId(putObjectAns.getVersionId())
        .lastModified(putObjectAns.getCreationDate())
        .etag(putObjectAns.getEtag())
        .size(putObjectAns.getSize())
        .checksum(putObjectAns.getChecksum())
        .serverSideEncryption(putObjectAns.getServerSideEncryption())
        .build();
  }

  private static boolean isCopyToItselfWithoutChanges(String bucket, String key, CopyObjectOptions options) {
    return bucket.equals(options.getSourceBucket()) && key.equals(options.getSourceKey())
        && options.getSourceVersion().isEmpty()
        && options.getMetadataDirective() != CopyObjectOptions.MetadataDirective.REPLACE
        && options.getTaggingDirective() != CopyObjectOptions.TaggingDirective.REPLACE
        && Objects.isNull(options.getStorageClass())
        && Objects.isNull(options.getWebsiteRedirectLocation())
        && Objects.isNull(options.getChecksumAlgorithm())
        && Objects.isNull(options.getServerSideEncryption())
        && Objects.isNull(options.getCustomerEncryption());
  }

  private static RequestChecksum copyChecksum(CopyObjectOptions options, GetObjectAns source) {
    CheckSumAlgorithm algorithm = Objects.nonNull(options.getChecksumAlgorithm()) ? options.getChecksumAlgorithm()
        : Optional.ofNullable(source.getChecksum()).map(ObjectChecksum::getAlgorithm).orElse(null);
    return Objects.isNull(algorithm) ? null : RequestChecksum.of(algorithm, null);
  }

}
