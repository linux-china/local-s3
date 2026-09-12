package com.robothy.s3.core.service;

import com.robothy.s3.core.annotations.CallsThroughProxy;
import com.robothy.s3.core.model.answers.CopyObjectAns;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.request.CopyObjectOptions;
import com.robothy.s3.core.model.request.GetObjectOptions;
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
  @CallsThroughProxy
  default CopyObjectAns copyObject(String bucket, String key, CopyObjectOptions options) {
    String srcVersion = options.getSourceVersion().orElse(null);
    // Invoked on the proxy, which read locks the source bucket while the source object is resolved.
    GetObjectAns srcObjectAns = getObject(options.getSourceBucket(), options.getSourceKey(),
        GetObjectOptions.builder().versionId(srcVersion).build());

    if (srcObjectAns.isDeleteMarker()) {
      throw new IllegalArgumentException("The source of a copy request may not specifically refer to a delete marker by version id.");
    }

    // Determine which metadata to use based on the metadata directive
    Map<String, String> metadataToUse;
    if (options.getMetadataDirective() == CopyObjectOptions.MetadataDirective.REPLACE) {
      // Use the metadata provided in the request
      metadataToUse = options.getUserMetadata();
    } else {
      // Use the metadata from the source object
      metadataToUse = srcObjectAns.getUserMetadata();
    }

    // The tagging of the source object is copied, unless the directive replaces it with the requested one.
    String[][] taggingToUse = options.getTaggingDirective() == CopyObjectOptions.TaggingDirective.REPLACE
        ? options.getTagging().orElse(null)
        : srcObjectAns.getTagging();

    // Invoked on the proxy, which stores the content before it write locks the destination bucket.
    PutObjectAns putObjectAns = putObject(bucket, key, PutObjectOptions.builder()
        .content(srcObjectAns.getContent())
        .contentType(srcObjectAns.getContentType())
        .size(srcObjectAns.getSize())
        .userMetadata(metadataToUse)
        .tagging(taggingToUse)
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
