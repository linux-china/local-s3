package com.robothy.s3.core.service.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.robothy.s3.core.annotations.BucketReadLock;
import com.robothy.s3.core.annotations.BucketWriteLock;
import com.robothy.s3.core.service.locks.BucketLock;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class LocalS3ServicesInvocationHandlerTest {

  @Test
  void rejectsMethodWithReadAndWriteLocks() {
    BucketLock bucketLock = mock(BucketLock.class);
    InvalidService delegate = bucketName -> { };
    LocalS3ServicesInvocationHandler<Object> handler = new LocalS3ServicesInvocationHandler<>(
        delegate, bucketLock, bucketName -> null, null);
    InvalidService service = (InvalidService) Proxy.newProxyInstance(
        InvalidService.class.getClassLoader(), new Class<?>[]{InvalidService.class}, handler);

    IllegalStateException exception = assertThrows(IllegalStateException.class,
        () -> service.invoke("bucket"));

    assertEquals("Bucket read and write locks are mutually exclusive.", exception.getMessage());
    verifyNoInteractions(bucketLock);
  }

  private interface InvalidService {

    @BucketReadLock
    @BucketWriteLock
    void invoke(String bucketName);
  }

}
