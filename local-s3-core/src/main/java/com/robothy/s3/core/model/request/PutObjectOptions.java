package com.robothy.s3.core.model.request;

import com.robothy.s3.core.model.internal.CustomerEncryption;
import com.robothy.s3.core.model.internal.ObjectLock;
import com.robothy.s3.core.model.internal.SystemMetadata;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

@Builder
@Getter
@EqualsAndHashCode(exclude = "content")
public class PutObjectOptions {

  /**
   * The operation of a put that names none.
   */
  public static final String PUT_OBJECT = "PutObject";

  private String contentType;

  /**
   * The system-defined metadata of the object besides its content type; {@code null} if it has none.
   */
  private SystemMetadata systemMetadata;

  private long size;

  private InputStream content;

  /**
   * A file that holds exactly the {@linkplain #content}, e.g. the file that a large request body was buffered in;
   * {@code null} if there is none. The storage may take the file over instead of copying the content, and the
   * caller must not rely on the file afterwards.
   */
  private Path contentFile;

  private String contentMd5;

  /**
   * The checksum to store the object with; {@code null} to store it without one.
   */
  private RequestChecksum checksum;

  private String[][] tagging;

  private Map<String, String> userMetadata;

  /**
   * The conditions that the object the key holds must satisfy for the put to store the new one; {@code null}
   * if the request is unconditional.
   */
  private ObjectPreconditions preconditions;

  /**
   * The S3 operation that stores the object, which the change it publishes is named after, e.g. {@code PostObject}
   * for a browser form upload; {@code null} for {@code PutObject}.
   */
  private String operation;

  /**
   * The Object Lock settings to store the object with; {@code null} for none, which stores it with the default
   * retention of the bucket, if any.
   */
  private ObjectLock objectLock;

  /**
   * The customer-provided key to store the object with; {@code null} for none.
   */
  private CustomerEncryption customerEncryption;

  /**
   * The {@code x-amz-write-offset-bytes} of a request that appends the content to the object the key holds, which
   * must be the size of that object; {@code null} to replace the object.
   */
  private Long writeOffsetBytes;

  /**
   * Get tagging in the put object request.
   *
   * @return tagging.
   */
  public Optional<String[][]> getTagging() {
    return Optional.ofNullable(tagging);
  }

  /**
   * Get the conditions that the object the key holds must satisfy.
   *
   * @return the preconditions of the request; {@linkplain ObjectPreconditions#none()} if it carries none.
   */
  public ObjectPreconditions getPreconditions() {
    return Optional.ofNullable(preconditions).orElseGet(ObjectPreconditions::none);
  }

  /**
   * Get the S3 operation that stores the object.
   *
   * @return the operation; {@code PutObject} if none was set.
   */
  public String getOperation() {
    return Optional.ofNullable(operation).orElse(PUT_OBJECT);
  }

}
