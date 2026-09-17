package com.robothy.s3.test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.jupiter.LocalS3;
import com.robothy.s3.jupiter.LocalS3Endpoint;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.Credentials;

/**
 * Browser form uploads, {@code POST Object}, sent the way a browser sends them: a {@code multipart/form-data} body
 * posted to the bucket, whose policy and signature are fields of the form.
 */
class PostObjectIntegrationTest {

  private static final String BUCKET = "uploads";

  private static final String ACCESS_KEY = "form-access-key";

  private static final String SECRET_KEY = "form-secret-key";

  private static final String REGION = "us-east-1";

  private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

  @Test
  @LocalS3(buckets = BUCKET)
  void storesTheFileWithTheMetadataOfTheForm(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("key", "photos/${filename}");
    fields.put("Content-Type", "image/png");
    fields.put("Cache-Control", "max-age=60");
    fields.put("x-amz-meta-owner", "alice");
    fields.put("tagging", "<Tagging><TagSet><Tag><Key>source</Key><Value>browser</Value></Tag></TagSet></Tagging>");
    fields.put("success_action_status", "201");

    HttpResponse<String> response = post(endpoint, BUCKET, fields, "cat.png", "meow".getBytes(StandardCharsets.UTF_8));

    assertEquals(201, response.statusCode(), response.body());
    ResponseBytes<GetObjectResponse> object = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("photos/cat.png"));
    assertEquals("meow", object.asUtf8String());
    assertEquals("image/png", object.response().contentType());
    assertEquals("max-age=60", object.response().cacheControl());
    assertEquals(Map.of("owner", "alice"), object.response().metadata());
    assertEquals(List.of(Tag.builder().key("source").value("browser").build()),
        s3.getObjectTagging(b -> b.bucket(BUCKET).key("photos/cat.png")).tagSet());

    String etag = object.response().eTag();
    assertEquals(etag, response.headers().firstValue("ETag").orElse(null));
    assertTrue(response.body().contains("<Bucket>" + BUCKET + "</Bucket>"), response.body());
    assertTrue(response.body().contains("<Key>photos/cat.png</Key>"), response.body());
    assertTrue(response.body().contains("<ETag>" + etag + "</ETag>"), response.body());
    assertTrue(response.body().contains("<Location>http://127.0.0.1:" + endpoint.port() + "/uploads/photos/cat.png"
        + "</Location>"), response.body());
  }

  @Test
  @LocalS3(buckets = BUCKET)
  void answersNoContentByDefault(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    HttpResponse<String> response = post(endpoint, BUCKET, Map.of("key", "a b.txt"), "a.txt", bytes("hello"));

    assertEquals(204, response.statusCode(), response.body());
    assertEquals("http://127.0.0.1:" + endpoint.port() + "/uploads/a%20b.txt",
        response.headers().firstValue("Location").orElse(null));
    assertEquals("hello", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("a b.txt")).asUtf8String());
  }

  @Test
  @LocalS3(buckets = BUCKET)
  void redirectsToTheSuccessActionRedirect(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("key", "docs/report.pdf");
    fields.put("success_action_redirect", "https://app.example.com/done?upload=1");

    HttpResponse<String> response = post(endpoint, BUCKET, fields, "report.pdf", bytes("%PDF"));

    assertEquals(303, response.statusCode(), response.body());
    String location = response.headers().firstValue("Location").orElseThrow();
    String etag = s3.headObject(b -> b.bucket(BUCKET).key("docs/report.pdf")).eTag();
    assertTrue(location.startsWith("https://app.example.com/done?upload=1&"), location);
    assertTrue(URLDecoder.decode(location, StandardCharsets.UTF_8)
        .endsWith("&bucket=uploads&key=docs/report.pdf&etag=" + etag), location);
  }

  /**
   * A body larger than the threshold of the request decoder is buffered in a file, which the form is read from in
   * place.
   */
  @Test
  @LocalS3(buckets = BUCKET)
  void storesALargeFile(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    byte[] content = new byte[6 * 1024 * 1024 + 7];
    new Random(42).nextBytes(content);

    HttpResponse<String> response = post(endpoint, BUCKET, Map.of("key", "large.bin"), "large.bin", content);

    assertEquals(204, response.statusCode(), response.body());
    assertArrayEquals(content, s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("large.bin")).asByteArray());
  }

  /**
   * A service without credentials still checks the policy of a form that carries one, so that its expiration and
   * conditions can be debugged locally.
   */
  @Test
  @LocalS3(buckets = BUCKET)
  void checksThePolicyOfAFormWithoutCredentials(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    String conditions = "[{\"bucket\":\"uploads\"},[\"starts-with\",\"$key\",\"user/42/\"],"
        + "[\"content-length-range\",1,10]]";

    assertError(403, "AccessDenied", "Policy expired.", post(endpoint, BUCKET,
        form(policy(Instant.now().minusSeconds(1), conditions), "user/42/a.txt"), "a.txt", bytes("hi")));
    assertError(403, "AccessDenied", "Policy Condition failed: [\"starts-with\",\"$key\",\"user/42/\"]",
        post(endpoint, BUCKET, form(policy(future(), conditions), "user/7/a.txt"), "a.txt", bytes("hi")));
    assertError(400, "EntityTooLarge", "exceeds the maximum allowed size", post(endpoint, BUCKET,
        form(policy(future(), conditions), "user/42/a.txt"), "a.txt", bytes("more than ten bytes")));

    Map<String, String> extra = form(policy(future(), conditions), "user/42/a.txt");
    extra.put("x-amz-meta-note", "not in the policy");
    assertError(403, "AccessDenied", "Extra input fields: x-amz-meta-note",
        post(endpoint, BUCKET, extra, "a.txt", bytes("hi")));

    Map<String, String> ignored = form(policy(future(), conditions), "user/42/a.txt");
    ignored.put("x-ignore-tracking", "anything");
    assertEquals(204, post(endpoint, BUCKET, ignored, "a.txt", bytes("hi")).statusCode());

    assertError(400, "InvalidPolicyDocument", "Policy missing expiration.", post(endpoint, BUCKET,
        form(base64("{\"conditions\":[]}"), "user/42/b.txt"), "b.txt", bytes("hi")));
    assertThrows(NoSuchKeyException.class, () -> s3.headObject(b -> b.bucket(BUCKET).key("user/42/b.txt")));
  }

  @Test
  @LocalS3(buckets = BUCKET)
  void rejectsMalformedForms(LocalS3Endpoint endpoint) throws Exception {
    assertError(400, "InvalidArgument", "must contain a field named 'key'",
        post(endpoint, BUCKET, Map.of("Content-Type", "text/plain"), "a.txt", bytes("hi")));
    assertError(400, "IncorrectNumberOfFilesInPostRequest", null,
        send(endpoint, BUCKET, multipart(Map.of("key", "a.txt"), null, null)));
    assertError(400, "RequestIsNotMultiPartContent", null, HTTP.send(HttpRequest.newBuilder(
            URI.create(endpoint.endpoint() + "/" + BUCKET))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString("key=a.txt")).build(), HttpResponse.BodyHandlers.ofString()));
    assertError(404, "NoSuchBucket", null,
        post(endpoint, "no-such-bucket", Map.of("key", "a.txt"), "a.txt", bytes("hi")));
  }

  @Test
  @LocalS3(buckets = BUCKET, accessKey = ACCESS_KEY, secretKey = SECRET_KEY)
  void acceptsAFormSignedWithSignatureVersion4(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    Instant now = Instant.now();
    String amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now);
    String date = amzDate.substring(0, 8);
    String credential = ACCESS_KEY + "/" + date + "/" + REGION + "/s3/aws4_request";
    String policy = policy(now.plus(1, ChronoUnit.HOURS), "[{\"bucket\":\"uploads\"},"
        + "[\"starts-with\",\"$key\",\"signed/\"],"
        + "{\"x-amz-algorithm\":\"AWS4-HMAC-SHA256\"},"
        + "{\"x-amz-credential\":\"" + credential + "\"},"
        + "{\"x-amz-date\":\"" + amzDate + "\"}]");

    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("key", "signed/a.txt");
    fields.put("x-amz-algorithm", "AWS4-HMAC-SHA256");
    fields.put("x-amz-credential", credential);
    fields.put("x-amz-date", amzDate);
    fields.put("policy", policy);
    fields.put("x-amz-signature", HexFormat.of().formatHex(hmacSha256(signingKey(date), policy)));

    assertEquals(204, post(endpoint, BUCKET, fields, "a.txt", bytes("signed")).statusCode());
    assertEquals("signed", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("signed/a.txt")).asUtf8String());

    fields.put("x-amz-signature", "0".repeat(64));
    assertError(403, "SignatureDoesNotMatch", null, post(endpoint, BUCKET, fields, "a.txt", bytes("forged")));
    fields.put("x-amz-credential", "unknown-key/" + date + "/" + REGION + "/s3/aws4_request");
    assertError(403, "InvalidAccessKeyId", null, post(endpoint, BUCKET, fields, "a.txt", bytes("forged")));
    assertEquals("signed", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("signed/a.txt")).asUtf8String());
  }

  /**
   * A form signed with temporary credentials of the STS endpoint, e.g. by a backend that hands out upload forms with
   * credentials it got from {@code AssumeRole}, carries their session token in the {@code x-amz-security-token} field.
   */
  @Test
  @LocalS3(buckets = BUCKET, accessKey = ACCESS_KEY, secretKey = SECRET_KEY)
  void acceptsAFormSignedWithTemporaryCredentials(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    Credentials credentials;
    try (StsClient sts = StsClient.builder().endpointOverride(URI.create(endpoint.endpoint()))
        .region(Region.of(REGION))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
        .build()) {
      credentials = sts.assumeRole(b -> b.roleArn("arn:aws:iam::123456789012:role/uploader")
          .roleSessionName("browser")).credentials();
    }
    Instant now = Instant.now();
    String amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now);
    String date = amzDate.substring(0, 8);
    String credential = credentials.accessKeyId() + "/" + date + "/" + REGION + "/s3/aws4_request";
    String policy = policy(now.plus(1, ChronoUnit.HOURS), "[{\"bucket\":\"uploads\"},"
        + "{\"key\":\"session.txt\"},"
        + "{\"x-amz-algorithm\":\"AWS4-HMAC-SHA256\"},"
        + "{\"x-amz-credential\":\"" + credential + "\"},"
        + "{\"x-amz-date\":\"" + amzDate + "\"},"
        + "{\"x-amz-security-token\":\"" + credentials.sessionToken() + "\"}]");

    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("key", "session.txt");
    fields.put("x-amz-algorithm", "AWS4-HMAC-SHA256");
    fields.put("x-amz-credential", credential);
    fields.put("x-amz-date", amzDate);
    fields.put("x-amz-security-token", credentials.sessionToken());
    fields.put("policy", policy);
    fields.put("x-amz-signature", HexFormat.of().formatHex(
        hmacSha256(signingKey(credentials.secretAccessKey(), date), policy)));

    assertEquals(204, post(endpoint, BUCKET, fields, "a.txt", bytes("session")).statusCode());
    assertEquals("session", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("session.txt")).asUtf8String());

    fields.remove("x-amz-security-token");
    assertError(403, "InvalidAccessKeyId", null, post(endpoint, BUCKET, fields, "a.txt", bytes("forged")));
  }

  @Test
  @LocalS3(buckets = BUCKET, accessKey = ACCESS_KEY, secretKey = SECRET_KEY)
  void acceptsAFormSignedWithSignatureVersion2(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    String policy = policy(future(), "[{\"bucket\":\"uploads\"},[\"eq\",\"$key\",\"v2.txt\"]]");
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("key", "v2.txt");
    fields.put("AWSAccessKeyId", ACCESS_KEY);
    fields.put("policy", policy);
    Mac mac = Mac.getInstance("HmacSHA1");
    mac.init(new SecretKeySpec(SECRET_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
    fields.put("signature", Base64.getEncoder().encodeToString(mac.doFinal(policy.getBytes(StandardCharsets.UTF_8))));

    assertEquals(204, post(endpoint, BUCKET, fields, "v2.txt", bytes("v2")).statusCode());
    assertEquals("v2", s3.getObjectAsBytes(b -> b.bucket(BUCKET).key("v2.txt")).asUtf8String());
  }

  @Test
  @LocalS3(buckets = BUCKET, accessKey = ACCESS_KEY, secretKey = SECRET_KEY)
  void aServiceWithCredentialsRejectsUnsignedForms(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    assertError(403, "AccessDenied", "must contain a field named 'policy'",
        post(endpoint, BUCKET, Map.of("key", "anonymous.txt"), "a.txt", bytes("hi")));
    // A policy without a signature is as anonymous as no policy at all.
    assertError(403, "AccessDenied", null, post(endpoint, BUCKET,
        form(policy(future(), "[{\"bucket\":\"uploads\"},{\"key\":\"anonymous.txt\"}]"), "anonymous.txt"),
        "a.txt", bytes("hi")));
    assertThrows(NoSuchKeyException.class, () -> s3.headObject(b -> b.bucket(BUCKET).key("anonymous.txt")));

    // Only a form posted to a bucket is authenticated by its fields; a form posted anywhere else needs a signature.
    HttpResponse<String> toObject = send(endpoint, BUCKET + "/a.txt",
        multipart(Map.of("key", "a.txt"), "a.txt", bytes("hi")));
    assertEquals(403, toObject.statusCode(), toObject.body());
    assertFalse(toObject.body().contains("NoSuchUpload"), toObject.body());
  }

  /**
   * A browser posts the form from the page of another origin, which reads the response only if the CORS configuration
   * of the bucket allows it.
   */
  @Test
  @LocalS3(buckets = BUCKET)
  void answersACrossOriginFormWithTheCorsHeadersOfTheBucket(S3Client s3, LocalS3Endpoint endpoint) throws Exception {
    String origin = "http://localhost:3000";
    s3.putBucketCors(b -> b.bucket(BUCKET).corsConfiguration(c -> c.corsRules(rule -> rule
        .allowedOrigins(origin)
        .allowedMethods("POST")
        .exposeHeaders("ETag", "Location"))));
    Multipart body = multipart(Map.of("key", "cors.txt"), "cors.txt", bytes("hi"));

    HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(endpoint.endpoint() + "/" + BUCKET))
        .header("Content-Type", "multipart/form-data; boundary=" + body.boundary())
        .header("Origin", origin)
        .POST(HttpRequest.BodyPublishers.ofByteArray(body.content()))
        .build(), HttpResponse.BodyHandlers.ofString());

    assertEquals(204, response.statusCode(), response.body());
    assertEquals(origin, response.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
    assertTrue(response.headers().firstValue("Access-Control-Expose-Headers").orElse("").contains("ETag"),
        response.headers().map().toString());
  }

  private static Map<String, String> form(String policy, String key) {
    Map<String, String> fields = new LinkedHashMap<>();
    fields.put("key", key);
    fields.put("policy", policy);
    return fields;
  }

  private static Instant future() {
    return Instant.now().plus(1, ChronoUnit.HOURS);
  }

  private static String policy(Instant expiration, String conditions) {
    return base64("{\"expiration\":\"" + DateTimeFormatter.ISO_INSTANT.format(expiration.truncatedTo(ChronoUnit.MILLIS))
        + "\",\"conditions\":" + conditions + "}");
  }

  private static String base64(String value) {
    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] signingKey(String date) throws Exception {
    return signingKey(SECRET_KEY, date);
  }

  private static byte[] signingKey(String secretKey, String date) throws Exception {
    byte[] key = hmacSha256(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
    key = hmacSha256(key, REGION);
    key = hmacSha256(key, "s3");
    return hmacSha256(key, "aws4_request");
  }

  private static byte[] hmacSha256(byte[] key, String value) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(key, "HmacSHA256"));
    return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
  }

  private static void assertError(int status, String code, String message, HttpResponse<String> response) {
    assertEquals(status, response.statusCode(), response.body());
    assertTrue(response.body().contains("<Code>" + code + "</Code>"), response.body());
    if (message != null) {
      assertTrue(response.body().contains(message.replace("\"", "&quot;"))
          || response.body().contains(message), response.body());
    }
  }

  private static HttpResponse<String> post(LocalS3Endpoint endpoint, String bucket, Map<String, String> fields,
                                           String filename, byte[] content) throws Exception {
    return send(endpoint, bucket, multipart(fields, filename, content));
  }

  private static HttpResponse<String> send(LocalS3Endpoint endpoint, String path, Multipart body) throws Exception {
    return HTTP.send(HttpRequest.newBuilder(URI.create(endpoint.endpoint() + "/" + path))
        .header("Content-Type", "multipart/form-data; boundary=" + body.boundary())
        .POST(HttpRequest.BodyPublishers.ofByteArray(body.content()))
        .build(), HttpResponse.BodyHandlers.ofString());
  }

  private record Multipart(String boundary, byte[] content) {
  }

  /**
   * A form as a browser encodes it: the fields in order, then the file, if any.
   */
  private static Multipart multipart(Map<String, String> fields, String filename, byte[] content) {
    String boundary = "----LocalS3FormBoundary" + Long.toHexString(new Random().nextLong());
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    fields.forEach((name, value) -> out.writeBytes(("--" + boundary + "\r\n"
        + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
        .getBytes(StandardCharsets.UTF_8)));
    if (filename != null) {
      out.writeBytes(("--" + boundary + "\r\n"
          + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
          + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
      out.writeBytes(content);
      out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
    }
    out.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
    return new Multipart(boundary, out.toByteArray());
  }

}
