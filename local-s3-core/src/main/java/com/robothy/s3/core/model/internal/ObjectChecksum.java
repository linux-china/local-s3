package com.robothy.s3.core.model.internal;

import com.robothy.s3.datatypes.enums.CheckSumAlgorithm;
import com.robothy.s3.datatypes.enums.ChecksumType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity.html">checksum</a>
 * that is stored with an object, or with a part of a multipart upload.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ObjectChecksum {

  private CheckSumAlgorithm algorithm;

  /**
   * {@linkplain ChecksumType#FULL_OBJECT} for a part.
   */
  private ChecksumType type;

  /**
   * The base64 encoded checksum, which a {@linkplain ChecksumType#COMPOSITE} checksum follows with {@code -} and the
   * number of parts, e.g. {@code 8Rp0UA==-3}.
   */
  private String value;

  /**
   * The checksum of the whole content of an object or a part.
   */
  public static ObjectChecksum fullObject(CheckSumAlgorithm algorithm, String value) {
    return new ObjectChecksum(algorithm, ChecksumType.FULL_OBJECT, value);
  }

}
