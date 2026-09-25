package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.BucketLifecycleConfiguration;
import com.robothy.s3.core.model.LifecycleRule;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import com.robothy.s3.core.TestFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class BucketLifecycleServiceTest extends LocalS3ServiceTestBase {

  private static final String BUCKET = "lifecycle-bucket";

  private static final String CONFIGURATION = """
      <?xml version="1.0" encoding="UTF-8"?>
      <LifecycleConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
        <Rule>
          <ID>expire-tmp</ID>
          <Filter><Prefix>tmp/</Prefix></Filter>
          <Status>Enabled</Status>
          <Expiration><Days>1</Days></Expiration>
        </Rule>
        <Rule>
          <ID>abort-logs</ID>
          <Filter><And><Prefix>logs/</Prefix><Tag><Key>k</Key><Value>v</Value></Tag></And></Filter>
          <Status>Disabled</Status>
          <AbortIncompleteMultipartUpload><DaysAfterInitiation>7</DaysAfterInitiation></AbortIncompleteMultipartUpload>
        </Rule>
      </LifecycleConfiguration>
      """;

  @ParameterizedTest
  @MethodSource("bucketServices")
  void putsGetsAndDeletesTheLifecycleConfiguration(BucketService bucketService) {
    bucketService.createBucket(BUCKET);
    assertTrue(bucketService.getBucketLifecycleConfiguration(BUCKET).isEmpty());

    BucketLifecycleConfiguration put = bucketService.putBucketLifecycleConfiguration(BUCKET, CONFIGURATION, null);
    assertEquals(new BucketLifecycleConfiguration(CONFIGURATION, "all_storage_classes_128K"), put);
    assertEquals(put, bucketService.getBucketLifecycleConfiguration(BUCKET).get());

    // A configuration that is put again replaces the previous one.
    String replacement = rules(1);
    bucketService.putBucketLifecycleConfiguration(BUCKET, replacement, "varies_by_storage_class");
    assertEquals(new BucketLifecycleConfiguration(replacement, "varies_by_storage_class"),
        bucketService.getBucketLifecycleConfiguration(BUCKET).get());

    bucketService.deleteBucketLifecycle(BUCKET);
    assertTrue(bucketService.getBucketLifecycleConfiguration(BUCKET).isEmpty());
    // Deleting a configuration that doesn't exist succeeds, as it does on Amazon S3.
    bucketService.deleteBucketLifecycle(BUCKET);

    assertThrows(BucketNotExistException.class, () -> bucketService.getBucketLifecycleConfiguration("no-such-bucket"));
    assertThrows(BucketNotExistException.class,
        () -> bucketService.putBucketLifecycleConfiguration("no-such-bucket", CONFIGURATION, null));
  }

  @Test
  void persistsTheLifecycleConfiguration() throws IOException {
    Path dataPath = Files.createTempDirectory("local-s3");
    try {
      LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
      manager.bucketService().createBucket(BUCKET);
      manager.bucketService().putBucketLifecycleConfiguration(BUCKET, CONFIGURATION, "varies_by_storage_class");
      manager.close();

      LocalS3Manager restarted = LocalS3Manager.createFileSystemS3Manager(dataPath);
      try {
        assertEquals(new BucketLifecycleConfiguration(CONFIGURATION, "varies_by_storage_class"),
            restarted.bucketService().getBucketLifecycleConfiguration(BUCKET).get());
      } finally {
        restarted.close();
      }
    } finally {
      TestFiles.deleteDirectory(dataPath);
    }
  }

  @Test
  void generatesTheIdsOfRulesPutWithoutOne() {
    BucketService bucketService = LocalS3Manager.createInMemoryS3Manager().bucketService();
    bucketService.createBucket(BUCKET);
    String configuration = """
        <?xml version="1.0" encoding="UTF-8"?>
        <LifecycleConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
          <Rule><ID>named</ID><Status>Enabled</Status><Expiration><Days>1</Days></Expiration></Rule>
          <Rule><Status>Enabled</Status><Expiration><Days>2</Days></Expiration></Rule>
          <Rule><Status>Enabled</Status><Expiration><Days>3</Days></Expiration></Rule>
        </LifecycleConfiguration>
        """;
    bucketService.putBucketLifecycleConfiguration(BUCKET, configuration, null);

    String stored = bucketService.getBucketLifecycleConfiguration(BUCKET).get().configuration();
    assertTrue(stored.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"), stored);
    List<String> ids = LifecycleRule.parse(stored).stream().map(LifecycleRule::id).toList();
    assertEquals(3, ids.size());
    assertEquals("named", ids.get(0));
    assertNotNull(ids.get(1));
    assertNotNull(ids.get(2));
    assertNotEquals(ids.get(1), ids.get(2));
    assertEquals(List.of(1, 2, 3), LifecycleRule.parse(stored).stream().map(LifecycleRule::expirationDays).toList());
  }

  @ParameterizedTest
  @ValueSource(strings = {"2030-01-01T00:00:00Z", "2030-01-01T00:00:00.000Z", "2030-01-01"})
  void acceptsDatesAtMidnightUtc(String date) {
    BucketService bucketService = LocalS3Manager.createInMemoryS3Manager().bucketService();
    bucketService.createBucket(BUCKET);
    bucketService.putBucketLifecycleConfiguration(BUCKET,
        rule("<ID>r</ID><Status>Enabled</Status><Expiration><Date>" + date + "</Date></Expiration>"), null);
    bucketService.putBucketLifecycleConfiguration(BUCKET, rule("<ID>r</ID><Status>Enabled</Status><Transition><Date>"
        + date + "</Date><StorageClass>GLACIER</StorageClass></Transition>"), null);
  }

  @Test
  void acceptsTheMaxNumberOfRules() {
    BucketService bucketService = LocalS3Manager.createInMemoryS3Manager().bucketService();
    bucketService.createBucket(BUCKET);
    bucketService.putBucketLifecycleConfiguration(BUCKET, rules(BucketLifecycleService.MAX_RULES), null);
  }

  @ParameterizedTest
  @MethodSource("invalidConfigurations")
  void rejectsInvalidConfigurations(String configuration, String minimumObjectSize, S3ErrorCode expected) {
    BucketService bucketService = LocalS3Manager.createInMemoryS3Manager().bucketService();
    bucketService.createBucket(BUCKET);
    bucketService.putBucketLifecycleConfiguration(BUCKET, CONFIGURATION, null);

    LocalS3Exception thrown = assertThrows(LocalS3Exception.class,
        () -> bucketService.putBucketLifecycleConfiguration(BUCKET, configuration, minimumObjectSize));
    assertEquals(expected, thrown.getS3ErrorCode());
    // A rejected configuration leaves the previous one in place.
    assertEquals(CONFIGURATION, bucketService.getBucketLifecycleConfiguration(BUCKET).get().configuration());
  }

  static Stream<Arguments> invalidConfigurations() {
    String expire = "<Expiration><Days>1</Days></Expiration>";
    return Stream.of(
        Arguments.of(null, null, S3ErrorCode.MalformedXML),
        Arguments.of("", null, S3ErrorCode.MalformedXML),
        Arguments.of("<LifecycleConfiguration>", null, S3ErrorCode.MalformedXML),
        Arguments.of("not xml", null, S3ErrorCode.MalformedXML),
        Arguments.of("<CORSConfiguration><Rule><Status>Enabled</Status>" + expire + "</Rule></CORSConfiguration>",
            null, S3ErrorCode.MalformedXML),
        Arguments.of("<LifecycleConfiguration/>", null, S3ErrorCode.MalformedXML),
        Arguments.of("<LifecycleConfiguration><Other/></LifecycleConfiguration>", null, S3ErrorCode.MalformedXML),
        Arguments.of("<LifecycleConfiguration>text<Rule><Status>Enabled</Status>" + expire
            + "</Rule></LifecycleConfiguration>", null, S3ErrorCode.MalformedXML),
        Arguments.of(rule("<Status>Paused</Status>" + expire), null, S3ErrorCode.MalformedXML),
        Arguments.of(rule(expire), null, S3ErrorCode.MalformedXML),
        Arguments.of(rule("<Status><Enabled/></Status>" + expire), null, S3ErrorCode.MalformedXML),
        Arguments.of(rule("<Status>Enabled</Status><Filter><Prefix>a/</Prefix></Filter>"), null,
            S3ErrorCode.InvalidRequest),
        Arguments.of(rule("<ID>" + "i".repeat(256) + "</ID><Status>Enabled</Status>" + expire), null,
            S3ErrorCode.InvalidArgument),
        Arguments.of("<LifecycleConfiguration>"
            + "<Rule><ID>same</ID><Status>Enabled</Status>" + expire + "</Rule>"
            + "<Rule><ID>same</ID><Status>Enabled</Status>" + expire + "</Rule>"
            + "</LifecycleConfiguration>", null, S3ErrorCode.InvalidArgument),
        Arguments.of(rules(BucketLifecycleService.MAX_RULES + 1), null, S3ErrorCode.InvalidRequest),
        Arguments.of(rules(1), "1MB", S3ErrorCode.InvalidArgument),
        // A Date must be ISO 8601, at midnight UTC.
        Arguments.of(rule("<ID>r</ID><Status>Enabled</Status><Expiration><Date>20200101</Date></Expiration>"), null,
            S3ErrorCode.InvalidArgument),
        Arguments.of(rule("<ID>r</ID><Status>Enabled</Status><Expiration><Date>2020-01-01T08:00:00Z</Date>"
            + "</Expiration>"), null, S3ErrorCode.InvalidArgument),
        Arguments.of(rule("<ID>r</ID><Status>Enabled</Status><Transition><Date>1970-08-23T00:55:27Z</Date>"
            + "<StorageClass>GLACIER</StorageClass></Transition>"), null, S3ErrorCode.InvalidArgument),
        // A document type could declare entities that read files or URLs; it is refused rather than resolved.
        Arguments.of("<!DOCTYPE LifecycleConfiguration [<!ENTITY id SYSTEM \"file:///etc/passwd\">]>"
            + rule("<ID>&id;</ID><Status>Enabled</Status>" + expire), null, S3ErrorCode.MalformedXML)
    );
  }

  private static String rule(String content) {
    return "<LifecycleConfiguration><Rule>" + content + "</Rule></LifecycleConfiguration>";
  }

  private static String rules(int count) {
    return IntStream.range(0, count)
        .mapToObj(i -> "<Rule><ID>rule-" + i + "</ID><Status>Enabled</Status>"
            + "<Expiration><Days>1</Days></Expiration></Rule>")
        .collect(Collectors.joining("", "<LifecycleConfiguration>", "</LifecycleConfiguration>"));
  }

}
