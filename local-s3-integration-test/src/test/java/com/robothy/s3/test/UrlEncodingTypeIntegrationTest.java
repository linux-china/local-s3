package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * The listings with {@code encoding-type=url} encode like RFC 3986, which is how Amazon S3 encodes them: a space is
 * {@code %20} and a plus is {@code %2B}. The AWS SDK for Java decodes a {@code +} to a space as well, so the answers
 * are read as they are sent, which is what a client that decodes by RFC 3986 alone, e.g. of Python or Rust, reads.
 */
class UrlEncodingTypeIntegrationTest {

  private static final List<String> KEYS = List.of("city=New York/a.parquet", "foo+1/bar", "asdf+b", "é~*");

  @Test
  @LocalS3
  void listingsEncodeASpaceAsPercent20(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    s3.createBucket(b -> b.bucket("encoding"));
    for (String key : KEYS) {
      s3.putObject(b -> b.bucket("encoding").key(key), RequestBody.fromString(key));
    }
    s3.createMultipartUpload(b -> b.bucket("encoding").key("upload one/a b"));

    String listObjects = get(endpoint, "/encoding?encoding-type=url&delimiter=/");
    assertTrue(listObjects.contains("<Prefix>city%3DNew%20York/</Prefix>"), listObjects);
    assertTrue(listObjects.contains("<Prefix>foo%2B1/</Prefix>"), listObjects);
    assertTrue(listObjects.contains("<Key>asdf%2Bb</Key>"), listObjects);
    assertTrue(listObjects.contains("<Key>%C3%A9~%2A</Key>"), listObjects);

    // ListObjects (v1) leaves its own Prefix unencoded, as Amazon S3 does; ListObjectsV2 below encodes it.
    String listObjectsWithPrefix = get(endpoint, "/encoding?encoding-type=url&prefix=city%3DNew%20York/");
    assertTrue(listObjectsWithPrefix.contains("<Prefix>city=New York/</Prefix>"), listObjectsWithPrefix);
    assertTrue(listObjectsWithPrefix.contains("<Key>city%3DNew%20York/a.parquet</Key>"), listObjectsWithPrefix);

    String listObjectsV2 = get(endpoint, "/encoding?list-type=2&encoding-type=url&prefix=city%3DNew%20York/");
    assertTrue(listObjectsV2.contains("<Key>city%3DNew%20York/a.parquet</Key>"), listObjectsV2);
    assertTrue(listObjectsV2.contains("<Prefix>city%3DNew%20York/</Prefix>"), listObjectsV2);

    String listVersions = get(endpoint, "/encoding?versions&encoding-type=url&delimiter=/");
    assertTrue(listVersions.contains("<Prefix>city%3DNew%20York/</Prefix>"), listVersions);
    assertTrue(listVersions.contains("<Key>asdf%2Bb</Key>"), listVersions);
    assertTrue(listVersions.contains("<Delimiter>/</Delimiter>"), listVersions);

    String listUploads = get(endpoint, "/encoding?uploads&encoding-type=url");
    assertTrue(listUploads.contains("<Key>upload%20one/a%20b</Key>"), listUploads);

    for (String listing : List.of(listObjects, listObjectsV2, listVersions, listUploads)) {
      assertFalse(listing.contains("+"), listing);
    }
  }

  @Test
  @LocalS3
  void theSdkReadsTheKeysBack(S3Client s3) {
    s3.createBucket(b -> b.bucket("encoding"));
    for (String key : KEYS) {
      s3.putObject(b -> b.bucket("encoding").key(key), RequestBody.fromString(key));
    }

    assertEquals(KEYS.stream().sorted().toList(), s3.listObjectsV2(b -> b.bucket("encoding").encodingType("url"))
        .contents().stream().map(S3Object::key).toList());
    assertEquals(List.of("city=New York/", "foo+1/"), s3.listObjectsV2(b -> b.bucket("encoding")
        .encodingType("url").delimiter("/")).commonPrefixes().stream().map(CommonPrefix::prefix).toList());
    assertEquals(List.of("city=New York/", "foo+1/"), s3.listObjectVersions(b -> b.bucket("encoding")
        .encodingType("url").delimiter("/")).commonPrefixes().stream().map(CommonPrefix::prefix).toList());
  }

  private static String get(LocalS3Endpoint endpoint, String pathAndQuery) throws Exception {
    HttpResponse<String> response = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(endpoint.endpoint() + pathAndQuery)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return response.body();
  }

}
