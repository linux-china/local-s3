package com.robothy.s3.core.storage;

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import com.robothy.s3.core.util.Strings;
import org.h2.mvstore.Cursor;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keeps the metadata of the buckets of a LocalS3 service in its {@linkplain LocalS3Store}, as JSON values.
 *
 * <p>The metadata of a bucket is spread over four kinds of map, so that a change writes only what it changed rather
 * than the whole bucket. A put of one object into a bucket of a million objects writes one record, and a put of a
 * version of a key that holds a thousand versions writes that version rather than all of them.
 *
 * <ul>
 *   <li>{@value #BUCKETS_MAP}: the name of a bucket to its own settings, e.g. its region, versioning, ACL and CORS,
 *   without the objects and uploads it holds;</li>
 *   <li>{@code objects/&lt;bucket&gt;}: an object key to the metadata of the object without its versions, e.g. its
 *   virtual version, which is small; a store written before the versions had a map of their own holds every version
 *   of the key here, which is still read, and is replaced the first time the key is written;</li>
 *   <li>{@code versions/&lt;bucket&gt;}: an object key, {@code '\0'} and a version ID to the metadata of that version
 *   of the object. The versions of a key are adjacent, so they are read by a range scan;</li>
 *   <li>{@code uploads/&lt;bucket&gt;}: an object key to the multipart uploads in progress for that key.</li>
 * </ul>
 *
 * <p>{@linkplain #store} and {@linkplain #delete} write a change into the key-value store, so every request sees it,
 * without committing it; whether {@linkplain #sync()} then commits it is the {@linkplain PersistencePolicy} of the
 * store.
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

  private static final String VERSIONS_MAP_PREFIX = "versions/";

  private static final String UPLOADS_MAP_PREFIX = "uploads/";

  /**
   * Separates the object key from the version ID in the keys of a {@code versions/} map. A version ID never holds it.
   */
  private static final char VERSION_SEPARATOR = '\0';

  /**
   * The property that holds the versions in the JSON of a whole {@linkplain ObjectMetadata}, which a value of an
   * {@code objects/} map holds only if it was written before the versions had a map of their own.
   */
  private static final String VERSIONS_PROPERTY = "versionedObjectMap";

  /**
   * Writes an object without its versions, which are stored on their own.
   */
  private static final JsonMapper OBJECT_HEADER_MAPPER = JsonMapper.builderWithJackson2Defaults()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .addMixIn(ObjectMetadata.class, ObjectHeader.class)
      .build();

  /**
   * Hides the versions of an object from {@linkplain #OBJECT_HEADER_MAPPER}.
   */
  private abstract static class ObjectHeader {

    @JsonIgnore
    abstract NavigableMap<String, VersionedObjectMetadata> getVersionedObjectMap();

  }

  /**
   * Writes the settings of a bucket without the objects and the uploads it holds, which are stored on their own.
   */
  private static final JsonMapper ATTRIBUTES_MAPPER = JsonMapper.builderWithJackson2Defaults()
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
  private static final tools.jackson.core.type.TypeReference<ConcurrentSkipListMap<String, UploadMetadata>>
      UPLOADS_OF_KEY = new tools.jackson.core.type.TypeReference<>() {
      };

  private final MVStore store;

  /**
   * Bounds how much of the object metadata that the buckets of this store reference is in heap at once.
   */
  private final ObjectMetadataCache objectMetadataCache;

  /**
   * Whether {@linkplain #sync()} commits the changes; a {@linkplain PersistencePolicy#FAST} store leaves that to the
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
   * Commit the changes written so far, if the store commits every change. A {@linkplain PersistencePolicy#FAST} store
   * leaves them in the key-value store, where every request sees them, and lets MVStore commit them in the
   * background: a burst of small writes is then committed together rather than appending a chunk each.
   *
   * <p>A {@linkplain PersistencePolicy#DURABLE} store commits here rather than in {@linkplain #store}, which runs
   * under the write lock of the bucket, so that concurrent writers of a bucket share a commit: MVStore skips the
   * commit of a caller whose changes a commit that started meanwhile already holds.
   */
  @Override
  public void sync() {
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

  private MVMap<String, String> versions(String bucketName) {
    return store.openMap(VERSIONS_MAP_PREFIX + bucketName);
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
    Function<String, String> source = objectSource(objects, versions(bucketName));
    for (String key : objects.keySet()) {
      bucketMetadata.putObjectMetadataRef(key, ObjectMetadataRef.lazy(key, source, objectMetadataCache));
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
    if (Strings.isBlank(bucketMetadata.getBucketName())) {
      throw new IllegalArgumentException("Invalid bucket name '" + bucketMetadata.getBucketName() + "'.");
    }
    String name = requireBucketName(bucketMetadata.getBucketName());

    boolean allVersionsChanged = bucketMetadata.drainAllVersionsChanged();
    List<String> changedObjects = bucketMetadata.drainChangedObjectKeys();
    if (!changedObjects.isEmpty()) {
      MVMap<String, String> objects = objects(name);
      MVMap<String, String> versions = versions(name);
      Function<String, String> source = objectSource(objects, versions);
      for (String key : changedObjects) {
        ObjectMetadataRef ref = bucketMetadata.getObjectMap().get(key);
        if (ref == null) {
          objects.remove(key);
          removeVersions(versions, key);
        } else {
          writeObject(bucketMetadata, objects, versions, key, ref, allVersionsChanged);
          // Written, so the metadata may now be dropped from heap and read back from here. An object that was created
          // in heap gets its source here, and is tracked from here on, so that storing many objects in one session
          // doesn't end up holding the metadata of all of them.
          ref.attach(key, source, objectMetadataCache);
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
    return name;
  }

  @Override
  public void delete(String bucketName) {
    String name = requireBucketName(bucketName);
    if (buckets().remove(name) == null) {
      throw new IllegalStateException("Failed to delete metadata of bucket " + bucketName);
    }
    store.removeMap(objects(name));
    store.removeMap(versions(name));
    store.removeMap(uploads(name));
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
   * Write the metadata of an object: every version of it if the store may not hold them as they are, e.g. the object
   * is new, or was read from a whole JSON document, or its key was written before the versions had a map of their
   * own; otherwise only the versions that changed, so that a key with many versions costs the versions that a change
   * touched.
   * The metadata of the object without its versions is always written, being small.
   */
  private void writeObject(BucketMetadata bucketMetadata, MVMap<String, String> objects,
                           MVMap<String, String> versions, String key, ObjectMetadataRef ref,
                           boolean allVersionsChanged) {
    ObjectMetadata objectMetadata = ref.persisted();
    List<String> changedVersionIds = objectMetadata.drainChangedVersionIds();
    String header = writeObjectHeader(objectMetadata);
    String previousHeader = objects.put(key, header);
    boolean rewrite = allVersionsChanged || objectMetadata.isAllVersionsChanged() || previousHeader == null
        || holdsVersions(previousHeader);

    if (rewrite) {
      removeVersions(versions, key);
      int size = header.length();
      for (Map.Entry<String, VersionedObjectMetadata> version : objectMetadata.getVersionedObjectMap().entrySet()) {
        String json = JsonUtils.toJson(version.getValue());
        versions.put(versionKey(key, version.getKey()), json);
        size += json.length();
        recordMaxId(bucketMetadata, version.getKey(), version.getValue());
      }
      ref.persistedSize(size);
    } else {
      long size = ref.persistedSize() + header.length() - previousHeader.length();
      for (String versionId : changedVersionIds) {
        VersionedObjectMetadata version = objectMetadata.getVersionedObjectMap().get(versionId);
        String previous;
        if (version == null) {
          previous = versions.remove(versionKey(key, versionId));
        } else {
          String json = JsonUtils.toJson(version);
          previous = versions.put(versionKey(key, versionId), json);
          size += json.length();
          recordMaxId(bucketMetadata, versionId, version);
        }
        size -= previous == null ? 0 : previous.length();
      }
      ref.persistedSize((int) Math.max(0, Math.min(Integer.MAX_VALUE, size)));
    }
    objectMetadata.markPersisted();
  }

  /**
   * Reads the metadata of an object key of a bucket as the JSON of a whole {@linkplain ObjectMetadata}, from the
   * metadata without the versions and the versions of the key, which are joined as they are stored rather than read and
   * written again.
   */
  private static Function<String, String> objectSource(MVMap<String, String> objects,
                                                       MVMap<String, String> versions) {
    return key -> {
      String header = objects.get(key);
      if (header == null || holdsVersions(header)) {
        return header;
      }
      StringBuilder json = new StringBuilder(256).append("{\"").append(VERSIONS_PROPERTY).append("\":{");
      String prefix = versionPrefix(key);
      boolean first = true;
      for (Cursor<String, String> cursor = versions.cursor(prefix); cursor.hasNext(); ) {
        String versionKey = cursor.next();
        if (!versionKey.startsWith(prefix)) {
          break;
        }
        if (versionKey.indexOf(VERSION_SEPARATOR, prefix.length()) >= 0) {
          // A version of another key, which starts with this key and the separator.
          continue;
        }
        if (!first) {
          json.append(',');
        }
        first = false;
        json.append(JsonUtils.toJson(versionKey.substring(prefix.length()))).append(':').append(cursor.getValue());
      }
      json.append('}');
      String properties = header.substring(1, header.length() - 1).strip();
      if (!properties.isEmpty()) {
        json.append(',').append(properties);
      }
      return json.append('}').toString();
    };
  }

  /**
   * Remove every version of an object key from a {@code versions/} map.
   */
  private static void removeVersions(MVMap<String, String> versions, String key) {
    String prefix = versionPrefix(key);
    List<String> versionKeys = new ArrayList<>();
    for (Iterator<String> keys = versions.keyIterator(prefix); keys.hasNext(); ) {
      String versionKey = keys.next();
      if (!versionKey.startsWith(prefix)) {
        break;
      }
      if (versionKey.indexOf(VERSION_SEPARATOR, prefix.length()) < 0) {
        versionKeys.add(versionKey);
      }
    }
    versionKeys.forEach(versions::remove);
  }

  private static String versionPrefix(String key) {
    return key + VERSION_SEPARATOR;
  }

  private static String versionKey(String key, String versionId) {
    return versionPrefix(key) + versionId;
  }

  /**
   * Whether a value of an {@code objects/} map is a whole {@linkplain ObjectMetadata}, with its versions, which is
   * what a store written before the versions had a map of their own holds.
   */
  private static boolean holdsVersions(String objectJson) {
    return objectJson.contains("\"" + VERSIONS_PROPERTY + "\"");
  }

  private static String writeObjectHeader(ObjectMetadata objectMetadata) {
    try {
      return OBJECT_HEADER_MAPPER.writeValueAsString(objectMetadata);
    } catch (JacksonException e) {
      throw new UncheckedIOException("Failed to write the metadata of an object as JSON.",
          new IOException(e.getMessage(), e));
    }
  }

  /**
   * A snapshot of the maps of a store that reference content in the storage, i.e. the {@code objects/},
   * {@code versions/} and {@code uploads/} maps of every bucket, as they are now: read-only maps that later changes of
   * the store don't change, as long as the caller keeps the current version of the store in use, see
   * {@linkplain MVStore#registerVersionUsage()}.
   *
   * @param store the MVStore of a {@linkplain LocalS3Store}.
   * @return the maps, whether or not a bucket of their name exists, so that anything a map references is counted.
   */
  public static List<MVMap<String, String>> contentReferencingMaps(MVStore store) {
    long version = store.getCurrentVersion();
    List<MVMap<String, String>> maps = new ArrayList<>();
    for (String mapName : store.getMapNames()) {
      if (mapName.startsWith(OBJECTS_MAP_PREFIX) || mapName.startsWith(VERSIONS_MAP_PREFIX)
          || mapName.startsWith(UPLOADS_MAP_PREFIX)) {
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
      boolean versions = map.getName().startsWith(VERSIONS_MAP_PREFIX);
      for (String json : map.values()) {
        if (cancelled.getAsBoolean()) {
          throw new CancellationException();
        }
        if (objects) {
          if (!holdsVersions(json)) {
            // Its versions are in the versions/ map of the bucket.
            continue;
          }
          ObjectMetadata objectMetadata = JsonUtils.fromJson(json, ObjectMetadata.class);
          for (VersionedObjectMetadata version : objectMetadata.getVersionedObjectMap().values()) {
            ids.add(version);
          }
        } else if (versions) {
          ids.add(JsonUtils.fromJson(json, VersionedObjectMetadata.class));
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

    void add(VersionedObjectMetadata version) {
      add(version.getFileId());
      for (ObjectPartMetadata part : version.getParts().orElse(List.of())) {
        add(part.getFileId());
      }
    }

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
   * Record the greatest generated ID that a version of an object references, so that a service which opens this store
   * again can seed its ID generator without reading the metadata of every object to find the IDs in use. Only the
   * versions that are written are walked, so it costs what the change costs.
   */
  private static void recordMaxId(BucketMetadata bucketMetadata, String versionId, VersionedObjectMetadata version) {
    bucketMetadata.recordMaxId(toId(versionId));
    bucketMetadata.recordMaxId(toId(version.getFileId()));
    // The content of a version completed from a multipart upload is stored in its parts.
    for (ObjectPartMetadata part : version.getParts().orElse(List.of())) {
      bucketMetadata.recordMaxId(toId(part.getFileId()));
    }
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
    } catch (JacksonException e) {
      throw new UncheckedIOException("Failed to write the settings of bucket "
          + bucketMetadata.getBucketName() + " as JSON.", new IOException(e.getMessage(), e));
    }
  }

  private static NavigableMap<String, UploadMetadata> readUploads(String json) {
    try {
      return ATTRIBUTES_MAPPER.readValue(json, UPLOADS_OF_KEY);
    } catch (JacksonException e) {
      throw new UncheckedIOException("Failed to read the multipart uploads of an object from JSON.",
          new IOException(e.getMessage(), e));
    }
  }

  /**
   * A bucket name is a single map name, so one that could name another map, e.g. one holding a {@code /}, is invalid
   * like a blank one. Amazon S3 rejects such names too.
   */
  private static String requireBucketName(String bucketName) {
    if (Strings.isBlank(bucketName) || bucketName.indexOf('/') >= 0) {
      throw new InvalidBucketNameException(String.valueOf(bucketName));
    }
    return bucketName;
  }

}
