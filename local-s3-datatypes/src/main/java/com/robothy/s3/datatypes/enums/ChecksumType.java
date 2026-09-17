package com.robothy.s3.datatypes.enums;

/**
 * How the checksum of an object is computed.
 */
public enum ChecksumType {

  /**
   * The checksum of the whole content of the object.
   */
  FULL_OBJECT,

  /**
   * The checksum of the checksums of the parts of an object that a multipart upload stored, followed by
   * {@code -} and the number of parts.
   */
  COMPOSITE

}
