package com.robothy.s3.jupiter.extensions;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.supplier.DataPathSupplier;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.io.File;
import java.io.IOException;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class LocalS3WithDataPathTest {


  @TempDir
  private static File tmpDir;

  @Order(1)
  @Test
  @LocalS3(mode = LocalS3Mode.PERSISTENCE, dataPathSupplier = DataPathSupplierImpl.class)
  @DisplayName("Create a bucket and a object in PERSISTENCE mode.")
  void test1(S3Client client) {
    assertDoesNotThrow(() -> client.createBucket(b -> b.bucket("my-bucket")));
    assertDoesNotThrow(() -> client.putObject(b -> b.bucket("my-bucket").key("a.txt").build(), RequestBody.fromString("LocalS3")));
  }

  @Order(2)
  @Test
  @LocalS3(mode = LocalS3Mode.IN_MEMORY, dataPathSupplier = DataPathSupplierImpl.class)
  @DisplayName("Create a bucket and a object in IN_MEMORY mode.")
  void test2(S3Client client) throws IOException {
    assertDoesNotThrow(() -> client.headBucket(b -> b.bucket("my-bucket")));
    var objectResponse = client.getObjectAsBytes(b -> b.bucket("my-bucket").key("a.txt"));
    assertEquals("LocalS3", objectResponse.asUtf8String());
    assertDoesNotThrow(() -> client.createBucket(b -> b.bucket("your-bucket")));
    assertDoesNotThrow(() -> client.putObject(b -> b.bucket("your-bucket").key("b.txt"), RequestBody.fromString("Robothy")));
  }

  @Order(3)
  @Test
  @LocalS3(mode = LocalS3Mode.IN_MEMORY, dataPathSupplier = DataPathSupplierImpl.class)
  @DisplayName("Changes in IN_MEMORY mode won't affect data in the disk.")
  void test3(S3Client client) throws IOException {
    assertDoesNotThrow(() -> client.headBucket(b -> b.bucket("my-bucket")));
    var objectResponse = client.getObjectAsBytes(b -> b.bucket("my-bucket").key("a.txt"));
    assertEquals("LocalS3", objectResponse.asUtf8String());
    // The bucket created by test2 in IN_MEMORY mode is neither on the disk nor in the cached initial data.
    assertThrows(NoSuchBucketException.class, () -> client.headBucket(b -> b.bucket("your-bucket")));
    assertDoesNotThrow(() -> client.createBucket(b -> b.bucket("her-bucket")));
    assertDoesNotThrow(() -> client.putObject(b -> b.bucket("her-bucket").key("c.txt"), RequestBody.fromString("Hello")));
  }

  @Order(4)
  @Test
  @LocalS3(mode = LocalS3Mode.PERSISTENCE, dataPathSupplier = DataPathSupplierImpl.class, initialDataCacheEnabled = false)
  @DisplayName("Changes in IN_MEMORY mode were not persisted; create a bucket in PERSISTENCE mode.")
  void test4(S3Client client) throws IOException {
    assertDoesNotThrow(() -> client.headBucket(b -> b.bucket("my-bucket")));
    var objectResponse = client.getObjectAsBytes(b -> b.bucket("my-bucket").key("a.txt"));
    assertEquals("LocalS3", objectResponse.asUtf8String());

    // The buckets created by test2 and test3 in IN_MEMORY mode are not on the disk.
    assertThrows(NoSuchBucketException.class, () -> client.headBucket(b -> b.bucket("your-bucket")));
    assertThrows(NoSuchBucketException.class, () -> client.headBucket(b -> b.bucket("her-bucket")));

    assertDoesNotThrow(() -> client.createBucket(b -> b.bucket("our-bucket")));
    assertDoesNotThrow(() -> client.putObject(b -> b.bucket("our-bucket").key("d.txt"), RequestBody.fromString("Persisted")));
  }

  @Order(5)
  @Test
  @LocalS3(mode = LocalS3Mode.PERSISTENCE, dataPathSupplier = DataPathSupplierImpl.class, initialDataCacheEnabled = false)
  @DisplayName("Change in PERSISTENCE mode will be persisted.")
  void test5(S3Client client) throws IOException {
    assertDoesNotThrow(() -> client.headBucket(b -> b.bucket("my-bucket")));
    var objectResponse = client.getObjectAsBytes(b -> b.bucket("my-bucket").key("a.txt"));
    assertEquals("LocalS3", objectResponse.asUtf8String());

    assertDoesNotThrow(() -> client.headBucket(b -> b.bucket("our-bucket")));
    var objectResponse1 = client.getObjectAsBytes(b -> b.bucket("our-bucket").key("d.txt"));
    assertEquals("Persisted", objectResponse1.asUtf8String());
  }

  static class DataPathSupplierImpl implements DataPathSupplier {
    @Override
    public String get() {
      return tmpDir.getAbsolutePath();
    }
  }

  @AfterAll
  public static void cleanup() throws IOException {
    FileUtils.deleteDirectory(tmpDir);
  }

}
