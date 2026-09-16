package com.robothy.s3.core.assertions;

import com.robothy.s3.core.exception.InvalidObjectKeyException;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.util.Strings;

public class ObjectAssertions {

  public static void assertObjectKeyIsValid(String key) {
    if (Strings.isBlank(key)) {
      throw new InvalidObjectKeyException(key);
    }
  }

  public static ObjectMetadata assertObjectExists(BucketMetadata bucketMetadata, String key) {
    return bucketMetadata.getObjectMetadata(key).orElseThrow(() -> new ObjectNotExistException(key));
  }

}
