package com.robothy.s3.datatypes.enums;

/**
 * The algorithms of the
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/checking-object-integrity.html">checksums</a>
 * that Amazon S3 stores with an object.
 */
public enum CheckSumAlgorithm {

  CRC32,

  CRC32C,

  CRC64NVME,

  SHA1,

  SHA256

}
