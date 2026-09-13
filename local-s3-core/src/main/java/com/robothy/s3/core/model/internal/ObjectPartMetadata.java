package com.robothy.s3.core.model.internal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One part of the multipart upload that stored a version of an object, kept after the upload is completed
 * so that
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectAttributes.html">GetObjectAttributes</a>
 * can answer the part layout of the object. The parts of the upload itself, i.e.
 * {@linkplain UploadPartMetadata}, are removed once the upload is completed, along with the content they
 * reference.
 *
 * <p>A version that {@code PutObject} stored, or that an upload completed by a LocalS3 before 2.5
 * stored, has no parts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectPartMetadata {

  /**
   * The part number the part was uploaded with, which the parts of an upload don't have to be numbered
   * consecutively with.
   */
  private int partNumber;

  /**
   * The number of bytes of the part that were concatenated into the object.
   */
  private long size;

}
