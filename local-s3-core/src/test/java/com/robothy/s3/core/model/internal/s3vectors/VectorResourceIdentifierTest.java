package com.robothy.s3.core.model.internal.s3vectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.core.exception.vectors.LocalS3VectorException;
import org.junit.jupiter.api.Test;

class VectorResourceIdentifierTest {

  @Test
  void fromArnOfVectorBucket() {
    assertEquals(new VectorResourceIdentifier("bucket", null),
        VectorResourceIdentifier.fromArn("arn:aws:s3vectors:::vector-bucket/bucket"));
  }

  @Test
  void fromArnOfIndex() {
    VectorResourceIdentifier resource =
        VectorResourceIdentifier.fromArn("arn:aws:s3vectors:::vector-bucket/bucket/index/my-index");
    assertEquals(new VectorResourceIdentifier("bucket", "my-index"), resource);
    assertEquals(true, resource.isIndex());
  }

  @Test
  void fromInvalidArn() {
    assertThrows(LocalS3VectorException.class, () -> VectorResourceIdentifier.fromArn(null));
    assertThrows(LocalS3VectorException.class, () -> VectorResourceIdentifier.fromArn("arn:aws:s3:::bucket"));
    assertThrows(LocalS3VectorException.class,
        () -> VectorResourceIdentifier.fromArn("arn:aws:s3vectors:::vector-bucket/"));
    assertThrows(LocalS3VectorException.class,
        () -> VectorResourceIdentifier.fromArn("arn:aws:s3vectors:::vector-bucket/bucket/other"));
    assertThrows(LocalS3VectorException.class,
        () -> VectorResourceIdentifier.fromArn("arn:aws:s3vectors:::vector-bucket/bucket/index/"));
  }

}
