package com.robothy.s3.core.service;

import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.annotations.BucketChanged;
import com.robothy.s3.core.annotations.BucketReadLock;
import com.robothy.s3.core.annotations.BucketWriteLock;
import com.robothy.s3.core.annotations.CallsThroughProxy;
import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.assertions.UploadAssertions;
import com.robothy.s3.core.exception.InvalidPartException;
import com.robothy.s3.core.exception.InvalidPartOrderException;
import com.robothy.s3.core.model.answers.CompleteMultipartUploadAns;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectPartMetadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.internal.UploadPartMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.CompleteMultipartUploadPartOption;
import com.robothy.s3.core.util.S3ObjectUtils;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * Complete a multipart upload.
 */
public interface CompleteMultipartUploadService extends LocalS3MetadataApplicable, StorageApplicable, PutObjectService {

  /**
   * Compete a multipart upload. The parts aren't concatenated: the object references the stored content of the parts
   * that complete the upload, so that completing an upload takes neither the time nor the disk space of copying its
   * content. The upload is validated under the bucket read lock, and only {@linkplain #commitCompleteMultipartUpload}
   * holds the bucket write lock; a part whose content has to be read to compute the entity tag of the object is read
   * without a lock.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId multipart upload ID.
   * @param completeParts multipart upload parts to complete.
   * @return result of the complete multipart operation.
   */
  @CallsThroughProxy
  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts) {
    return completeMultipartUpload(bucket, key, uploadId, completeParts, 0);
  }

  /**
   * Complete a multipart upload, requiring every part but the last one to be at least {@code minimumPartSize}
   * bytes. Amazon S3 always requires {@linkplain UploadAssertions#MIN_PART_SIZE}; LocalS3 only does when it is
   * configured to, so that tests that upload small parts keep working.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId multipart upload ID.
   * @param completeParts multipart upload parts to complete.
   * @param minimumPartSize the smallest size of a part that isn't the last one; {@code 0} to check nothing.
   * @return result of the complete multipart operation.
   */
  @CallsThroughProxy
  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts,
                                                             long minimumPartSize) {
    return completeMultipartUpload(bucket, key, uploadId, completeParts, minimumPartSize, true);
  }

  /**
   * Complete a multipart upload, giving the object the entity tag that Amazon S3 gives an object uploaded in
   * parts, or the MD5 digest of its whole content.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId multipart upload ID.
   * @param completeParts multipart upload parts to complete.
   * @param minimumPartSize the smallest size of a part that isn't the last one; {@code 0} to check nothing.
   * @param compositeEtag {@code true} to give the object the
   *     {@linkplain S3ObjectUtils#compositeEtag(List) entity tag of Amazon S3}, which is what a client reads
   *     the part layout of an object off; {@code false} to give it the MD5 digest of its whole content,
   *     which is what LocalS3 gave it before 2.5, and which reads the content of all parts.
   * @return result of the complete multipart operation.
   */
  @CallsThroughProxy
  default CompleteMultipartUploadAns completeMultipartUpload(String bucket, String key, String uploadId,
                                                             List<CompleteMultipartUploadPartOption> completeParts,
                                                             long minimumPartSize, boolean compositeEtag) {
    // Invoked on the proxy, which read locks the bucket while the upload is validated. The parts of the returned
    // upload are a snapshot of the ones that complete it, so a part uploaded again from here on isn't mixed in.
    UploadMetadata uploadMetadata = prepareCompleteMultipartUpload(bucket, key, uploadId, completeParts);
    UploadAssertions.assertPartsAreLargeEnough(uploadMetadata, completeParts, minimumPartSize);
    NavigableMap<Integer, UploadPartMetadata> partsToComplete = uploadMetadata.getParts();

    // The layout records the bytes that are actually stored for every part, which the length declared when a part
    // was uploaded may not match; the content of the object is read from the parts by these sizes.
    List<ObjectPartMetadata> layout = new ArrayList<>(partsToComplete.size());
    long size = 0;
    for (Map.Entry<Integer, UploadPartMetadata> part : partsToComplete.entrySet()) {
      long partSize = partSize(part.getKey(), part.getValue());
      layout.add(ObjectPartMetadata.builder()
          .partNumber(part.getKey())
          .size(partSize)
          .fileId(part.getValue().getFileId())
          .build());
      size += partSize;
    }

    VersionedObjectMetadata versionedObjectMetadata = new VersionedObjectMetadata();
    versionedObjectMetadata.setCreationDate(System.currentTimeMillis());
    versionedObjectMetadata.setContentType(uploadMetadata.getContentType());
    versionedObjectMetadata.setSystemMetadata(uploadMetadata.getSystemMetadata());
    versionedObjectMetadata.setSize(size);
    versionedObjectMetadata.setEtag(compositeEtag ? S3ObjectUtils.compositeEtag(partDigests(partsToComplete))
        : contentDigest(partsToComplete));
    // The content of the object is the content of the parts. The layout is kept after the upload is removed, so
    // that GetObjectAttributes can answer the part layout of the object as well.
    versionedObjectMetadata.setParts(layout);
    uploadMetadata.getTagging().ifPresent(versionedObjectMetadata::setTagging);
    if (Objects.nonNull(uploadMetadata.getUserMetadata())) {
      versionedObjectMetadata.setUserMetadata(uploadMetadata.getUserMetadata());
    }

    return commitCompleteMultipartUpload(bucket, key, uploadId, versionedObjectMetadata, partsToComplete);
  }

  /**
   * The number of bytes that are stored for a part.
   *
   * @throws InvalidPartException if the part was uploaded again since it was validated, which deleted its content.
   */
  private long partSize(int partNumber, UploadPartMetadata part) {
    try {
      return storage().size(part.getFileId());
    } catch (IllegalArgumentException e) {
      throw partGone(partNumber, e);
    }
  }

  /**
   * The MD5 digests of the parts that complete an upload, in the order of their part numbers.
   *
   * <p>The digest is the one that was computed when the content of the part was stored, rather than the entity tag
   * of the part: the entity tag is the one that the upload of the part answered, which a request may have supplied
   * instead of the digest of the data it sent. A part that a LocalS3 before 2.5 stored holds no digest, and its
   * content is read to compute it.
   */
  private List<byte[]> partDigests(NavigableMap<Integer, UploadPartMetadata> partsToComplete) {
    List<byte[]> digests = new ArrayList<>(partsToComplete.size());
    for (Map.Entry<Integer, UploadPartMetadata> part : partsToComplete.entrySet()) {
      String contentMd5 = part.getValue().getContentMd5();
      if (Objects.nonNull(contentMd5)) {
        try {
          digests.add(Hex.decodeHex(contentMd5));
          continue;
        } catch (DecoderException e) {
          // Not a digest; compute it from the content.
        }
      }
      MessageDigest md5 = DigestUtils.getMd5Digest();
      digestPart(part.getKey(), part.getValue(), md5);
      digests.add(md5.digest());
    }
    return digests;
  }

  /**
   * The MD5 digest of the whole content of the object that the parts complete, which is read from the parts.
   */
  private String contentDigest(NavigableMap<Integer, UploadPartMetadata> partsToComplete) {
    MessageDigest md5 = DigestUtils.getMd5Digest();
    partsToComplete.forEach((partNumber, part) -> digestPart(partNumber, part, md5));
    return S3ObjectUtils.etag(md5);
  }

  private void digestPart(int partNumber, UploadPartMetadata part, MessageDigest md5) {
    InputStream content;
    try {
      content = storage().getInputStream(part.getFileId());
    } catch (IllegalArgumentException e) {
      throw partGone(partNumber, e);
    }
    try (InputStream in = content) {
      DigestUtils.updateDigest(md5, in);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read part " + partNumber + ".", e);
    }
  }

  /**
   * The storage reports the content of a part that is gone with an {@linkplain IllegalArgumentException}, which
   * means that the part was uploaded again since it was validated.
   */
  private static InvalidPartException partGone(int partNumber, IllegalArgumentException cause) {
    InvalidPartException replaced = InvalidPartException.replaced(partNumber);
    replaced.addSuppressed(cause);
    return replaced;
  }

  /**
   * Validate an upload to complete and the parts that complete it. Called by
   * {@linkplain #completeMultipartUpload}, which measures the parts after the bucket is unlocked;
   * {@linkplain #commitCompleteMultipartUpload} validates the upload again under the write lock.
   *
   * <p>Every part must have been uploaded, and the entity tag that completes it must be the one that its upload
   * answered, like Amazon S3 requires.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId multipart upload ID.
   * @param completeParts multipart upload parts to complete.
   * @return a copy of the upload to complete, whose parts are the ones that complete it, as they are now; the
   *     parts of the upload itself change when a part is uploaded again.
   * @throws InvalidPartOrderException if the part numbers aren't in ascending order.
   * @throws InvalidPartException if a part wasn't uploaded, or was uploaded with another entity tag.
   */
  @BucketReadLock
  default UploadMetadata prepareCompleteMultipartUpload(String bucket, String key, String uploadId,
                                                        List<CompleteMultipartUploadPartOption> completeParts) {
    BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);
    UploadMetadata uploadMetadata = UploadAssertions.assertUploadExists(bucketMetadata, key, uploadId);

    if (completeParts.isEmpty()) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }

    int pre = -1;
    NavigableMap<Integer, UploadPartMetadata> partsToComplete = new TreeMap<>();
    for (CompleteMultipartUploadPartOption partOption : completeParts) {
      if (partOption.getPartNumber() <= pre) {
        throw new InvalidPartOrderException();
      }
      pre = partOption.getPartNumber();
      partsToComplete.put(partOption.getPartNumber(), UploadAssertions.assertPartMatches(uploadMetadata, partOption));
    }

    return UploadMetadata.builder()
        .createDate(uploadMetadata.getCreateDate())
        .contentType(uploadMetadata.getContentType())
        .systemMetadata(uploadMetadata.getSystemMetadata())
        .tagging(uploadMetadata.getTagging().orElse(null))
        .userMetadata(uploadMetadata.getUserMetadata())
        .parts(partsToComplete)
        .build();
  }

  /**
   * Add the object of a completed upload, whose content is the content of the parts that complete the upload, and
   * remove the upload with the content of its other parts. Called by {@linkplain #completeMultipartUpload}.
   *
   * @param bucket the bucket name.
   * @param key the object key.
   * @param uploadId multipart upload ID.
   * @param versionedObjectMetadata the metadata of the new version, referencing the content of the parts.
   * @param partsToComplete the parts that complete the upload, as {@linkplain #prepareCompleteMultipartUpload}
   *     validated them.
   * @return result of the complete multipart operation.
   * @throws InvalidPartException if a part was uploaded again since it was validated, so that the version would
   *     reference content that isn't the content of the upload.
   */
  @BucketChanged
  @BucketWriteLock
  default CompleteMultipartUploadAns commitCompleteMultipartUpload(String bucket, String key, String uploadId,
                                                                   VersionedObjectMetadata versionedObjectMetadata,
                                                                   NavigableMap<Integer, UploadPartMetadata> partsToComplete) {
    BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucket);
    UploadMetadata uploadMetadata = UploadAssertions.assertUploadExists(bucketMetadata, key, uploadId);
    // The data of a part is stored under a new ID whenever the part is uploaded, so an unchanged ID is an unchanged part.
    partsToComplete.forEach((partNumber, completing) -> {
      UploadPartMetadata current = uploadMetadata.getParts().get(partNumber);
      if (current == null || current.getFileId() != completing.getFileId()) {
        throw InvalidPartException.replaced(partNumber);
      }
    });
    PutObjectAns putObjectAns = PutObjectService.addVersion(bucketMetadata, storage(), key, versionedObjectMetadata);

    // The content of the parts that complete the upload now belongs to the object; only the other parts are deleted.
    uploadMetadata.getParts().forEach((partNumber, part) -> {
      if (!partsToComplete.containsKey(partNumber)) {
        storage().delete(part.getFileId());
      }
    });
    Map<String, NavigableMap<String, UploadMetadata>> uploads = bucketMetadata.getUploads();
    uploads.get(key).remove(uploadId);
    if (uploads.get(key).isEmpty()) {
      uploads.remove(key);
    }

    return CompleteMultipartUploadAns.builder()
        .location("/" + bucket + "/" + key)
        .versionId(putObjectAns.getVersionId())
        .etag(putObjectAns.getEtag())
        .size(putObjectAns.getSize())
        .build();
  }

}
