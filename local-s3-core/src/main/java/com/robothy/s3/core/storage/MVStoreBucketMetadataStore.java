package com.robothy.s3.core.storage;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.robothy.s3.core.exception.InvalidBucketNameException;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.UploadMetadata;
import com.robothy.s3.core.util.JsonUtils;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;
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
 * <p>The values are JSON, written by the same Jackson mapper that reads them, so the metadata model needs no
 * {@code Serializable} of its own and a store can be read by a later version that added fields.
 *
 * <p>{@linkplain #store} writes the objects and uploads that the bucket recorded as changed, see
 * {@linkplain BucketMetadata#drainChangedObjectKeys()}, and always writes the settings of the bucket, which are small.
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
    abstract Map<String, ObjectMetadata> getObjectMap();

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
   * Create a store over the MVStore of a LocalS3 service.
   *
   * @param localS3Store the store of the service.
   * @return a metadata store that reads and writes the buckets of the service.
   */
  public static MetadataStore<BucketMetadata> create(LocalS3Store localS3Store) {
    return new MVStoreBucketMetadataStore(Objects.requireNonNull(localS3Store, "localS3Store").store());
  }

  private MVStoreBucketMetadataStore(MVStore store) {
    this.store = store;
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
    objects(bucketName).forEach((key, json) ->
        bucketMetadata.getObjectMap().put(key, JsonUtils.fromJson(json, ObjectMetadata.class)));
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
    buckets().put(name, writeAttributes(bucketMetadata));

    List<String> changedObjects = bucketMetadata.drainChangedObjectKeys();
    if (!changedObjects.isEmpty()) {
      MVMap<String, String> objects = objects(name);
      for (String key : changedObjects) {
        ObjectMetadata objectMetadata = bucketMetadata.getObjectMap().get(key);
        if (objectMetadata == null) {
          objects.remove(key);
        } else {
          objects.put(key, JsonUtils.toJson(objectMetadata));
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
        }
      }
    }

    store.commit();
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
    store.commit();
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
