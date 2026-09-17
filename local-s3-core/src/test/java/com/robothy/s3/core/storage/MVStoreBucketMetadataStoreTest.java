package com.robothy.s3.core.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.exception.InvalidBucketNameException;
import com.robothy.s3.core.model.internal.BucketChangeScope;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadataRef;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.util.JsonUtils;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;
import org.h2.mvstore.MVMap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MVStoreBucketMetadataStoreTest {

  private LocalS3Store localS3Store;

  private MetadataStore<BucketMetadata> store;

  @BeforeEach
  void setUp() {
    localS3Store = LocalS3Store.inMemory();
    store = MVStoreBucketMetadataStore.create(localS3Store);
  }

  @AfterEach
  void tearDown() {
    localS3Store.close();
  }

  @Test
  void readsBackTheSettingsObjectsAndUploadsOfABucket() {
    BucketMetadata bucket = bucket("my-bucket");
    bucket.setRegion("eu-central-1");
    bucket.setVersioningEnabled(true);
    bucket.putObjectMetadata("a.txt", object("v1", 5L, "etag-a"));
    bucket.getUploads().put("big.bin", uploadsOfKey("upload-1"));
    bucket.markUploadsChanged("big.bin");

    store.store("my-bucket", bucket);

    BucketMetadata loaded = store.fetch("my-bucket");
    assertEquals("my-bucket", loaded.getBucketName());
    assertEquals("eu-central-1", loaded.getRegion());
    assertEquals(true, loaded.getVersioningEnabled());
    assertEquals(bucket.getCreationDate(), loaded.getCreationDate());
    assertEquals(List.of("a.txt"), List.copyOf(loaded.getObjectMap().keySet()));
    assertEquals("etag-a", loaded.getObjectMap().get("a.txt").get().getVersionedObjectMap().get("v1").getEtag());
    assertEquals(List.of("upload-1"), List.copyOf(loaded.getUploads().get("big.bin").keySet()));
  }

  /**
   * A bucket that was just read has nothing left to write, so storing it again writes only its settings.
   */
  @Test
  void aFetchedBucketHasNoChangesToWrite() {
    BucketMetadata bucket = bucket("my-bucket");
    bucket.putObjectMetadata("a.txt", object("v1", 5L, "etag-a"));
    store.store("my-bucket", bucket);

    BucketMetadata loaded = store.fetch("my-bucket");
    assertEquals(List.of(), loaded.drainChangedObjectKeys());
    assertEquals(List.of(), loaded.drainChangedUploadKeys());
  }

  /**
   * The store writes the objects that changed rather than the whole bucket, which is what keeps a put into a large
   * bucket cheap. An object that the metadata never handed out within a change is left alone.
   */
  @Test
  void writesOnlyTheObjectsThatChanged() {
    BucketMetadata bucket = bucket("my-bucket");
    bucket.putObjectMetadata("a.txt", object("v1", 5L, "etag-a"));
    bucket.putObjectMetadata("b.txt", object("v1", 5L, "etag-b"));
    store.store("my-bucket", bucket);

    // Changed in memory, but not recorded as changed: the store doesn't know of it.
    bucket.getObjectMap().get("b.txt").get().getVersionedObjectMap().get("v1").setEtag("etag-b-new");
    // Recorded, like a service that changes an object within a change of its bucket does.
    bucket.putObjectMetadata("a.txt", object("v2", 7L, "etag-a-new"));
    assertEquals(List.of("a.txt"), bucket.drainChangedObjectKeys());

    bucket.putObjectMetadata("a.txt", object("v2", 7L, "etag-a-new"));
    store.store("my-bucket", bucket);

    BucketMetadata loaded = store.fetch("my-bucket");
    assertEquals("etag-a-new", loaded.getObjectMap().get("a.txt").get().getVersionedObjectMap().get("v2").getEtag());
    assertEquals("etag-b", loaded.getObjectMap().get("b.txt").get().getVersionedObjectMap().get("v1").getEtag(),
        "Only the recorded object is written.");
  }

  @Test
  void removesAnObjectAndItsUploadsFromTheStore() {
    BucketMetadata bucket = bucket("my-bucket");
    bucket.putObjectMetadata("a.txt", object("v1", 5L, "etag-a"));
    bucket.getUploads().put("a.txt", uploadsOfKey("upload-1"));
    bucket.markUploadsChanged("a.txt");
    store.store("my-bucket", bucket);

    bucket.removeObjectMetadata("a.txt");
    bucket.getUploads().remove("a.txt");
    bucket.markUploadsChanged("a.txt");
    store.store("my-bucket", bucket);

    BucketMetadata loaded = store.fetch("my-bucket");
    assertTrue(loaded.getObjectMap().isEmpty());
    assertTrue(loaded.getUploads().isEmpty());
  }

  @Test
  void marksEverythingChangedToSeedAnotherStore() {
    BucketMetadata bucket = bucket("my-bucket");
    bucket.putObjectMetadata("a.txt", object("v1", 5L, "etag-a"));
    bucket.getUploads().put("big.bin", uploadsOfKey("upload-1"));
    bucket.markUploadsChanged("big.bin");
    store.store("my-bucket", bucket);

    BucketMetadata loaded = store.fetch("my-bucket");
    loaded.markAllChanged();
    try (LocalS3Store other = LocalS3Store.inMemory()) {
      MetadataStore<BucketMetadata> copy = MVStoreBucketMetadataStore.create(other);
      copy.store("my-bucket", loaded);

      BucketMetadata copied = copy.fetch("my-bucket");
      assertEquals(List.of("a.txt"), List.copyOf(copied.getObjectMap().keySet()));
      assertEquals(List.of("big.bin"), List.copyOf(copied.getUploads().keySet()));
    }
  }

  @Test
  void deleteDropsTheBucketWithItsContents() {
    BucketMetadata bucket = bucket("my-bucket");
    bucket.putObjectMetadata("a.txt", object("v1", 5L, "etag-a"));
    store.store("my-bucket", bucket);
    assertTrue(store.exists("my-bucket"));

    store.delete("my-bucket");

    assertFalse(store.exists("my-bucket"));
    assertNull(store.fetch("my-bucket"));
    assertEquals(List.of(), store.fetchAll());

    // A bucket created again with the same name starts empty.
    store.store("my-bucket", bucket("my-bucket"));
    assertTrue(store.fetch("my-bucket").getObjectMap().isEmpty());
  }

  @Test
  void deletingABucketThatIsNotStoredFails() {
    assertThrows(IllegalStateException.class, () -> store.delete("absent"));
  }

  @Test
  void fetchesEveryStoredBucket() {
    store.store("a", bucket("a"));
    store.store("b", bucket("b"));

    assertEquals(List.of("a", "b"), store.fetchAll().stream().map(BucketMetadata::getBucketName).sorted().toList());
  }

  @Test
  void rejectsABucketNameThatIsNotOne() {
    assertThrows(InvalidBucketNameException.class, () -> store.fetch(" "));
    assertThrows(InvalidBucketNameException.class, () -> store.exists("a/b"));
    assertThrows(IllegalArgumentException.class, () -> store.store("", bucket("")));
  }

  /**
   * The services of a JVM that use the same data directory share one open store, so that the file, which MVStore
   * locks, is opened once and they read and write the same metadata.
   */
  @Test
  void sharesTheStoreOfADataDirectory(@TempDir Path dataPath) {
    try (LocalS3Store first = LocalS3Store.persistent(dataPath);
         LocalS3Store second = LocalS3Store.persistent(dataPath)) {
      assertSame(first.store(), second.store());
      MVStoreBucketMetadataStore.create(first).store("shared", bucket("shared"));
      assertNotNull(MVStoreBucketMetadataStore.create(second).fetch("shared"));
    }

    // Closed by both holders, so the file is free again, and holds what was written.
    assertTrue(Files.isRegularFile(dataPath.resolve(LocalS3Store.FILE_NAME)));
    try (LocalS3Store reopened = LocalS3Store.persistent(dataPath)) {
      assertNotNull(MVStoreBucketMetadataStore.create(reopened).fetch("shared"));
    }
  }

  @Test
  void readsADataDirectoryThatHoldsNoStore(@TempDir Path empty) {
    try (LocalS3Store store = LocalS3Store.readOnly(empty)) {
      assertEquals(List.of(), MVStoreBucketMetadataStore.create(store).fetchAll());
    }
  }

  /**
   * Opening a bucket reads the keys of its objects, not their metadata: that is what keeps opening a data directory
   * proportional to the keys it holds rather than to everything they carry.
   */
  @Test
  void fetchReadsTheKeysOfABucketWithoutTheMetadataOfItsObjects() {
    BucketMetadata bucket = bucket("my-bucket");
    for (int i = 0; i < 100; i++) {
      bucket.putObjectMetadata("key-" + i, objectMetadata("v" + i, "etag-" + i));
    }
    store.store("my-bucket", bucket);

    BucketMetadata loaded = store.fetch("my-bucket");
    assertEquals(100, loaded.getObjectMap().size(), "Every key is there.");
    assertTrue(loaded.getObjectMap().values().stream().noneMatch(ObjectMetadataRef::isLoaded),
        "None of the metadata has been read.");

    assertEquals("etag-7", loaded.getObjectMap().get("key-7").get().getVersionedObjectMap().get("v7").getEtag());
    assertEquals(1, loaded.getObjectMap().values().stream().filter(ObjectMetadataRef::isLoaded).count(),
        "Only the object that was asked for has been read.");
  }

  /**
   * A bucket records the greatest generated ID it references as it is written, so that a service which opens the
   * store can seed its ID generator without reading the metadata of every object to find the IDs in use.
   */
  @Test
  void recordsTheGreatestIdOfTheObjectsItWrites() {
    BucketMetadata bucket = bucket("my-bucket");
    assertNull(bucket.getMaxId(), "Nothing has been written yet.");

    VersionedObjectMetadata version = new VersionedObjectMetadata();
    version.setEtag("etag");
    version.setFileId(4242L);
    bucket.putObjectMetadata("a.txt", new ObjectMetadata("777", version));
    store.store("my-bucket", bucket);

    assertEquals(4242L, store.fetch("my-bucket").getMaxId(),
        "The file ID is greater than the version ID, so it is the one recorded.");

    VersionedObjectMetadata newer = new VersionedObjectMetadata();
    newer.setFileId(11L);
    bucket.putObjectMetadata("b.txt", new ObjectMetadata("9999", newer));
    store.store("my-bucket", bucket);
    assertEquals(9999L, store.fetch("my-bucket").getMaxId(), "The greatest ID seen is kept.");
  }

  /**
   * Each version of a key is a record of its own, so adding a version to a key that holds many writes that version
   * rather than every version of the key again.
   */
  @Test
  void writesOnlyTheVersionsThatChanged() {
    BucketMetadata bucket = bucket("my-bucket");
    bucket.setVersioningEnabled(true);
    ObjectMetadata object = object("1", 5L, "etag-1");
    for (int i = 2; i <= 100; i++) {
      object.putVersionedObjectMetadata(String.valueOf(i), version("etag-" + i));
    }
    bucket.putObjectMetadata("a.txt", object);
    store.store("my-bucket", bucket);

    MVMap<String, String> versions = localS3Store.store().openMap("versions/my-bucket");
    assertEquals(100, versions.size());
    MVMap<String, String> objects = localS3Store.store().openMap("objects/my-bucket");
    assertFalse(objects.get("a.txt").contains("versionedObjectMap"), "The versions are stored on their own.");

    BucketMetadata loaded = store.fetch("my-bucket");
    ObjectMetadata loadedObject = loaded.getObjectMap().get("a.txt").get();
    assertEquals(100, loadedObject.getVersionedObjectMap().size());
    assertEquals("100", loadedObject.getLatestVersion());

    changing("my-bucket", () -> {
      ObjectMetadata changed = loaded.getObjectMetadata("a.txt").orElseThrow();
      changed.putVersionedObjectMetadata("101", version("etag-101"));
      changed.removeVersionedObjectMetadata("50");
      // Another version of the key, changed in memory but not recorded, which a write of the whole key would carry.
      changed.getVersionedObjectMap().get("7").setEtag("not-written");
    });
    store.store("my-bucket", loaded);

    assertEquals(100, versions.size());
    assertNull(versions.get("a.txt\0" + "50"));
    ObjectMetadata reread = store.fetch("my-bucket").getObjectMap().get("a.txt").get();
    assertEquals("101", reread.getLatestVersion());
    assertEquals("etag-101", reread.getLatest().getEtag());
    assertEquals("etag-7", reread.getVersionedObjectMap().get("7").getEtag(), "Only the changed versions are written.");
  }

  /**
   * A key whose name starts with another key and the separator keeps its versions apart from that key's.
   */
  @Test
  void keepsTheVersionsOfKeysThatShareAPrefixApart() {
    BucketMetadata bucket = bucket("my-bucket");
    bucket.putObjectMetadata("a", object("1", 1L, "etag-a"));
    bucket.putObjectMetadata("a\0b", object("2", 1L, "etag-ab"));
    store.store("my-bucket", bucket);

    BucketMetadata loaded = store.fetch("my-bucket");
    assertEquals(List.of("1"), List.copyOf(loaded.getObjectMap().get("a").get().getVersionedObjectMap().keySet()));

    loaded.removeObjectMetadata("a");
    store.store("my-bucket", loaded);
    assertEquals("etag-ab", store.fetch("my-bucket").getObjectMap().get("a\0b").get().getLatest().getEtag());
  }

  /**
   * A store written before the versions had a map of their own holds every version of a key with the key, which is
   * still read, and is moved to the versions map the first time the key is written.
   */
  @Test
  void readsAndRewritesAKeyThatHoldsItsVersions() {
    store.store("my-bucket", bucket("my-bucket"));
    ObjectMetadata legacy = object("1", 5L, "etag-1");
    legacy.putVersionedObjectMetadata("2", version("etag-2"));
    MVMap<String, String> objects = localS3Store.store().openMap("objects/my-bucket");
    objects.put("a.txt", JsonUtils.toJson(legacy));

    BucketMetadata loaded = store.fetch("my-bucket");
    ObjectMetadata object = loaded.getObjectMap().get("a.txt").get();
    assertEquals(List.of("2", "1"), List.copyOf(object.getVersionedObjectMap().keySet()));
    assertArrayEquals(new long[] {1L}, MVStoreBucketMetadataStore.referencedContentIds(
        MVStoreBucketMetadataStore.contentReferencingMaps(localS3Store.store()), () -> false));

    changing("my-bucket", () -> loaded.getObjectMetadata("a.txt").orElseThrow()
        .putVersionedObjectMetadata("3", version("etag-3")));
    store.store("my-bucket", loaded);

    assertFalse(objects.get("a.txt").contains("versionedObjectMap"));
    assertEquals(3, localS3Store.store().openMap("versions/my-bucket").size(), "Every version is moved.");
    ObjectMetadata reread = store.fetch("my-bucket").getObjectMap().get("a.txt").get();
    assertEquals(List.of("3", "2", "1"), List.copyOf(reread.getVersionedObjectMap().keySet()));
  }

  private static void changing(String bucketName, Runnable change) {
    BucketChangeScope.begin(bucketName);
    try {
      change.run();
    } finally {
      BucketChangeScope.end(bucketName);
    }
  }

  private static VersionedObjectMetadata version(String etag) {
    VersionedObjectMetadata version = new VersionedObjectMetadata();
    version.setEtag(etag);
    version.setSize(1L);
    return version;
  }

  private static ObjectMetadata objectMetadata(String versionId, String etag) {
    VersionedObjectMetadata version = new VersionedObjectMetadata();
    version.setEtag(etag);
    version.setSize(1L);
    return new ObjectMetadata(versionId, version);
  }

  private static BucketMetadata bucket(String name) {
    BucketMetadata bucketMetadata = new BucketMetadata();
    bucketMetadata.setBucketName(name);
    bucketMetadata.setCreationDate(System.currentTimeMillis());
    return bucketMetadata;
  }

  private static ObjectMetadata object(String versionId, long size, String etag) {
    VersionedObjectMetadata version = new VersionedObjectMetadata();
    version.setCreationDate(System.currentTimeMillis());
    version.setFileId(1L);
    version.setSize(size);
    version.setEtag(etag);
    return new ObjectMetadata(versionId, version);
  }

  private static ConcurrentSkipListMap<String, UploadMetadata> uploadsOfKey(String uploadId) {
    ConcurrentSkipListMap<String, UploadMetadata> uploads = new ConcurrentSkipListMap<>();
    uploads.put(uploadId, UploadMetadata.builder()
        .contentType("text/plain")
        .createDate(System.currentTimeMillis())
        .build());
    return uploads;
  }

}
