package com.robothy.s3.core.service;

import static com.robothy.s3.core.model.IdentifiedBucketConfiguration.METRICS;
import static com.robothy.s3.core.model.StoredBucketConfiguration.ACCELERATE;
import static com.robothy.s3.core.model.StoredBucketConfiguration.LOGGING;
import static com.robothy.s3.core.model.StoredBucketConfiguration.OWNERSHIP_CONTROLS;
import static com.robothy.s3.core.model.StoredBucketConfiguration.REQUEST_PAYMENT;
import static com.robothy.s3.core.model.StoredBucketConfiguration.WEBSITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.TestFiles;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.exception.LocalS3Exception;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.IdentifiedBucketConfiguration;
import com.robothy.s3.core.model.StoredBucketConfiguration;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class BucketStoredConfigurationServiceTest extends LocalS3ServiceTestBase {

  private static final String BUCKET = "configured-bucket";

  private static final String WEBSITE_CONFIGURATION = """
      <WebsiteConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
        <IndexDocument><Suffix>index.html</Suffix></IndexDocument>
      </WebsiteConfiguration>""";

  private static final String OBJECT_WRITER = """
      <OwnershipControls xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
        <Rule><ObjectOwnership>ObjectWriter</ObjectOwnership></Rule>
      </OwnershipControls>""";

  @ParameterizedTest
  @MethodSource("bucketServices")
  void answersTheDefaultsOfANewBucket(BucketService bucketService) {
    bucketService.createBucket(BUCKET);

    assertEquals(ACCELERATE.defaultConfiguration().orElseThrow(),
        bucketService.getBucketConfiguration(BUCKET, ACCELERATE));
    assertEquals(LOGGING.defaultConfiguration().orElseThrow(), bucketService.getBucketConfiguration(BUCKET, LOGGING));
    assertTrue(bucketService.getBucketConfiguration(BUCKET, REQUEST_PAYMENT).contains("<Payer>BucketOwner</Payer>"));
    assertTrue(bucketService.getBucketConfiguration(BUCKET, OWNERSHIP_CONTROLS)
        .contains("<ObjectOwnership>BucketOwnerEnforced</ObjectOwnership>"));
    assertError(S3ErrorCode.NoSuchWebsiteConfiguration, () -> bucketService.getBucketConfiguration(BUCKET, WEBSITE));
  }

  @ParameterizedTest
  @MethodSource("bucketServices")
  void putsGetsAndDeletesTheWebsite(BucketService bucketService) {
    bucketService.createBucket(BUCKET);

    bucketService.putBucketConfiguration(BUCKET, WEBSITE, WEBSITE_CONFIGURATION);
    assertEquals(WEBSITE_CONFIGURATION, bucketService.getBucketConfiguration(BUCKET, WEBSITE));

    bucketService.deleteBucketConfiguration(BUCKET, WEBSITE);
    assertError(S3ErrorCode.NoSuchWebsiteConfiguration, () -> bucketService.getBucketConfiguration(BUCKET, WEBSITE));
    // Deleting a configuration the bucket doesn't have succeeds.
    bucketService.deleteBucketConfiguration(BUCKET, WEBSITE);
  }

  @ParameterizedTest
  @MethodSource("bucketServices")
  void deletedOwnershipControlsDontFallBackToTheDefault(BucketService bucketService) {
    bucketService.createBucket(BUCKET);

    bucketService.putBucketConfiguration(BUCKET, OWNERSHIP_CONTROLS, OBJECT_WRITER);
    assertEquals(OBJECT_WRITER, bucketService.getBucketConfiguration(BUCKET, OWNERSHIP_CONTROLS));

    bucketService.deleteBucketConfiguration(BUCKET, OWNERSHIP_CONTROLS);
    assertError(S3ErrorCode.OwnershipControlsNotFoundError,
        () -> bucketService.getBucketConfiguration(BUCKET, OWNERSHIP_CONTROLS));

    bucketService.putBucketConfiguration(BUCKET, OWNERSHIP_CONTROLS, OBJECT_WRITER);
    assertEquals(OBJECT_WRITER, bucketService.getBucketConfiguration(BUCKET, OWNERSHIP_CONTROLS));
  }

  @ParameterizedTest
  @EnumSource(StoredBucketConfiguration.class)
  void rejectsMalformedConfigurationsAndMissingBuckets(StoredBucketConfiguration type) {
    BucketService bucketService = LocalS3Manager.createInMemoryS3Manager().bucketService();
    bucketService.createBucket(BUCKET);
    String configuration = "<" + type.rootElement() + "/>";
    bucketService.putBucketConfiguration(BUCKET, type, configuration);

    assertError(S3ErrorCode.MalformedXML, () -> bucketService.putBucketConfiguration(BUCKET, type, ""));
    assertError(S3ErrorCode.MalformedXML,
        () -> bucketService.putBucketConfiguration(BUCKET, type, "<NotificationConfiguration/>"));
    // A rejected configuration leaves the previous one in place.
    assertEquals(configuration, bucketService.getBucketConfiguration(BUCKET, type));

    assertThrows(BucketNotExistException.class, () -> bucketService.getBucketConfiguration("no-such-bucket", type));
    assertThrows(BucketNotExistException.class,
        () -> bucketService.putBucketConfiguration("no-such-bucket", type, configuration));
  }

  @ParameterizedTest
  @EnumSource(IdentifiedBucketConfiguration.class)
  void putsGetsListsAndDeletesConfigurationsById(IdentifiedBucketConfiguration type) {
    BucketService bucketService = LocalS3Manager.createInMemoryS3Manager().bucketService();
    bucketService.createBucket(BUCKET);
    String second = identified(type, "second");
    String first = identified(type, "first");

    assertEquals(List.of(), bucketService.listBucketConfigurations(BUCKET, type));
    assertError(S3ErrorCode.NoSuchConfiguration, () -> bucketService.getBucketConfiguration(BUCKET, type, "first"));

    bucketService.putBucketConfiguration(BUCKET, type, "second", second);
    bucketService.putBucketConfiguration(BUCKET, type, "first", first);
    assertEquals(first, bucketService.getBucketConfiguration(BUCKET, type, "first"));
    // Listed in the order of their IDs.
    assertEquals(List.of(first, second), bucketService.listBucketConfigurations(BUCKET, type));

    bucketService.deleteBucketConfiguration(BUCKET, type, "first");
    assertError(S3ErrorCode.NoSuchConfiguration, () -> bucketService.getBucketConfiguration(BUCKET, type, "first"));
    assertEquals(List.of(second), bucketService.listBucketConfigurations(BUCKET, type));
    // Unlike the configurations a bucket has one of, deleting one that doesn't exist fails, like on Amazon S3.
    assertError(S3ErrorCode.NoSuchConfiguration,
        () -> bucketService.deleteBucketConfiguration(BUCKET, type, "first"));
  }

  @ParameterizedTest
  @EnumSource(IdentifiedBucketConfiguration.class)
  void rejectsConfigurationsWhoseIdDoesntMatch(IdentifiedBucketConfiguration type) {
    BucketService bucketService = LocalS3Manager.createInMemoryS3Manager().bucketService();
    bucketService.createBucket(BUCKET);

    assertError(S3ErrorCode.InvalidArgument,
        () -> bucketService.putBucketConfiguration(BUCKET, type, "one", identified(type, "other")));
    assertError(S3ErrorCode.InvalidArgument,
        () -> bucketService.putBucketConfiguration(BUCKET, type, null, identified(type, "one")));
    assertError(S3ErrorCode.MalformedXML,
        () -> bucketService.putBucketConfiguration(BUCKET, type, "one", "<" + type.rootElement() + "/>"));
    assertError(S3ErrorCode.MalformedXML,
        () -> bucketService.putBucketConfiguration(BUCKET, type, "one", "<NotificationConfiguration/>"));
    assertEquals(List.of(), bucketService.listBucketConfigurations(BUCKET, type));
    assertThrows(BucketNotExistException.class,
        () -> bucketService.listBucketConfigurations("no-such-bucket", type));
  }

  @Test
  void persistsTheConfigurations() throws IOException {
    Path dataPath = Files.createTempDirectory("local-s3");
    try {
      LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
      manager.bucketService().createBucket(BUCKET);
      manager.bucketService().putBucketConfiguration(BUCKET, WEBSITE, WEBSITE_CONFIGURATION);
      manager.bucketService().deleteBucketConfiguration(BUCKET, OWNERSHIP_CONTROLS);
      manager.bucketService().putBucketConfiguration(BUCKET, METRICS, "all", identified(METRICS, "all"));
      manager.close();

      LocalS3Manager restarted = LocalS3Manager.createFileSystemS3Manager(dataPath);
      try {
        assertEquals(WEBSITE_CONFIGURATION, restarted.bucketService().getBucketConfiguration(BUCKET, WEBSITE));
        assertError(S3ErrorCode.OwnershipControlsNotFoundError,
            () -> restarted.bucketService().getBucketConfiguration(BUCKET, OWNERSHIP_CONTROLS));
        assertEquals(List.of(identified(METRICS, "all")),
            restarted.bucketService().listBucketConfigurations(BUCKET, METRICS));
      } finally {
        restarted.close();
      }
    } finally {
      TestFiles.deleteDirectory(dataPath);
    }
  }

  private static String identified(IdentifiedBucketConfiguration type, String id) {
    return "<" + type.rootElement() + " xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\"><Id>" + id + "</Id></"
        + type.rootElement() + ">";
  }

  private static void assertError(S3ErrorCode expected, Runnable request) {
    LocalS3Exception thrown = assertThrows(LocalS3Exception.class, request::run);
    assertEquals(expected, thrown.getS3ErrorCode());
  }

}
