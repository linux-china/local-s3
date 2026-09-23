package com.robothy.s3.core.storage.s3vectors;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.robothy.s3.core.exception.InvalidBucketNameException;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorIndexMetadata;
import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.storage.MVStoreBucketMetadataStore;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.storage.PersistencePolicy;
import com.robothy.s3.core.util.JsonUtils;
import com.robothy.s3.core.util.Strings;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the metadata of the vector buckets of a LocalS3 service in its {@linkplain LocalS3Store}, as JSON values.
 *
 * <p>It is the same store that {@linkplain MVStoreBucketMetadataStore} writes the S3 buckets to, so a data directory
 * holds the metadata of both kinds of bucket in one {@value LocalS3Store#FILE_NAME}, committed on one write stream:
 * a copy of that file is a consistent point of the whole service rather than of one half of it. Only the content of
 * the objects and the data of the vectors are kept beside it, each named by an ID that the metadata references.
 *
 * <p>Like an S3 bucket, a vector bucket is spread over several maps, so that a change writes only what it changed: a
 * put of one vector into an index of a million vectors writes one record, rather than the whole bucket.
 *
 * <ul>
 *   <li>{@value #VECTOR_BUCKETS_MAP}: the name of a vector bucket to its own settings, e.g. its encryption and policy,
 *   without its indexes;</li>
 *   <li>{@code vectors/indexes/<bucket>}: the name of an index to its configuration, without its vectors;</li>
 *   <li>{@code vectors/objects/<bucket>/<index>}: the ID of a vector to its metadata. A bucket name holds no
 *   {@code /}, so the maps of a bucket are the ones named with its prefix, whatever its index names hold.</li>
 * </ul>
 *
 * <p>{@linkplain #store} writes the vectors that an index recorded as changed, see
 * {@linkplain VectorIndexMetadata#drainChangedVectorIds()}, once the index is tracked: after this store wrote the index
 * or read it. An index that isn't tracked yet, e.g. one just created, is written whole, and an index that the store
 * holds but the bucket doesn't anymore is deleted with its vectors. The settings of the bucket and of its indexes are
 * small, and written when they differ from the stored ones.
 *
 * <p>{@linkplain #fetch} reads the whole bucket, the metadata of its vectors included: a query of an index reads the
 * metadata of all of its vectors to filter them, so reading them later would save no heap.
 *
 * <p>A store of an earlier 2.5 snapshot keeps a vector bucket whole, as one value of {@value #VECTOR_BUCKETS_MAP} with
 * its indexes and vectors. Opening such a store for writing spreads its buckets over the maps above; a store opened for
 * reading only is read as it is.
 */
public class MVStoreVectorBucketMetadataStore implements MetadataStore<VectorBucketMetadata> {

  /**
   * The name of the map that holds the settings of the vector buckets. Prefixed, so that it can't collide with a map of
   * {@linkplain MVStoreBucketMetadataStore}, whose {@code objects/} and {@code uploads/} maps are named after a bucket.
   */
  static final String VECTOR_BUCKETS_MAP = "vectors/buckets";

  /**
   * The prefix of the map of the indexes of a vector bucket, followed by the bucket name.
   */
  static final String INDEXES_MAP_PREFIX = "vectors/indexes/";

  /**
   * The prefix of the map of the vectors of an index, followed by the bucket name, {@code /} and the index name.
   */
  static final String VECTORS_MAP_PREFIX = "vectors/objects/";

  /**
   * The property of a vector bucket written whole by an earlier 2.5 snapshot, which the settings written on their own
   * don't have.
   */
  private static final String WHOLE_BUCKET_PROPERTY = "indexes";

  /**
   * Reads and writes the metadata like {@linkplain JsonUtils}, whose JSON a store of an earlier snapshot holds.
   */
  private static final JsonMapper MAPPER = JsonMapper.builderWithJackson2Defaults()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();

  /**
   * Writes the settings of a vector bucket without its indexes, and of an index without its vectors.
   */
  private static final JsonMapper ATTRIBUTES_MAPPER = MAPPER.rebuild()
      .addMixIn(VectorBucketMetadata.class, VectorBucketAttributes.class)
      .addMixIn(VectorIndexMetadata.class, VectorIndexAttributes.class)
      .build();

  /**
   * Hides the indexes of a vector bucket from {@linkplain #ATTRIBUTES_MAPPER}.
   */
  private abstract static class VectorBucketAttributes {

    @JsonIgnore
    abstract ConcurrentSkipListMap<String, VectorIndexMetadata> getIndexes();

  }

  /**
   * Hides the vectors of an index from {@linkplain #ATTRIBUTES_MAPPER}, and what is derived from them, which would
   * make the settings differ from the stored ones on every change of a vector.
   */
  private abstract static class VectorIndexAttributes {

    @JsonIgnore
    abstract Map<String, VectorObjectMetadata> getVectorObjects();

    @JsonIgnore
    abstract int getVectorObjectCount();

    @JsonIgnore
    abstract boolean isActive();

  }

  private final MVStore store;

  /**
   * Whether {@linkplain #sync()} commits the changes; see {@linkplain PersistencePolicy}.
   */
  private final boolean commitEveryChange;

  /**
   * Create a store over the MVStore of a LocalS3 service. A store opened for writing that holds vector buckets written
   * whole by an earlier 2.5 snapshot has them spread over the maps of this version first.
   *
   * @param localS3Store the store of the service, shared with the S3 buckets of the same data directory.
   * @return a metadata store that reads and writes the vector buckets of the service.
   */
  public static MetadataStore<VectorBucketMetadata> create(LocalS3Store localS3Store) {
    Objects.requireNonNull(localS3Store, "localS3Store");
    MVStoreVectorBucketMetadataStore metadataStore =
        new MVStoreVectorBucketMetadataStore(localS3Store.store(), localS3Store.commitsEveryChange());
    if (!localS3Store.isReadOnly()) {
      metadataStore.spreadWholeBuckets();
    }
    return metadataStore;
  }

  private MVStoreVectorBucketMetadataStore(MVStore store, boolean commitEveryChange) {
    this.store = store;
    this.commitEveryChange = commitEveryChange;
  }

  /**
   * Commit the changes written so far, if the store commits every change; see {@linkplain PersistencePolicy} and
   * {@linkplain com.robothy.s3.core.storage.MVStoreBucketMetadataStore#sync()}.
   */
  @Override
  public void sync() {
    if (commitEveryChange) {
      store.commit();
    }
  }

  private MVMap<String, String> vectorBuckets() {
    return store.openMap(VECTOR_BUCKETS_MAP);
  }

  private static String indexesMapName(String vectorBucketName) {
    return INDEXES_MAP_PREFIX + vectorBucketName;
  }

  private static String vectorsMapName(String vectorBucketName, String indexName) {
    return VECTORS_MAP_PREFIX + vectorBucketName + "/" + indexName;
  }

  @Override
  public VectorBucketMetadata fetch(String vectorBucketName) {
    String name = requireVectorBucketName(vectorBucketName);
    String json = vectorBuckets().get(name);
    if (json == null) {
      return null;
    }
    JsonNode attributes = readTree(json);
    if (attributes.has(WHOLE_BUCKET_PROPERTY)) {
      // Written whole by an earlier snapshot, and not spread since, i.e. read from a store opened for reading only.
      return treeToValue(attributes, VectorBucketMetadata.class);
    }

    VectorBucketMetadata bucketMetadata = treeToValue(attributes, VectorBucketMetadata.class);
    String indexesMapName = indexesMapName(name);
    if (store.hasMap(indexesMapName)) {
      MVMap<String, String> indexes = store.openMap(indexesMapName);
      for (Map.Entry<String, String> entry : indexes.entrySet()) {
        VectorIndexMetadata indexMetadata = fromJson(entry.getValue(), VectorIndexMetadata.class);
        indexMetadata.setVectorObjects(readVectors(name, entry.getKey()));
        bucketMetadata.getIndexes().put(entry.getKey(), indexMetadata);
      }
    }
    if (!store.isReadOnly()) {
      // What was just read is what the store holds, so only the changes from here on need to be written. A bucket read
      // from a store that can't be written, e.g. the initial data of an in-memory service, is never written back, and
      // records nothing.
      bucketMetadata.getIndexes().values().forEach(VectorIndexMetadata::trackChanges);
    }
    return bucketMetadata;
  }

  private Map<String, VectorObjectMetadata> readVectors(String vectorBucketName, String indexName) {
    Map<String, VectorObjectMetadata> vectors = new ConcurrentHashMap<>();
    String vectorsMapName = vectorsMapName(vectorBucketName, indexName);
    if (store.hasMap(vectorsMapName)) {
      MVMap<String, String> stored = store.openMap(vectorsMapName);
      stored.forEach((vectorId, json) -> vectors.put(vectorId, fromJson(json, VectorObjectMetadata.class)));
    }
    return vectors;
  }

  @Override
  public boolean exists(String vectorBucketName) {
    return vectorBuckets().containsKey(requireVectorBucketName(vectorBucketName));
  }

  @Override
  public String store(String vectorBucketName, VectorBucketMetadata vectorBucketMetadata) {
    if (Strings.isBlank(vectorBucketMetadata.getVectorBucketName())) {
      throw new IllegalArgumentException("Invalid vector bucket name '"
          + vectorBucketMetadata.getVectorBucketName() + "'.");
    }
    String name = requireVectorBucketName(vectorBucketMetadata.getVectorBucketName());
    writeBucket(name, vectorBucketMetadata);
    return name;
  }

  /**
   * Write what changed of a vector bucket, without committing it: the indexes it doesn't hold anymore are deleted, the
   * indexes that aren't tracked are written whole, and of the others the changed vectors. The settings of the bucket
   * are written last, so that a bucket whose settings are stored has its indexes stored.
   */
  private void writeBucket(String name, VectorBucketMetadata bucketMetadata) {
    Map<String, VectorIndexMetadata> indexes = bucketMetadata.getIndexes();
    MVMap<String, String> storedIndexes = store.openMap(indexesMapName(name));
    for (String indexName : new ArrayList<>(storedIndexes.keySet())) {
      if (!indexes.containsKey(indexName)) {
        removeVectorsMap(name, indexName);
        storedIndexes.remove(indexName);
      }
    }

    for (Map.Entry<String, VectorIndexMetadata> entry : indexes.entrySet()) {
      String indexName = entry.getKey();
      VectorIndexMetadata indexMetadata = entry.getValue();
      putIfChanged(storedIndexes, indexName, writeAttributes(indexMetadata));
      // Both are drained, whichever is written, so that an index written whole doesn't write its vectors again.
      boolean allVectorsChanged = indexMetadata.drainAllVectorsChanged();
      List<String> changedVectorIds = indexMetadata.drainChangedVectorIds();
      if (!indexMetadata.tracksChanges() || allVectorsChanged) {
        writeWholeIndex(name, indexName, indexMetadata);
        indexMetadata.trackChanges();
      } else if (!changedVectorIds.isEmpty()) {
        MVMap<String, String> vectors = store.openMap(vectorsMapName(name, indexName));
        for (String vectorId : changedVectorIds) {
          VectorObjectMetadata vector = indexMetadata.getVectorObject(vectorId);
          if (vector == null) {
            vectors.remove(vectorId);
          } else {
            vectors.put(vectorId, toJson(vector));
          }
        }
      }
    }

    putIfChanged(vectorBuckets(), name, writeAttributes(bucketMetadata));
  }

  /**
   * Replace the stored vectors of an index with the vectors it holds, e.g. of an index that was created, or replaced by
   * another one of the same name.
   */
  private void writeWholeIndex(String vectorBucketName, String indexName, VectorIndexMetadata indexMetadata) {
    removeVectorsMap(vectorBucketName, indexName);
    Map<String, VectorObjectMetadata> vectorObjects = indexMetadata.getVectorObjects();
    if (vectorObjects.isEmpty()) {
      return;
    }
    MVMap<String, String> vectors = store.openMap(vectorsMapName(vectorBucketName, indexName));
    vectorObjects.forEach((vectorId, vector) -> vectors.put(vectorId, toJson(vector)));
  }

  private void removeVectorsMap(String vectorBucketName, String indexName) {
    String vectorsMapName = vectorsMapName(vectorBucketName, indexName);
    if (store.hasMap(vectorsMapName)) {
      store.removeMap(vectorsMapName);
    }
  }

  /**
   * Write a small value only if it differs from the stored one, so that a change of a vector doesn't write the settings
   * that it left as they were.
   */
  private static void putIfChanged(MVMap<String, String> map, String key, String json) {
    if (!json.equals(map.get(key))) {
      map.put(key, json);
    }
  }

  @Override
  public void delete(String vectorBucketName) {
    String name = requireVectorBucketName(vectorBucketName);
    if (vectorBuckets().remove(name) == null) {
      throw new IllegalStateException("Failed to delete metadata of vector bucket " + vectorBucketName);
    }
    String indexesMapName = indexesMapName(name);
    if (store.hasMap(indexesMapName)) {
      store.removeMap(indexesMapName);
    }
    String vectorsMapPrefix = vectorsMapName(name, "");
    for (String mapName : new ArrayList<>(store.getMapNames())) {
      if (mapName.startsWith(vectorsMapPrefix)) {
        store.removeMap(mapName);
      }
    }
  }

  @Override
  public List<VectorBucketMetadata> fetchAll() {
    List<VectorBucketMetadata> all = new ArrayList<>();
    for (String vectorBucketName : vectorBuckets().keySet()) {
      VectorBucketMetadata bucketMetadata = fetch(vectorBucketName);
      if (bucketMetadata != null) {
        all.add(bucketMetadata);
      }
    }
    return all;
  }

  /**
   * Spread the vector buckets that an earlier 2.5 snapshot wrote whole over the maps of this version, and commit them,
   * so that the conversion is done once rather than on every open.
   */
  private void spreadWholeBuckets() {
    if (!store.hasMap(VECTOR_BUCKETS_MAP)) {
      return;
    }
    boolean spread = false;
    MVMap<String, String> vectorBuckets = vectorBuckets();
    for (Map.Entry<String, String> entry : new ArrayList<>(vectorBuckets.entrySet())) {
      JsonNode json = readTree(entry.getValue());
      if (json.has(WHOLE_BUCKET_PROPERTY)) {
        // Its indexes aren't tracked, so they are written whole, and its settings are written without them.
        writeBucket(entry.getKey(), treeToValue(json, VectorBucketMetadata.class));
        spread = true;
      }
    }
    if (spread) {
      store.commit();
    }
  }

  private static String writeAttributes(Object metadata) {
    try {
      return ATTRIBUTES_MAPPER.writeValueAsString(metadata);
    } catch (JacksonException e) {
      throw unchecked("Failed to write the settings of a " + metadata.getClass().getSimpleName() + " as JSON.", e);
    }
  }

  private static String toJson(Object metadata) {
    try {
      return MAPPER.writeValueAsString(metadata);
    } catch (JacksonException e) {
      throw unchecked("Failed to write a " + metadata.getClass().getSimpleName() + " as JSON.", e);
    }
  }

  private static <T> T fromJson(String json, Class<T> type) {
    try {
      return MAPPER.readValue(json, type);
    } catch (JacksonException e) {
      throw unchecked("Failed to read a " + type.getSimpleName() + " from JSON.", e);
    }
  }

  private static JsonNode readTree(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (JacksonException e) {
      throw unchecked("Failed to read a vector bucket from JSON.", e);
    }
  }

  private static <T> T treeToValue(JsonNode json, Class<T> type) {
    try {
      return MAPPER.treeToValue(json, type);
    } catch (JacksonException e) {
      throw unchecked("Failed to read a " + type.getSimpleName() + " from JSON.", e);
    }
  }

  private static UncheckedIOException unchecked(String message, JacksonException cause) {
    return new UncheckedIOException(message,
        cause.getCause() instanceof IOException io ? io : new IOException(cause.getMessage(), cause));
  }

  /**
   * A vector bucket that can't be named can't be stored. A blank name is rejected like Amazon S3 rejects it; a name
   * that holds a {@code /} is rejected as well, so that the maps of a vector bucket are told apart by their prefix.
   */
  private static String requireVectorBucketName(String vectorBucketName) {
    if (Strings.isBlank(vectorBucketName) || vectorBucketName.indexOf('/') >= 0) {
      throw new InvalidBucketNameException(String.valueOf(vectorBucketName));
    }
    return vectorBucketName;
  }

}
