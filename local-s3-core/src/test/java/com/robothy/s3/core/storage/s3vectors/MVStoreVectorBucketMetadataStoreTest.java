package com.robothy.s3.core.storage.s3vectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorIndexMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.util.JsonUtils;
import com.robothy.s3.datatypes.s3vectors.DistanceMetric;
import com.robothy.s3.datatypes.s3vectors.VectorDataType;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;

class MVStoreVectorBucketMetadataStoreTest {

  private static final String BUCKET = "my-vectors";

  private static final String INDEX = "my-index";

  /**
   * A value that no write of the store produces, put in place of a stored vector to tell whether the store wrote the
   * vector again.
   */
  private static final String UNTOUCHED = "{\"vectorId\":\"untouched\",\"dimension\":2}";

  private LocalS3Store localS3Store;

  private MetadataStore<VectorBucketMetadata> store;

  @BeforeEach
  void setUp() {
    localS3Store = LocalS3Store.inMemory();
    store = MVStoreVectorBucketMetadataStore.create(localS3Store);
  }

  @AfterEach
  void tearDown() {
    localS3Store.close();
  }

  @Test
  void readsBackTheSettingsIndexesAndVectorsOfABucket() {
    VectorBucketMetadata bucket = bucket(BUCKET);
    bucket.setPolicy("{\"Version\":\"2012-10-17\"}");
    VectorIndexMetadata index = index(INDEX);
    index.setNonFilterableMetadataKeys(List.of("text"));
    VectorObjectMetadata a = vector("a", 1L);
    a.setMetadata(JsonNodeFactory.instance.objectNode().put("genre", "drama"));
    index.addVectorObject(a);
    index.addVectorObject(vector("b", 2L));
    bucket.putIndexMetadata(INDEX, index);

    store.store(BUCKET, bucket);

    VectorBucketMetadata loaded = store.fetch(BUCKET);
    assertEquals(BUCKET, loaded.getVectorBucketName());
    assertEquals(bucket.getCreationDate(), loaded.getCreationDate());
    assertEquals("{\"Version\":\"2012-10-17\"}", loaded.getPolicy().orElseThrow());
    VectorIndexMetadata loadedIndex = loaded.getIndexMetadata(INDEX).orElseThrow();
    assertEquals(2, loadedIndex.getDimension());
    assertEquals(DistanceMetric.COSINE, loadedIndex.getDistanceMetric());
    assertTrue(loadedIndex.isActive());
    assertEquals(List.of("text"), loadedIndex.getNonFilterableMetadataKeys());
    assertEquals(Set.of("a", "b"), loadedIndex.getVectorObjects().keySet());
    assertEquals(a, loadedIndex.getVectorObject("a"));
  }

  /**
   * The settings of a bucket and of an index are written without the vectors, which are written one record each.
   */
  @Test
  void spreadsABucketOverItsMaps() {
    VectorBucketMetadata bucket = bucket(BUCKET);
    VectorIndexMetadata index = index(INDEX);
    index.addVectorObject(vector("a", 1L));
    bucket.putIndexMetadata(INDEX, index);

    store.store(BUCKET, bucket);

    MVStore mvStore = localS3Store.store();
    assertFalse(mvStore.<String, String>openMap(MVStoreVectorBucketMetadataStore.VECTOR_BUCKETS_MAP).get(BUCKET)
        .contains("indexes"));
    String indexJson = indexes(BUCKET).get(INDEX);
    assertNotNull(indexJson);
    assertFalse(indexJson.contains("vectorObjects"));
    assertEquals(Set.of("a"), vectors(BUCKET, INDEX).keySet());
  }

  /**
   * A change of a vector writes that vector, rather than the whole bucket, which is what keeps a put into a large index
   * cheap. A vector that no change touched is left alone.
   */
  @Test
  void writesOnlyTheVectorsThatChanged() {
    VectorBucketMetadata bucket = bucket(BUCKET);
    VectorIndexMetadata index = index(INDEX);
    index.addVectorObject(vector("a", 1L));
    index.addVectorObject(vector("b", 2L));
    index.addVectorObject(vector("c", 3L));
    bucket.putIndexMetadata(INDEX, index);
    store.store(BUCKET, bucket);
    vectors(BUCKET, INDEX).put("b", UNTOUCHED);

    index.addVectorObject(vector("a", 10L));
    index.addVectorObject(vector("d", 4L));
    index.removeVectorObject("c");
    store.store(BUCKET, bucket);

    MVMap<String, String> vectors = vectors(BUCKET, INDEX);
    assertEquals(Set.of("a", "b", "d"), vectors.keySet());
    assertEquals(UNTOUCHED, vectors.get("b"));
    assertEquals(10L, JsonUtils.fromJson(vectors.get("a"), VectorObjectMetadata.class).getStorageId());
    assertEquals(4L, JsonUtils.fromJson(vectors.get("d"), VectorObjectMetadata.class).getStorageId());
  }

  /**
   * A bucket that was read from a store tracks its changes from there, so its first change after a restart writes
   * that change only, not the whole bucket.
   */
  @Test
  void aFetchedBucketWritesOnlyItsChanges(@TempDir Path dataPath) {
    try (LocalS3Store persistent = LocalS3Store.persistent(dataPath)) {
      VectorBucketMetadata bucket = bucket(BUCKET);
      VectorIndexMetadata index = index(INDEX);
      index.addVectorObject(vector("a", 1L));
      index.addVectorObject(vector("b", 2L));
      bucket.putIndexMetadata(INDEX, index);
      MVStoreVectorBucketMetadataStore.create(persistent).store(BUCKET, bucket);
    }

    try (LocalS3Store persistent = LocalS3Store.persistent(dataPath)) {
      MetadataStore<VectorBucketMetadata> reopened = MVStoreVectorBucketMetadataStore.create(persistent);
      VectorBucketMetadata loaded = reopened.fetch(BUCKET);
      VectorIndexMetadata index = loaded.getIndexMetadata(INDEX).orElseThrow();
      assertTrue(index.tracksChanges());
      assertEquals(List.of(), index.drainChangedVectorIds());

      persistent.store().<String, String>openMap(vectorsMapName(BUCKET, INDEX)).put("b", UNTOUCHED);
      index.addVectorObject(vector("c", 3L));
      reopened.store(BUCKET, loaded);

      MVMap<String, String> vectors = persistent.store().openMap(vectorsMapName(BUCKET, INDEX));
      assertEquals(Set.of("a", "b", "c"), vectors.keySet());
      assertEquals(UNTOUCHED, vectors.get("b"));
    }

    try (LocalS3Store persistent = LocalS3Store.persistent(dataPath)) {
      persistent.store().<String, String>openMap(vectorsMapName(BUCKET, INDEX)).remove("b");
      VectorIndexMetadata index = MVStoreVectorBucketMetadataStore.create(persistent).fetch(BUCKET)
          .getIndexMetadata(INDEX).orElseThrow();
      assertEquals(Set.of("a", "c"), index.getVectorObjects().keySet());
    }
  }

  /**
   * An index whose vectors were changed as a whole, e.g. cleared, is written whole.
   */
  @Test
  void writesAllVectorsOfAnIndexThatWasCleared() {
    VectorBucketMetadata bucket = bucket(BUCKET);
    VectorIndexMetadata index = index(INDEX);
    index.addVectorObject(vector("a", 1L));
    index.addVectorObject(vector("b", 2L));
    bucket.putIndexMetadata(INDEX, index);
    store.store(BUCKET, bucket);

    index.clearVectorObjects();
    index.addVectorObject(vector("c", 3L));
    store.store(BUCKET, bucket);

    assertEquals(Set.of("c"), vectors(BUCKET, INDEX).keySet());
  }

  @Test
  void deletesTheVectorsOfAnIndexThatWasRemoved() {
    VectorBucketMetadata bucket = bucket(BUCKET);
    VectorIndexMetadata index = index(INDEX);
    index.addVectorObject(vector("a", 1L));
    bucket.putIndexMetadata(INDEX, index);
    bucket.putIndexMetadata("other", index("other"));
    store.store(BUCKET, bucket);

    bucket.removeIndexMetadata(INDEX);
    store.store(BUCKET, bucket);

    assertEquals(Set.of("other"), indexes(BUCKET).keySet());
    assertFalse(localS3Store.store().hasMap(vectorsMapName(BUCKET, INDEX)));
    assertEquals(Set.of("other"), store.fetch(BUCKET).getIndexes().keySet());
  }

  /**
   * An index that replaces another one of the same name isn't tracked yet, so none of the vectors of the index it
   * replaced are left behind.
   */
  @Test
  void replacesTheVectorsOfAnIndexThatWasReplaced() {
    VectorBucketMetadata bucket = bucket(BUCKET);
    VectorIndexMetadata index = index(INDEX);
    index.addVectorObject(vector("a", 1L));
    index.addVectorObject(vector("b", 2L));
    bucket.putIndexMetadata(INDEX, index);
    store.store(BUCKET, bucket);

    VectorIndexMetadata replacement = index(INDEX);
    replacement.setDimension(3);
    VectorObjectMetadata c = new VectorObjectMetadata("c", 3, 3L, null);
    replacement.addVectorObject(c);
    bucket.putIndexMetadata(INDEX, replacement);
    store.store(BUCKET, bucket);

    assertEquals(Set.of("c"), vectors(BUCKET, INDEX).keySet());
    VectorIndexMetadata loaded = store.fetch(BUCKET).getIndexMetadata(INDEX).orElseThrow();
    assertEquals(3, loaded.getDimension());
    assertEquals(Set.of("c"), loaded.getVectorObjects().keySet());
  }

  /**
   * Deleting a bucket deletes the maps of its indexes and vectors, and none of another bucket whose name starts with
   * its name.
   */
  @Test
  void deletesTheMapsOfABucket() {
    for (String name : List.of(BUCKET, BUCKET + "-2")) {
      VectorBucketMetadata bucket = bucket(name);
      VectorIndexMetadata index = index(INDEX);
      index.addVectorObject(vector("a", 1L));
      bucket.putIndexMetadata(INDEX, index);
      store.store(name, bucket);
    }

    store.delete(BUCKET);

    assertFalse(store.exists(BUCKET));
    assertNull(store.fetch(BUCKET));
    Set<String> mapNames = new TreeSet<>(localS3Store.store().getMapNames());
    assertFalse(mapNames.contains(MVStoreVectorBucketMetadataStore.INDEXES_MAP_PREFIX + BUCKET), mapNames::toString);
    assertFalse(mapNames.contains(vectorsMapName(BUCKET, INDEX)), mapNames::toString);
    assertEquals(Set.of("a"), store.fetch(BUCKET + "-2").getIndexMetadata(INDEX).orElseThrow()
        .getVectorObjects().keySet());
  }

  /**
   * An index that no store tracks, e.g. of a service that keeps its vectors in memory, records none of its changes,
   * which would never be drained.
   */
  @Test
  void anIndexThatNoStoreTracksRecordsNothing() {
    VectorIndexMetadata index = index(INDEX);
    index.addVectorObject(vector("a", 1L));
    index.removeVectorObject("a");
    index.clearVectorObjects();

    assertFalse(index.tracksChanges());
    assertFalse(index.drainAllVectorsChanged());
    assertEquals(List.of(), index.drainChangedVectorIds());
  }

  /**
   * A store of an earlier 2.5 snapshot holds a vector bucket whole. Opening it for writing spreads the bucket over the
   * maps once; opening it for reading only reads it as it is, and leaves it so.
   */
  @Test
  void readsAndSpreadsABucketThatWasWrittenWhole(@TempDir Path dataPath) {
    VectorBucketMetadata whole = bucket(BUCKET);
    VectorIndexMetadata index = index(INDEX);
    index.addVectorObject(vector("a", 1L));
    index.addVectorObject(vector("b", 2L));
    whole.putIndexMetadata(INDEX, index);
    try (LocalS3Store persistent = LocalS3Store.persistent(dataPath)) {
      persistent.store().<String, String>openMap(MVStoreVectorBucketMetadataStore.VECTOR_BUCKETS_MAP)
          .put(BUCKET, JsonUtils.toJson(whole));
    }

    try (LocalS3Store readOnly = LocalS3Store.readOnly(dataPath)) {
      VectorBucketMetadata loaded = MVStoreVectorBucketMetadataStore.create(readOnly).fetch(BUCKET);
      assertEquals(Set.of("a", "b"), loaded.getIndexMetadata(INDEX).orElseThrow().getVectorObjects().keySet());
      assertFalse(readOnly.store().hasMap(vectorsMapName(BUCKET, INDEX)));
    }

    try (LocalS3Store persistent = LocalS3Store.persistent(dataPath)) {
      MetadataStore<VectorBucketMetadata> spread = MVStoreVectorBucketMetadataStore.create(persistent);
      assertFalse(persistent.store().<String, String>openMap(MVStoreVectorBucketMetadataStore.VECTOR_BUCKETS_MAP)
          .get(BUCKET).contains("indexes"));
      assertEquals(Set.of("a", "b"), persistent.store().openMap(vectorsMapName(BUCKET, INDEX)).keySet());
      VectorBucketMetadata loaded = spread.fetch(BUCKET);
      assertEquals(whole.getCreationDate(), loaded.getCreationDate());
      VectorObjectMetadata a = loaded.getIndexMetadata(INDEX).orElseThrow().getVectorObject("a");
      assertEquals(1L, a.getStorageId());
      assertEquals(index.getVectorObject("a").getCreationDate(), a.getCreationDate());
    }

    try (LocalS3Store readOnly = LocalS3Store.readOnly(dataPath)) {
      VectorBucketMetadata loaded = MVStoreVectorBucketMetadataStore.create(readOnly).fetch(BUCKET);
      assertEquals(Set.of("a", "b"), loaded.getIndexMetadata(INDEX).orElseThrow().getVectorObjects().keySet());
      assertFalse(loaded.getIndexMetadata(INDEX).orElseThrow().tracksChanges(),
          "A bucket read from a store that can't be written records no changes.");
    }
  }

  private MVMap<String, String> indexes(String bucketName) {
    return localS3Store.store().openMap(MVStoreVectorBucketMetadataStore.INDEXES_MAP_PREFIX + bucketName);
  }

  private MVMap<String, String> vectors(String bucketName, String indexName) {
    return localS3Store.store().openMap(vectorsMapName(bucketName, indexName));
  }

  private static String vectorsMapName(String bucketName, String indexName) {
    return MVStoreVectorBucketMetadataStore.VECTORS_MAP_PREFIX + bucketName + "/" + indexName;
  }

  private static VectorBucketMetadata bucket(String name) {
    VectorBucketMetadata bucket = new VectorBucketMetadata();
    bucket.setVectorBucketName(name);
    bucket.setCreationDate(System.currentTimeMillis());
    return bucket;
  }

  private static VectorIndexMetadata index(String name) {
    VectorIndexMetadata index = new VectorIndexMetadata();
    index.setIndexName(name);
    index.setDimension(2);
    index.setDataType(VectorDataType.FLOAT32);
    index.setDistanceMetric(DistanceMetric.COSINE);
    index.setCreationDate(System.currentTimeMillis());
    index.setActive();
    return index;
  }

  private static VectorObjectMetadata vector(String vectorId, long storageId) {
    return new VectorObjectMetadata(vectorId, 2, storageId, null);
  }

}
