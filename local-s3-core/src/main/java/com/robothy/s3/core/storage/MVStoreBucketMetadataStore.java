package com.robothy.s3.core.storage;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.robothy.s3.core.exception.InvalidBucketNameException;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadataCache;
import com.robothy.s3.core.model.internal.ObjectMetadataRef;
import com.robothy.s3.core.model.internal.ObjectPartMetadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.model.internal.UploadPartMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.util.JsonUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.BooleanSupplier;
import org.apache.commons.lang3.StringUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

/**
 * Keeps the metadata of the buckets of a LocalS3 service in its {@linkplain LocalS3Store}, as JSON values.
 *
 * <p>The metadata of a bucket is spread over three kinds of map, so that a change writes only what it changed rather
 * than the whole bucket. A put of one object into a bucket of a million objects writes one record.
 *
 * <ul>
 *   <li>{@value #BUCKETS_MAP}: the name of a bucket to its own settings, e.g. its region, versioning, ACL and CORS,
 *   without the objects and uploads it holds;</li>
 *   <li>{@code objects/&lt;bucket&gt;}: an object key to the metadata of the object, with all of its versions;</li>
 *   <li>{@code uploads/&lt;bucket&gt;}: an object key to the multipart uploads in progress for that key.</li>
 * </ul>
 *
 * <p>Whether a change is committed as it is written is the {@linkplain PersistencePolicy} of the store; the change
 * itself is always written into the key-value store, so every request sees it either way.
 *
 * <p>The values are JSON, written by the same Jackson mapper that reads them, so the metadata model needs no
 * {@code Serializable} of its own and a store can be read by a later version that added fields.
 *
 * <p>{@linkplain #store} writes the objects and uploads that the bucket recorded as changed, see
 * {@linkplain BucketMetadata#drainChangedObjectKeys()}, and always writes the settings of the bucket, which are small.
 *
 * <p>{@linkplain #fetch} reads the keys of the objects of a bucket, not their metadata: each key gets an
 * {@linkplain ObjectMetadataRef} that reads its metadata from the {@code objects/} map when something needs it, and an
 * {@linkplain ObjectMetadataCache} bounds how many of them keep it in heap. Opening a data directory therefore costs
 * the keys it holds rather than the metadata of every version of every object, and a service that serves a few keys of
 * a large bucket holds a few keys' metadata. A store that keeps its metadata in memory has nothing to read a bucket
 * back from, so its references are loaded and its cache keeps everything.
 */
public class MVStoreBucketMetadataStore implements MetadataStore<BucketMetadata> {

  /**
   * The name of the map that holds the settings of every bucket.
   */
  static final String BUCKETS_MAP = "buckets";

  private static final String OBJECTS_MAP_PREFIX = "objects/";

  private static final String UPLOADS_MAP_PREFIX = "uploads/";

  /**
   * Writes the settings of a bucket without the objects and the uploads it holds, which are stored on their own.
   */
  private static final JsonMapper ATTRIBUTES_MAPPER = JsonMapper.builder()
      .addModule(new Jdk8Module())
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .addMixIn(BucketMetadata.class, BucketAttributes.class)
      .build();

  /**
   * Hides the contents of a bucket from {@linkplain #ATTRIBUTES_MAPPER}.
   */
  private abstract static class BucketAttributes {

    @JsonIgnore
    abstract Map<String, ObjectMetadataRef> getObjectMap();

    @JsonIgnore
    abstract Map<String, NavigableMap<String, UploadMetadata>> getUploads();

  }

  /**
   * Reads the uploads of an object key, which Jackson can't infer from a nested generic type.
   */
  private static final com.fasterxml.jackson.core.type.TypeReference<ConcurrentSkipListMap<String, UploadMetadata>>
      UPLOADS_OF_KEY = new com.fasterxml.jackson.core.type.TypeReference<>() {
      };

  private final MVStore store;

  /**
   * Bounds how much of the object metadata that the buckets of this store reference is in heap at once.
   */
  private final ObjectMetadataCache objectMetadataCache;

  /**
   * Whether a change is committed as it is written; a {@linkplain PersistencePolicy#FAST} store leaves that to the
   * background thread of MVStore and to {@linkplain LocalS3Store#close()}.
   */
  private final boolean commitEveryChange;

  /**
   * Create a store over the MVStore of a LocalS3 service.
   *
   * @param localS3Store the store of the service.
   * @return a metadata store that reads and writes the buckets of the service.
   */
  public static MetadataStore<BucketMetadata> create(LocalS3Store localS3Store) {
    Objects.requireNonNull(localS3Store, "localS3Store");
    // A store that keeps its metadata in memory has nothing to read a bucket back from, so its buckets hold every
    // object they were given; one over a file reads an object when it is needed, within a bounded heap.
    return new MVStoreBucketMetadataStore(localS3Store.store(),
        localS3Store.isInMemory() ? ObjectMetadataCache.unbounded() : ObjectMetadataCache.bounded(),
        localS3Store.commitsEveryChange());
  }

  /**
   * Create a store with a given cache, for the tests that exercise the bound.
   *
   * @param localS3Store the store of the service.
   * @param objectMetadataCache bounds how much object metadata is in heap.
   * @return a metadata store that reads and writes the buckets of the service.
   */
  public static MetadataStore<BucketMetadata> create(LocalS3Store localS3Store,
                                                     ObjectMetadataCache objectMetadataCache) {
    Objects.requireNonNull(localS3Store, "localS3Store");
    return new MVStoreBucketMetadataStore(localS3Store.store(),
        Objects.requireNonNull(objectMetadataCache, "objectMetadataCache"), localS3Store.commitsEveryChange());
  }

  private MVStoreBucketMetadataStore(MVStore store, ObjectMetadataCache objectMetadataCache,
                                     boolean commitEveryChange) {
    this.store = store;
    this.objectMetadataCache = objectMetadataCache;
    this.commitEveryChange = commitEveryChange;
  }

  /**
   * The cache that bounds how much of the object metadata of the buckets of this store is in heap.
   *
   * @return the cache.
   */
  public ObjectMetadataCache objectMetadataCache() {
    return objectMetadataCache;
  }

  /**
   * Make the change durable, if the store commits every change. A {@linkplain PersistencePolicy#FAST} store leaves
   * the change in the key-value store, where every request sees it, and lets MVStore commit it in the background:
   * a burst of small writes is then committed together rather than appending a chunk each.
   */
  private void commit() {
    if (commitEveryChange) {
      store.commit();
    }
  }

  private MVMap<String, String> buckets() {
    return store.openMap(BUCKETS_MAP);
  }

  private MVMap<String, String> objects(String bucketName) {
    return store.openMap(OBJECTS_MAP_PREFIX + bucketName);
  }

  private MVMap<String, String> uploads(String bucketName) {
    return store.openMap(UPLOADS_MAP_PREFIX + bucketName);
  }

  @Override
  public BucketMetadata fetch(String bucketName) {
    String attributes = buckets().get(requireBucketName(bucketName));
    if (attributes == null) {
      return null;
    }
    BucketMetadata bucketMetadata = JsonUtils.fromJson(attributes, BucketMetadata.class);
    // Only the keys of the objects are read here, each with a reference that reads its metadata when it is needed:
    // opening a bucket of a million objects costs its keys rather than all of their metadata. The multipart uploads
    // in progress are read whole, being few and short lived.
    MVMap<String, String> objects = objects(bucketName);
    for (String key : objects.keySet()) {
      bucketMetadata.putObjectMetadataRef(key, ObjectMetadataRef.lazy(key, objects::get, objectMetadataCache));
    }
    uploads(bucketName).forEach((key, json) -> bucketMetadata.getUploads().put(key, readUploads(json)));
    // A bucket that was just read has nothing left to write.
    bucketMetadata.drainChangedObjectKeys();
    bucketMetadata.drainChangedUploadKeys();
    return bucketMetadata;
  }

  @Override
  public boolean exists(String bucketName) {
    return buckets().containsKey(requireBucketName(bucketName));
  }

  @Override
  public String store(String bucketName, BucketMetadata bucketMetadata) {
    if (StringUtils.isBlank(bucketMetadata.getBucketName())) {
      throw new IllegalArgumentException("Invalid bucket name '" + bucketMetadata.getBucketName() + "'.");
    }
    String name = requireBucketName(bucketMetadata.getBucketName());

    List<String> changedObjects = bucketMetadata.drainChangedObjectKeys();
    if (!changedObjects.isEmpty()) {
      MVMap<String, String> objects = objects(name);
      for (String key : changedObjects) {
        ObjectMetadataRef ref = bucketMetadata.getObjectMap().get(key);
        if (ref == null) {
          objects.remove(key);
        } else {
          ObjectMetadata objectMetadata = ref.persisted();
          String json = JsonUtils.toJson(objectMetadata);
          objects.put(key, json);
          ref.persistedSize(json.length());
          recordMaxId(bucketMetadata, objectMetadata);
          // Written, so the metadata may now be dropped from heap and read back from here. An object that was created
          // in heap gets its source here, and is tracked from here on, so that storing many objects in one session
          // doesn't end up holding the metadata of all of them.
          ref.attach(key, objects::get, objectMetadataCache);
          ref.unpin();
          objectMetadataCache.recordRead(ref);
        }
      }
    }

    List<String> changedUploads = bucketMetadata.drainChangedUploadKeys();
    if (!changedUploads.isEmpty()) {
      MVMap<String, String> uploads = uploads(name);
      for (String key : changedUploads) {
        NavigableMap<String, UploadMetadata> uploadsOfKey = bucketMetadata.getUploads().get(key);
        if (uploadsOfKey == null || uploadsOfKey.isEmpty()) {
          uploads.remove(key);
        } else {
          uploads.put(key, JsonUtils.toJson(uploadsOfKey));
          uploadsOfKey.forEach((uploadId, upload) -> recordMaxId(bucketMetadata, uploadId, upload));
        }
      }
    }

    // Written last: the attributes carry the greatest ID in use, which the objects and uploads above raised.
    buckets().put(name, writeAttributes(bucketMetadata));
    commit();
    return name;
  }

  @Override
  public void delete(String bucketName) {
    String name = requireBucketName(bucketName);
    if (buckets().remove(name) == null) {
      throw new IllegalStateException("Failed to delete metadata of bucket " + bucketName);
    }
    store.removeMap(objects(name));
    store.removeMap(uploads(name));
    commit();
  }

  @Override
  public List<BucketMetadata> fetchAll() {
    List<BucketMetadata> all = new ArrayList<>();
    for (String bucketName : buckets().keySet()) {
      BucketMetadata bucketMetadata = fetch(bucketName);
      if (bucketMetadata != null) {
        all.add(bucketMetadata);
      }
    }
    return all;
  }

  /**
   * A snapshot of the maps of a store that reference content in the storage, i.e. the {@code objects/} and
   * {@code uploads/} maps of every bucket, as they are now: read-only maps that later changes of the store don't change,
   * as long as the caller keeps the current version of the store in use, see {@linkplain MVStore#registerVersionUsage()}.
   *
   * @param store the MVStore of a {@linkplain LocalS3Store}.
   * @return the maps, whether or not a bucket of their name exists, so that anything a map references is counted.
   */
  public static List<MVMap<String, String>> contentReferencingMaps(MVStore store) {
    long version = store.getCurrentVersion();
    List<MVMap<String, String>> maps = new ArrayList<>();
    for (String mapName : store.getMapNames()) {
      if (mapName.startsWith(OBJECTS_MAP_PREFIX) || mapName.startsWith(UPLOADS_MAP_PREFIX)) {
        maps.add(store.<String, String>openMap(mapName).openVersion(version));
      }
    }
    return maps;
  }

  /**
   * The IDs of the content in the storage that maps of {@linkplain #contentReferencingMaps} reference: the content of
   * every version of every object, of the parts of a version that a multipart upload completed, and of the parts of the
   * uploads in progress. The IDs of a version are counted however its content is read, e.g. both the file ID and the
   * part IDs of a version that has both. Reads the metadata of every object, so it costs the metadata that the maps
   * hold.
   *
   * @param maps the maps.
   * @param cancelled whether to stop reading, checked between the values.
   * @return the referenced IDs, sorted and without duplicates; empty if the maps hold no objects and no uploads.
   * @throws UncheckedIOException if the metadata can't be read.
   * @throws java.util.concurrent.CancellationException if {@code cancelled} turned {@code true}.
   */
  public static long[] referencedContentIds(List<MVMap<String, String>> maps, BooleanSupplier cancelled) {
    ContentIds ids = new ContentIds();
    for (MVMap<String, String> map : maps) {
      boolean objects = map.getName().startsWith(OBJECTS_MAP_PREFIX);
      for (String json : map.values()) {
        if (cancelled.getAsBoolean()) {
          throw new CancellationException();
        }
        if (objects) {
          ObjectMetadata objectMetadata = JsonUtils.fromJson(json, ObjectMetadata.class);
          for (VersionedObjectMetadata version : objectMetadata.getVersionedObjectMap().values()) {
            ids.add(version.getFileId());
            for (ObjectPartMetadata part : version.getParts().orElse(List.of())) {
              ids.add(part.getFileId());
            }
          }
        } else {
          for (UploadMetadata upload : readUploads(json).values()) {
            for (UploadPartMetadata part : upload.getParts().values()) {
              ids.add(part.getFileId());
            }
          }
        }
      }
    }
    return ids.sortedDistinct();
  }

  /**
   * IDs collected into a growing array of primitives rather than a set of boxed {@code Long}s, which takes several times
   * the heap for the many IDs of a large data directory.
   */
  private static final class ContentIds {

    private long[] ids = new long[1024];

    private int size;

    void add(Long id) {
      if (id == null) {
        return;
      }
      if (size == ids.length) {
        ids = Arrays.copyOf(ids, ids.length * 2);
      }
      ids[size++] = id;
    }

    long[] sortedDistinct() {
      long[] sorted = Arrays.copyOf(ids, size);
      Arrays.sort(sorted);
      int distinct = 0;
      for (int i = 0; i < sorted.length; i++) {
        if (i == 0 || sorted[i] != sorted[i - 1]) {
          sorted[distinct++] = sorted[i];
        }
      }
      return Arrays.copyOf(sorted, distinct);
    }
  }

  /**
   * Record the greatest generated ID that an object references, so that a service which opens this store again can
   * seed its ID generator without reading the metadata of every object to find the IDs in use. Only the objects that
   * are written are walked, so it costs what the change costs.
   */
  private static void recordMaxId(BucketMetadata bucketMetadata, ObjectMetadata objectMetadata) {
    objectMetadata.getVersionedObjectMap().forEach((versionId, version) -> {
      bucketMetadata.recordMaxId(toId(versionId));
      bucketMetadata.recordMaxId(toId(version.getFileId()));
      // The content of a version completed from a multipart upload is stored in its parts.
      for (ObjectPartMetadata part : version.getParts().orElse(List.of())) {
        bucketMetadata.recordMaxId(toId(part.getFileId()));
      }
    });
  }

  private static void recordMaxId(BucketMetadata bucketMetadata, String uploadId, UploadMetadata upload) {
    bucketMetadata.recordMaxId(toId(uploadId));
    for (UploadPartMetadata part : upload.getParts().values()) {
      bucketMetadata.recordMaxId(toId(part.getFileId()));
    }
  }

  /**
   * An ID as a number; {@code -1} for anything the generator didn't produce, e.g. the {@code null} version.
   */
  private static long toId(String id) {
    try {
      return Long.parseLong(id);
    } catch (NumberFormatException | NullPointerException e) {
      return -1L;
    }
  }

  private static long toId(Long id) {
    return id == null ? -1L : id;
  }

  private static String writeAttributes(BucketMetadata bucketMetadata) {
    try {
      return ATTRIBUTES_MAPPER.writeValueAsString(bucketMetadata);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to write the settings of bucket "
          + bucketMetadata.getBucketName() + " as JSON.", e);
    }
  }

  private static NavigableMap<String, UploadMetadata> readUploads(String json) {
    try {
      return ATTRIBUTES_MAPPER.readValue(json, UPLOADS_OF_KEY);
    } catch (IOException e) {
      throw new UncheckedIOException("Failed to read the multipart uploads of an object from JSON.", e);
    }
  }

  /**
   * A bucket name is a single map name, so one that could name another map, e.g. one holding a {@code /}, is invalid
   * like a blank one. Amazon S3 rejects such names too.
   */
  private static String requireBucketName(String bucketName) {
    if (StringUtils.isBlank(bucketName) || bucketName.indexOf('/') >= 0) {
      throw new InvalidBucketNameException(String.valueOf(bucketName));
    }
    return bucketName;
  }

}
