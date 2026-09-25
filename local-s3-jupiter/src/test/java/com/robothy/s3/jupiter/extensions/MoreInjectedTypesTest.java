package com.robothy.s3.jupiter.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.event.S3Change;
import java.net.HttpURLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

@com.robothy.s3.jupiter.LocalS3(buckets = "bucket")
class MoreInjectedTypesTest {

  @Test
  void asyncClient(S3AsyncClient client, S3Client s3) {
    client.putObject(b -> b.bucket("bucket").key("async.txt"), AsyncRequestBody.fromString("async")).join();
    assertEquals("async", s3.getObjectAsBytes(b -> b.bucket("bucket").key("async.txt")).asUtf8String());
  }

  @Test
  void presigner(S3Presigner presigner, S3Client s3) throws Exception {
    s3.putObject(b -> b.bucket("bucket").key("presigned.txt"), RequestBody.fromString("presigned"));
    var url = presigner.presignGetObject(b -> b.signatureDuration(Duration.ofMinutes(5))
        .getObjectRequest(r -> r.bucket("bucket").key("presigned.txt"))).url();
    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
    try (var in = connection.getInputStream()) {
      assertEquals("presigned", new String(in.readAllBytes()));
    }
  }

  @Test
  void transferManager(S3TransferManager transferManager, S3Client s3, @TempDir Path dir) throws Exception {
    Path file = Files.writeString(dir.resolve("upload.txt"), "transferred");
    transferManager.uploadFile(b -> b.source(file).putObjectRequest(r -> r.bucket("bucket").key("upload.txt")))
        .completionFuture().join();
    assertEquals("transferred", s3.getObjectAsBytes(b -> b.bucket("bucket").key("upload.txt")).asUtf8String());
  }

  @Test
  void service(com.robothy.s3.rest.LocalS3 localS3, S3Client s3) {
    List<S3Change> changes = new CopyOnWriteArrayList<>();
    localS3.getS3Manager().addChangeListener(changes::add);
    s3.putObject(b -> b.bucket("bucket").key("changed.txt"), RequestBody.fromString("changed"));
    assertTrue(changes.stream().anyMatch(change -> "changed.txt".equals(change.key())), changes::toString);

    localS3.reset();
    assertTrue(s3.listObjectsV2(b -> b.bucket("bucket")).contents().isEmpty());
  }

}
