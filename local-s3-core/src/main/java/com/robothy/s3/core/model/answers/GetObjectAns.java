package com.robothy.s3.core.model.answers;

import com.robothy.s3.core.model.internal.CustomerEncryption;
import com.robothy.s3.core.model.internal.ObjectChecksum;
import com.robothy.s3.core.model.internal.ObjectLock;
import com.robothy.s3.core.model.internal.ObjectPartMetadata;
import com.robothy.s3.core.model.internal.SystemMetadata;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GetObjectAns {

  private String bucketName;

  private String key;

  private String versionId;

  private boolean deleteMarker;

  /**
   * Whether the client already holds this version of the object, i.e. an {@code If-None-Match} or an
   * {@code If-Modified-Since} precondition of the read didn't hold. The answer then carries the metadata
   * that identifies the version, but no {@linkplain #content}, and the response is
   * {@code 304 Not Modified}.
   */
  private boolean notModified;

  private String contentType;

  /**
   * The system-defined metadata of the object besides its content type; {@code null} if it has none.
   */
  private SystemMetadata systemMetadata;

  private long size;

  private long lastModified;

  private String etag;

  private InputStream content;

  private Map<String, String> userMetadata;

  private int taggingCount;

  /**
   * Tagging of the object; {@code null} when it isn't tagged.
   */
  private String[][] tagging;

  /**
   * Value of the {@code Content-Range} response header, e.g. {@code "bytes 0-9/443"}.
   * {@code null} when the response is not a ranged result.
   */
  private String contentRange;

  /**
   * The parts of the multipart upload that stored this version, which {@code GetObjectAttributes} answers
   * the part layout of the object from; {@code null} when it wasn't stored by one.
   */
  private List<ObjectPartMetadata> parts;

  /**
   * The checksum that the version was stored with; {@code null} if it was stored without one, and for a range of
   * the content, which the checksum isn't the one of.
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
}
