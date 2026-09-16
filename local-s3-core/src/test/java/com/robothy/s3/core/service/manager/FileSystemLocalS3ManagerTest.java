package com.robothy.s3.core.service.manager;

import com.robothy.s3.core.exception.BucketTaggingNotExistException;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadataCache;
import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.storage.MVStoreBucketMetadataStore;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.storage.PersistencePolicy;
import com.robothy.s3.core.util.JsonUtils;
import java.util.ArrayList;
import com.robothy.s3.datatypes.response.ObjectVersion;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.exception.UploadNotExistException;
import com.robothy.s3.core.model.request.CreateMultipartUploadOptions;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.model.request.UploadPartOptions;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import com.robothy.s3.core.TestFiles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FileSystemLocalS3ManagerTest {

  private static final String BUCKET = "my-bucket";

  private static final String KEY = "a.txt";

  private Path dataPath;

  @BeforeEach
  void setUp() throws IOException {
    dataPath = Files.createTempDirectory("local-s3");
  }

  @AfterEach
  void tearDown() throws IOException {
    dataPath.toFile().setWritable(true);
    TestFiles.deleteDirectory(dataPath);
  }

  @Test
  void cachesServices() {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);

    assertSame(manager.bucketService(), manager.bucketService());
    assertSame(manager.objectService(), manager.objectService());
  }

  /**
   * The tagging of a bucket is part of the metadata of the bucket, which a restarted service loads.
   */
  @Test
  void persistsTheTaggingOfABucket() {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    manager.bucketService().createBucket("tagged");
    manager.bucketService().createBucket("untagged");
    manager.bucketService().putTagging("tagged", List.of(Map.of("team", "s3")));
    manager.bucketService().putTagging("untagged", List.of(Map.of("team", "s3")));
    manager.bucketService().deleteTagging("untagged");

    LocalS3Manager restarted = LocalS3Manager.createFileSystemS3Manager(dataPath);
    assertEquals(List.of(Map.of("team", "s3")),
        List.copyOf(restarted.bucketService().getTagging("tagged")));
    assertThrows(BucketTaggingNotExistException.class,
        () -> restarted.bucketService().getTagging("untagged"));
  }

  /**
   * Overwriting an object of a non-versioned bucket deletes the content of the version it replaces, and only once the
   * metadata that no longer references it is persisted, so that the metadata on the disk and the object files it
   * references stay consistent. A change whose persistence fails is rolled back instead, which
   * {@code DefaultBucketGuardTest.rollsBackAChangeWhosePersistenceFails} covers.
   */
  @Test
  void overwritingAnObjectKeepsMemoryStorageAndDiskConsistent() throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);
    putObject(objectService, "v1");
    assertEquals(1, storedObjects().size());

    putObject(objectService, "v2");
    assertEquals(1, storedObjects().size(), "v1 is deleted once v2 is persisted.");
    assertEquals("v2", getObject(objectService));
    assertEquals("v2", getObject(LocalS3Manager.createFileSystemS3Manager(dataPath).objectService()));
  }

  @Test
  void discardsStoredContentWhenBucketIsDeletedWhileStoring() throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    BucketService bucketService = manager.bucketService();
    bucketService.createBucket(BUCKET);

    // The bucket isn't locked while the content is stored, so it can be deleted meanwhile.
    InputStream content = onFirstRead("v1", () -> bucketService.deleteBucket(BUCKET));
    assertThrows(BucketNotExistException.class, () -> manager.objectService().putObject(BUCKET, KEY,
        PutObjectOptions.builder().content(content).contentType("text/plain").size(2L).build()));

    assertEquals(Set.of(), storedObjects());
  }

  @Test
  void discardsStoredPartWhenUploadIsAbortedWhileStoring() throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);
    String uploadId = objectService.createMultipartUpload(BUCKET, KEY,
        CreateMultipartUploadOptions.builder().contentType("text/plain").build());

    InputStream data = onFirstRead("part", () -> objectService.abortMultipartUpload(BUCKET, KEY, uploadId));
    assertThrows(UploadNotExistException.class, () -> objectService.uploadPart(BUCKET, KEY, uploadId, 1,
        UploadPartOptions.builder().contentLength(4).data(data).build()));

    assertEquals(Set.of(), storedObjects());
  }

  /**
   * A stream that runs {@code action} when it is first read.
   */
  private static InputStream onFirstRead(String content, Runnable action) {
    return new FilterInputStream(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8))) {

      private boolean started;

      @Override
      public int read() throws IOException {
        start();
        return super.read();
      }

      @Override
      public int read(byte[] b, int off, int len) throws IOException {
        start();
        return super.read(b, off, len);
      }

      private void start() {
        if (!started) {
          started = true;
          action.run();
        }
      }
    };
  }

  /**
   * The store writes the objects that a change touched, which a service reaches through the metadata of its bucket and
   * then changes in place, e.g. the tagging of a version. A restarted service has to see every such change, so this
   * walks the changes that don't replace the object of a key.
   */
  @Test
  void persistsTheChangesMadeInsideAnObject() throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);
    manager.bucketService().setVersioningEnabled(BUCKET, true);

    String firstVersion = objectService.putObject(BUCKET, KEY, PutObjectOptions.builder()
        .content(new ByteArrayInputStream("v1".getBytes(StandardCharsets.UTF_8)))
        .contentType("text/plain")
        .size(2L)
        .build()).getVersionId();
    // A second version is added to the object that the key already holds, rather than replacing it.
    putObject(objectService, "v2");
    // Both change a version in place.
    objectService.putObjectTagging(BUCKET, KEY, firstVersion, new String[][] {{"team", "s3"}});

    String uploadId = objectService.createMultipartUpload(BUCKET, KEY + ".part",
        CreateMultipartUploadOptions.builder().contentType("text/plain").build());

    LocalS3Manager restarted = LocalS3Manager.createFileSystemS3Manager(dataPath);
    ObjectService restartedObjects = restarted.objectService();
    assertEquals("v2", getObject(restartedObjects));
    assertEquals(2, restartedObjects.listObjectVersions(BUCKET, null, null, 10, null, null).getVersions().size());
    assertEquals("team", restartedObjects.getObjectTagging(BUCKET, KEY, firstVersion).getTagging()[0][0],
        "The tagging set on a version of a loaded object is persisted.");
    assertEquals(List.of(uploadId), restartedObjects.listMultipartUploads(BUCKET, null, null, null, 10, null, null)
        .getUploads().stream().map(upload -> upload.getUploadId()).toList());

    // Aborting the upload, and deleting a version, are persisted too.
    restartedObjects.abortMultipartUpload(BUCKET, KEY + ".part", uploadId);
    restartedObjects.deleteObject(BUCKET, KEY, firstVersion);

    LocalS3Manager restartedAgain = LocalS3Manager.createFileSystemS3Manager(dataPath);
    assertEquals(1, restartedAgain.objectService()
        .listObjectVersions(BUCKET, null, null, 10, null, null).getVersions().size());
    assertTrue(restartedAgain.objectService().listMultipartUploads(BUCKET, null, null, null, 10, null, null)
        .getUploads().isEmpty());
  }

  /**
   * A service holds the metadata of the objects it serves, not of every object of its data directory: listing and
   * reading a bucket of many more objects than the bound keeps working, and answers the same as one that holds them
   * all. Listing is where it matters, since a listing walks keys rather than looking one up.
   */
  @Test
  void servesABucketOfMoreObjectsThanItKeepsInHeap() {
    int objects = 400;
    withObjectMetadataCacheOf(20, () -> {
      LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
      ObjectService objectService = manager.objectService();
      manager.bucketService().createBucket(BUCKET);
      for (int i = 0; i < objects; i++) {
        putObject(objectService, String.format("logs/%03d.txt", i), "content-" + i);
      }
      putObject(objectService, "a.txt", "a");
      putObject(objectService, "z.txt", "z");
      manager.close();

      LocalS3Manager restarted = LocalS3Manager.createFileSystemS3Manager(dataPath);
      ObjectService restartedObjects = restarted.objectService();

      // A listing of every key, paged the way a client pages it.
      List<String> listed = new ArrayList<>();
      String marker = null;
      do {
        var page = restartedObjects.listObjects(BUCKET, null, null, marker, 50, null);
        page.getObjects().forEach(object -> listed.add(object.getKey()));
        marker = page.getNextMarker().orElse(null);
      } while (marker != null);

      List<String> expected = new ArrayList<>();
      expected.add("a.txt");
      for (int i = 0; i < objects; i++) {
        expected.add(String.format("logs/%03d.txt", i));
      }
      expected.add("z.txt");
      assertEquals(expected, listed, "Every key is listed once, in order, however little is held in heap.");

      // The rolled up listing takes the same route through the keys.
      var rolledUp = restartedObjects.listObjects(BUCKET, "/", null, null, 10, null);
      assertEquals(List.of("a.txt", "z.txt"),
          rolledUp.getObjects().stream().map(object -> object.getKey()).toList());
      assertEquals(List.of("logs/"), rolledUp.getCommonPrefixes());

      // The content of an object is still served, whatever was evicted.
      assertEquals("content-377", getObject(restartedObjects, "logs/377.txt"));

      ObjectStatistics statistics = restarted.statistics();
      assertEquals(objects + 2, statistics.objects(), "Every object is counted.");
      assertTrue(statistics.loadedObjects() <= 20,
          "Holds the metadata of " + statistics.loadedObjects() + " objects, more than the bound of 20.");
      assertTrue(statistics.loadedObjectMetadataBytes() > 0,
          "The metadata that is held is measured.");
      restarted.close();
      return null;
    });
  }

  /**
   * Storing objects doesn't fill the heap either: the references created while the service runs are handed to the
   * store, which gives them something to be read back from, so they fall under the bound like the loaded ones.
   */
  @Test
  void storingManyObjectsDoesNotFillTheHeap() {
    withObjectMetadataCacheOf(20, () -> {
      LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
      manager.bucketService().createBucket(BUCKET);
      for (int i = 0; i < 300; i++) {
        putObject(manager.objectService(), "key-" + i, "content-" + i);
      }

      ObjectStatistics statistics = manager.statistics();
      assertEquals(300, statistics.objects());
      assertTrue(statistics.loadedObjects() <= 20,
          "Holds the metadata of " + statistics.loadedObjects() + " objects it stored, more than the bound of 20.");
      manager.close();
      return null;
    });
  }

  /**
   * A bucket records the greatest generated ID it references as it is written, which is what a service that opens the
   * data directory seeds its ID generator from instead of reading the metadata of every object to find the IDs in
   * use. That the seeding then works is covered by {@code DefaultFileSystemS3MetadataLoaderTest}, which needs a fresh
   * generator to show it and so can't be asserted here: the generator is the one of the JVM, and within one JVM it
   * has already passed the IDs that this test writes.
   */
  @Test
  void recordsTheGreatestIdItReferences() {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    manager.bucketService().createBucket(BUCKET);
    manager.bucketService().setVersioningEnabled(BUCKET, true);
    putObject(manager.objectService(), KEY, "v1");
    String version = ((ObjectVersion) manager.objectService()
        .listObjectVersions(BUCKET, null, null, 10, null, null).getVersions().get(0)).getVersionId();
    manager.close();

    LocalS3Manager restarted = LocalS3Manager.createFileSystemS3Manager(dataPath);
    Long recorded = restarted.bucketService().localS3Metadata().getBucketMetadata(BUCKET).orElseThrow().getMaxId();
    assertNotNull(recorded, "The bucket records the greatest ID it references.");
    assertTrue(recorded >= Long.parseLong(version),
        "Recorded " + recorded + ", which is below the version ID " + version + " that the bucket holds.");
    assertEquals("v1", getObject(restarted.objectService(), KEY));
    restarted.close();
  }

  /**
   * A FAST service doesn't commit every change, so what a killed process would lose is the last second of them. A
   * service that is shut down persists everything: closing its store writes what is left.
   */
  @Test
  void aFastServicePersistsEverythingWhenItIsClosed() {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(dataPath, PersistencePolicy.FAST);
    manager.bucketService().createBucket(BUCKET);
    manager.bucketService().setVersioningEnabled(BUCKET, true);
    for (int i = 0; i < 500; i++) {
      putObject(manager.objectService(), "key-" + i, "content-" + i);
    }
    putObject(manager.objectService(), KEY, "v1");
    putObject(manager.objectService(), KEY, "v2");
    manager.objectService().putObjectTagging(BUCKET, KEY, null, new String[][] {{"team", "s3"}});
    manager.close();

    LocalS3Manager restarted = LocalS3Manager.createFileSystemS3Manager(dataPath, PersistencePolicy.FAST);
    assertEquals(501, restarted.statistics().objects(), "Every object written before the shutdown is there.");
    assertEquals("content-499", getObject(restarted.objectService(), "key-499"));
    assertEquals("v2", getObject(restarted.objectService(), KEY));
    assertEquals(2, restarted.objectService()
        .listObjectVersions(BUCKET, null, null, 10, KEY, null).getVersions().size());
    assertEquals("team", restarted.objectService().getObjectTagging(BUCKET, KEY, null).getTagging()[0][0]);
    restarted.close();
  }

  /**
   * A commit appends a chunk to the file of the data directory, so committing every change leaves one per change. A
   * FAST service commits them together instead, which is what keeps the file of a bulk load close to the metadata it
   * holds rather than to the number of writes that built it.
   */
  @Test
  void aFastServiceWritesFarLessWhileItLoads() throws IOException {
    Path durablePath = dataPath.resolve("durable");
    Path fastPath = dataPath.resolve("fast");
    long durableSize = loadAndMeasure(durablePath, PersistencePolicy.DURABLE);
    long fastSize = loadAndMeasure(fastPath, PersistencePolicy.FAST);

    assertTrue(fastSize * 4 < durableSize,
        "The FAST load wrote " + fastSize + " bytes and the DURABLE one " + durableSize + ".");
  }

  /**
   * Load a data directory and answer how large its file grew while the service ran, i.e. before it was closed and
   * the file compacted.
   */
  private static long loadAndMeasure(Path path, PersistencePolicy policy) throws IOException {
    LocalS3Manager manager = LocalS3Manager.createFileSystemS3Manager(path, policy);
    manager.bucketService().createBucket(BUCKET);
    for (int i = 0; i < 2_000; i++) {
      putObject(manager.objectService(), String.format("data/part-%05d.parquet", i), "content-" + i);
    }
    long whileRunning = Files.size(path.resolve(LocalS3Store.FILE_NAME));
    manager.close();
    return whileRunning;
  }

  /**
   * Run an action with a given bound on the object metadata that a new service keeps in heap. The bound is read when
   * a service opens its store, so it is set around the whole action.
   */
  private static <T> T withObjectMetadataCacheOf(int maxEntries, java.util.function.Supplier<T> action) {
    String previous = System.getProperty(ObjectMetadataCache.MAX_ENTRIES_VARIABLE);
    System.setProperty(ObjectMetadataCache.MAX_ENTRIES_VARIABLE, String.valueOf(maxEntries));
    try {
      return action.get();
    } finally {
      if (previous == null) {
        System.clearProperty(ObjectMetadataCache.MAX_ENTRIES_VARIABLE);
      } else {
        System.setProperty(ObjectMetadataCache.MAX_ENTRIES_VARIABLE, previous);
      }
    }
  }

  private static void putObject(ObjectService objectService, String key, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    objectService.putObject(BUCKET, key, PutObjectOptions.builder()
        .content(new ByteArrayInputStream(bytes))
        .contentType("text/plain")
        .size((long) bytes.length)
        .build());
  }

  private static String getObject(ObjectService objectService, String key) {
    try (InputStream content = objectService.getObject(BUCKET, key, GetObjectOptions.builder().build()).getContent()) {
      return new String(content.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }


  private static void putObject(ObjectService objectService, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    objectService.putObject(BUCKET, KEY, PutObjectOptions.builder()
        .content(new ByteArrayInputStream(bytes))
        .contentType("text/plain")
        .size((long) bytes.length)
        .build());
  }

  private static String getObject(ObjectService objectService) throws IOException {
    return new String(objectService.getObject(BUCKET, KEY, GetObjectOptions.builder().build())
        .getContent().readAllBytes(), StandardCharsets.UTF_8);
  }

  private Set<Path> storedObjects() throws IOException {
    // The object files are spread over subdirectories.
    try (Stream<Path> files = Files.walk(dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY))) {
      return files.filter(Files::isRegularFile).collect(Collectors.toSet());
    }
  }

}
