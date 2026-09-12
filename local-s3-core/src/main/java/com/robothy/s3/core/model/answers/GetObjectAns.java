package com.robothy.s3.core.model.answers;

import java.io.InputStream;
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
}
