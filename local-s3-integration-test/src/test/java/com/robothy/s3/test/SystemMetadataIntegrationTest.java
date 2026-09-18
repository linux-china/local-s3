package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.auth.signer.AwsS3V4Signer;
import software.amazon.awssdk.auth.signer.S3SignerExecutionAttribute;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The standard system-defined metadata of an object, e.g. {@code Cache-Control}, and the {@code response-*} query
 * parameters that override the headers of a response.
 */
class SystemMetadataIntegrationTest {

  private static final String BUCKET = "system-metadata";

  private static final Instant EXPIRES = Instant.parse("2033-12-01T16:00:00Z");

  @Test
  @LocalS3
  void putObjectKeepsTheSystemMetadata(S3Client s3) {
    s3.createBucket(request -> request.bucket(BUCKET));
    s3.putObject(request -> request.bucket(BUCKET).key("report.csv.gz")
            .contentType("text/csv")
            .cacheControl("max-age=3600")
            .contentDisposition("attachment; filename=\"report.csv\"")
            .contentEncoding("gzip")
            .contentLanguage("en-US")
            .expires(EXPIRES),
        RequestBody.fromString("a,b"));

    ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(request -> request.bucket(BUCKET).key("report.csv.gz"));
    GetObjectResponse get = object.response();
    assertEquals("text/csv", get.contentType());
    assertEquals("max-age=3600", get.cacheControl());
    assertEquals("attachment; filename=\"report.csv\"", get.contentDisposition());
    assertEquals("gzip", get.contentEncoding());
    assertEquals("en-US", get.contentLanguage());
    assertEquals(EXPIRES, get.expires());
    assertEquals("a,b", object.asUtf8String());

    HeadObjectResponse head = s3.headObject(request -> request.bucket(BUCKET).key("report.csv.gz"));
    assertEquals("max-age=3600", head.cacheControl());
    assertEquals("attachment; filename=\"report.csv\"", head.contentDisposition());
    assertEquals("gzip", head.contentEncoding());
    assertEquals("en-US", head.contentLanguage());
    assertEquals(EXPIRES, head.expires());
  }

  @Test
  @LocalS3
  void awsChunkedIsNotStoredAsTheContentEncoding(S3Client s3) {
    s3.createBucket(request -> request.bucket(BUCKET));
    s3.putObject(request -> request.bucket(BUCKET).key("chunked.txt")
            .overrideConfiguration(configuration -> {
              configuration.signer(AwsS3V4Signer.create());
              configuration.executionAttributes()
                  .putAttribute(S3SignerExecutionAttribute.ENABLE_PAYLOAD_SIGNING, true)
                  .putAttribute(S3SignerExecutionAttribute.ENABLE_CHUNKED_ENCODING, true);
            }),
        RequestBody.fromString("Signed stream"));

    assertNull(s3.headObject(request -> request.bucket(BUCKET).key("chunked.txt")).contentEncoding());
  }

  /**
   * An object stored by a request that carries no {@code Content-Type} is served with
   * {@code binary/octet-stream}, like Amazon S3 serves it: a {@code GetObject} or {@code HeadObject} response
   * always carries a content type, which a browser and the clients that branch on it rely on.
   */
  @Test
  @LocalS3
  void anObjectStoredWithoutAContentTypeIsServedWithTheDefaultOne(S3Client s3, LocalS3Endpoint endpoint)
      throws Exception {
    s3.createBucket(request -> request.bucket(BUCKET));
    // The AWS SDK always sends a content type, so the object is stored over plain HTTP without one; the service
    // accepts unsigned requests here, since @LocalS3 carries no credentials.
    HttpClient http = HttpClient.newHttpClient();
    URI url = URI.create(endpoint.endpoint() + "/" + BUCKET + "/no-content-type.bin");
    HttpResponse<Void> put = http.send(HttpRequest.newBuilder(url)
        .PUT(HttpRequest.BodyPublishers.ofString("xyz")).build(), HttpResponse.BodyHandlers.discarding());
    assertEquals(200, put.statusCode());

    assertEquals("binary/octet-stream",
        s3.headObject(request -> request.bucket(BUCKET).key("no-content-type.bin")).contentType());
    assertEquals("binary/octet-stream",
        s3.getObject(request -> request.bucket(BUCKET).key("no-content-type.bin")).response().contentType());
  }

  @Test
  @LocalS3
  void responseParametersOverrideTheHeaders(S3Client s3) {
    s3.createBucket(request -> request.bucket(BUCKET));
    s3.putObject(request -> request.bucket(BUCKET).key("a.txt").contentType("text/plain").cacheControl("no-cache"),
        RequestBody.fromString("Hello"));

    GetObjectResponse get = s3.getObject(request -> request.bucket(BUCKET).key("a.txt")
        .responseContentType("application/octet-stream")
        .responseContentDisposition("attachment; filename=\"hello.txt\"")
        .responseCacheControl("max-age=60")
        .responseContentEncoding("identity")
        .responseContentLanguage("fr")
        .responseExpires(EXPIRES)).response();
    assertEquals("application/octet-stream", get.contentType());
    assertEquals("attachment; filename=\"hello.txt\"", get.contentDisposition());
    assertEquals("max-age=60", get.cacheControl());
    assertEquals("identity", get.contentEncoding());
    assertEquals("fr", get.contentLanguage());
    assertEquals(EXPIRES, get.expires());

    HeadObjectResponse head = s3.headObject(request -> request.bucket(BUCKET).key("a.txt")
        .responseContentDisposition("inline"));
    assertEquals("inline", head.contentDisposition());
    assertEquals("no-cache", head.cacheControl());
  }

  @Test
  @LocalS3
  void presignedUrlDownloadsUnderTheRequestedName(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    s3.createBucket(request -> request.bucket(BUCKET));
    s3.putObject(request -> request.bucket(BUCKET).key("a.txt"), RequestBody.fromString("Hello"));

    try (S3Presigner presigner = S3Presigner.builder()
        .endpointOverride(URI.create(endpoint.endpoint()))
        .region(Region.of(endpoint.region()))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("foo", "bar")))
        .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
        .build()) {
      URI url = presigner.presignGetObject(presign -> presign.signatureDuration(Duration.ofMinutes(5))
          .getObjectRequest(request -> request.bucket(BUCKET).key("a.txt")
              .responseContentDisposition("attachment; filename=\"download.txt\""))).url().toURI();

      HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
      try {
        assertEquals(200, connection.getResponseCode());
        assertEquals("attachment; filename=\"download.txt\"", connection.getHeaderField("Content-Disposition"));
      } finally {
        connection.disconnect();
      }
    }
  }

  @Test
  @LocalS3
  void multipartUploadKeepsTheSystemMetadata(S3Client s3) {
    s3.createBucket(request -> request.bucket(BUCKET));
    String uploadId = s3.createMultipartUpload(request -> request.bucket(BUCKET).key("a.bin")
        .cacheControl("max-age=10")
        .contentDisposition("attachment")).uploadId();
    String etag = s3.uploadPart(request -> request.bucket(BUCKET).key("a.bin").uploadId(uploadId).partNumber(1),
        RequestBody.fromString("Hello")).eTag();
    s3.completeMultipartUpload(request -> request.bucket(BUCKET).key("a.bin").uploadId(uploadId)
        .multipartUpload(upload -> upload.parts(CompletedPart.builder().partNumber(1).eTag(etag).build())));

    HeadObjectResponse head = s3.headObject(request -> request.bucket(BUCKET).key("a.bin"));
    assertEquals("max-age=10", head.cacheControl());
    assertEquals("attachment", head.contentDisposition());
  }

  @Test
  @LocalS3
  void copyObjectCopiesTheSystemMetadataUnlessTheDirectiveReplacesIt(S3Client s3) {
    s3.createBucket(request -> request.bucket(BUCKET));
    s3.putObject(request -> request.bucket(BUCKET).key("source").contentType("text/csv")
        .cacheControl("max-age=3600").contentLanguage("en"), RequestBody.fromString("a,b"));

    s3.copyObject(request -> request.sourceBucket(BUCKET).sourceKey("source")
        .destinationBucket(BUCKET).destinationKey("copied"));
    HeadObjectResponse copied = s3.headObject(request -> request.bucket(BUCKET).key("copied"));
    assertEquals("text/csv", copied.contentType());
    assertEquals("max-age=3600", copied.cacheControl());
    assertEquals("en", copied.contentLanguage());

    s3.copyObject(request -> request.sourceBucket(BUCKET).sourceKey("source")
        .destinationBucket(BUCKET).destinationKey("replaced")
        .metadataDirective(MetadataDirective.REPLACE)
        .contentType("application/json")
        .cacheControl("no-store"));
    HeadObjectResponse replaced = s3.headObject(request -> request.bucket(BUCKET).key("replaced"));
    assertEquals("application/json", replaced.contentType());
    assertEquals("no-store", replaced.cacheControl());
    assertNull(replaced.contentLanguage());
  }

}
