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

  private long creationDate;

  private long size;

  /**
   * Is this versioned object deleted.
   */
  private boolean isDeleted;

  /**
   * File ID in {@linkplain com.robothy.s3.core.storage.Storage}.
   */
  private Long fileId;

  private String[][] tagging;

  private AccessControlPolicy acl;

  /**
   * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingMetadata.html#UserMetadata">User-defined object metadata</a>.
   */
  private Map<String, String> userMetadata = Collections.emptyMap();

  /**
   * The parts of the multipart upload that stored this version, in the order they were concatenated in;
   * {@code null} when {@code PutObject} stored it, or when an upload that a LocalS3 before 2.5 completed
   * did. {@code GetObjectAttributes} answers the part layout of the object from it.
   */
  private List<ObjectPartMetadata> parts;

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
