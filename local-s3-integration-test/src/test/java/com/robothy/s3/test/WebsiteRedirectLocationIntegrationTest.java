package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.robothy.s3.jupiter.LocalS3;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/**
 * The {@code x-amz-website-redirect-location} of an object is stored with it and answered by {@code GetObject} and
 * {@code HeadObject}; see {@code StaticWebsiteIntegrationTest} for the redirect of the website.
 */
class WebsiteRedirectLocationIntegrationTest {

  @Test
  @LocalS3
  void theWebsiteRedirectLocationIsStoredAndAnswered(S3Client s3) {
    String bucket = "redirect-bucket";
    s3.createBucket(b -> b.bucket(bucket));
    s3.putObject(b -> b.bucket(bucket).key("a.html").websiteRedirectLocation("/b.html"),
        RequestBody.fromString("Hello"));

    assertEquals("/b.html", s3.headObject(b -> b.bucket(bucket).key("a.html")).websiteRedirectLocation());
    assertEquals("/b.html",
        s3.getObjectAsBytes(b -> b.bucket(bucket).key("a.html")).response().websiteRedirectLocation());

    CreateMultipartUploadResponse upload = s3.createMultipartUpload(b -> b.bucket(bucket).key("c.html")
        .websiteRedirectLocation("https://example.com/"));
    UploadPartResponse part = s3.uploadPart(b -> b.bucket(bucket).key("c.html").uploadId(upload.uploadId())
        .partNumber(1), RequestBody.fromString("Hello"));
    s3.completeMultipartUpload(b -> b.bucket(bucket).key("c.html").uploadId(upload.uploadId())
        .multipartUpload(mu -> mu.parts(p -> p.partNumber(1).eTag(part.eTag()))));
    assertEquals("https://example.com/",
        s3.headObject(b -> b.bucket(bucket).key("c.html")).websiteRedirectLocation());
  }

  @Test
  @LocalS3
  void aCopyHasTheWebsiteRedirectLocationOfTheRequest(S3Client s3) {
    String bucket = "redirect-copy-bucket";
    s3.createBucket(b -> b.bucket(bucket));
    s3.putObject(b -> b.bucket(bucket).key("a.html").websiteRedirectLocation("/b.html"),
        RequestBody.fromString("Hello"));

    // Not copied from the source, whatever the metadata directive.
    s3.copyObject(b -> b.sourceBucket(bucket).sourceKey("a.html").destinationBucket(bucket).destinationKey("c.html"));
    assertNull(s3.headObject(b -> b.bucket(bucket).key("c.html")).websiteRedirectLocation());

    s3.copyObject(b -> b.sourceBucket(bucket).sourceKey("a.html").destinationBucket(bucket).destinationKey("d.html")
        .metadataDirective(MetadataDirective.REPLACE).websiteRedirectLocation("/e.html"));
    assertEquals("/e.html", s3.headObject(b -> b.bucket(bucket).key("d.html")).websiteRedirectLocation());

    // An object copied onto itself changes its location.
    s3.copyObject(b -> b.sourceBucket(bucket).sourceKey("a.html").destinationBucket(bucket).destinationKey("a.html")
        .websiteRedirectLocation("/f.html"));
    assertEquals("/f.html", s3.headObject(b -> b.bucket(bucket).key("a.html")).websiteRedirectLocation());
  }

  @Test
  @LocalS3
  void aLocationThatIsNeitherAPathNorAUrlIsRejected(S3Client s3) {
    String bucket = "redirect-invalid-bucket";
    s3.createBucket(b -> b.bucket(bucket));

    S3Exception exception = assertThrows(S3Exception.class, () -> s3.putObject(b -> b.bucket(bucket).key("a.html")
        .websiteRedirectLocation("b.html"), RequestBody.fromString("Hello")));
    assertEquals(400, exception.statusCode());
    assertEquals("InvalidArgument", exception.awsErrorDetails().errorCode());
  }

}
