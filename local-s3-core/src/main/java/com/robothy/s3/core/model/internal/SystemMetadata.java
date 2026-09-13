package com.robothy.s3.core.model.internal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/UsingMetadata.html#SysMetadata">system-defined
 * metadata</a> of an object that the client sets when it stores the object, besides its {@code Content-Type}, and
 * that the object is served with. Each value is kept as the client sent it, e.g. {@code Expires} as an HTTP date.
 *
 * <p>An object that was stored without any of them, including one stored by a LocalS3 before 2.5, has no
 * {@code SystemMetadata} at all rather than an empty one.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SystemMetadata {

  private String cacheControl;

  private String contentDisposition;

  private String contentEncoding;

  private String contentLanguage;

  private String expires;

}
