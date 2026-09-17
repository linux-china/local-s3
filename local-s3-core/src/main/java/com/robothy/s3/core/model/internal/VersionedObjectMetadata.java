package com.robothy.s3.core.model.internal;

import com.robothy.s3.datatypes.AccessControlPolicy;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Data;

@Data
public class VersionedObjectMetadata {

  private String etag;

  private String contentType;

  /**
   * The system-defined metadata of the object besides its content type; {@code null} if it has none.
   */
  private SystemMetadata systemMetadata;

  private long creationDate;

  private long size;

  /**
   * Is this versioned object deleted.
   */
  private boolean isDeleted;

  /**
   * File ID in {@linkplain com.robothy.s3.core.storage.Storage}; {@code null} for a delete marker, and for a version
   * whose content is the content of its {@linkplain #parts}, each of which has its own file ID.
   *
   * @see com.robothy.s3.core.util.ObjectContentUtils
   */
  private Long fileId;

  private String[][] tagging;

  private AccessControlPolicy acl;

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingMetadata.html#UserMetadata">User-defined object metadata</a>.
   */
  private Map<String, String> userMetadata = Collections.emptyMap();

  /**
   * The parts of the multipart upload that stored this version, in the order of the content;
   * {@code null} when {@code PutObject} stored it, or when an upload that a LocalS3 before 2.5 completed
   * did. {@code GetObjectAttributes} answers the part layout of the object from it, and, when the parts have
   * file IDs, the content of the version is read from them.
   */
  private List<ObjectPartMetadata> parts;

  /**
   * The checksum that the version was stored with; {@code null} for a version that was stored without one, or by a
   * LocalS3 before checksums were stored.
   */
  private ObjectChecksum checksum;

  /**
   * The Object Lock retention and legal hold of the version; {@code null} if it has neither.
   */
  private ObjectLock objectLock;

  /**
   * The customer-provided key that the version was stored with; {@code null} if it wasn't stored with one.
   */
  private CustomerEncryption customerEncryption;

  /**
   * The SSE-S3 or SSE-KMS encryption that the version was stored with; {@code null} if it wasn't stored with one.
   */
  private ServerSideEncryption serverSideEncryption;

  /**
   * Get object tagging.
   */
  public Optional<String[][]> getTagging() {
    return Optional.ofNullable(tagging);
  }

  /**
   * Get object access control policy.
   */
  public Optional<AccessControlPolicy> getAcl() {
    return Optional.ofNullable(acl);
  }

  /**
   * The parts that this version was uploaded in.
   *
   * @return the parts of the upload that stored this version; empty if it wasn't stored by one.
   */
  public Optional<List<ObjectPartMetadata>> getParts() {
    return Optional.ofNullable(parts);
  }

}
