package com.robothy.s3.core.storage.s3vectors;

import com.robothy.s3.core.exception.InvalidBucketNameException;
import com.robothy.s3.core.model.internal.s3vectors.VectorBucketMetadata;
import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.storage.MVStoreBucketMetadataStore;
import com.robothy.s3.core.storage.MetadataStore;
import com.robothy.s3.core.storage.PersistencePolicy;
import com.robothy.s3.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.apache.commons.lang3.StringUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;

/**
 * Keeps the metadata of the vector buckets of a LocalS3 service in its {@linkplain LocalS3Store}, as JSON values, in
 * the {@value #VECTOR_BUCKETS_MAP} map.
 *
 * <p>It is the same store that {@linkplain MVStoreBucketMetadataStore} writes the S3 buckets to, so a data directory
 * holds the metadata of both kinds of bucket in one {@value LocalS3Store#FILE_NAME}, committed on one write stream:
 * a copy of that file is a consistent point of the whole service rather than of one half of it. Only the content of
 * the objects and the data of the vectors are kept beside it, each named by an ID that the metadata references.
 *
 * <p>A vector bucket is written whole, unlike an S3 bucket, whose objects and uploads are written one changed key at a
 * time: a {@linkplain VectorBucketMetadata} carries the indexes and the vectors it holds, and records no changed keys
 * to write on their own. Writing a bucket therefore costs its whole metadata, as it did when a bucket was one JSON
 * file, so a bucket of very many vectors is written in full by every change to it.
 */
public class MVStoreVectorBucketMetadataStore implements MetadataStore<VectorBucketMetadata> {

  /**
   * The name of the map that holds the vector buckets. Prefixed, so that it can't collide with a map of
   * {@linkplain MVStoreBucketMetadataStore}, whose {@code objects/} and {@code uploads/} maps are named after a bucket.
   */
  static final String VECTOR_BUCKETS_MAP = "vectors/buckets";

  private final MVStore store;

  /**
   * Whether a change is committed as it is written; see {@linkplain PersistencePolicy}.
   */
  private final boolean commitEveryChange;

  /**
   * Create a store over the MVStore of a LocalS3 service.
   *
   * @param localS3Store the store of the service, shared with the S3 buckets of the same data directory.
   * @return a metadata store that reads and writes the vector buckets of the service.
   */
  public static MetadataStore<VectorBucketMetadata> create(LocalS3Store localS3Store) {
    Objects.requireNonNull(localS3Store, "localS3Store");
    return new MVStoreVectorBucketMetadataStore(localS3Store.store(), localS3Store.commitsEveryChange());
  }

  private MVStoreVectorBucketMetadataStore(MVStore store, boolean commitEveryChange) {
    this.store = store;
    this.commitEveryChange = commitEveryChange;
  }

  /**
   * Make the change durable, if the store commits every change; see {@linkplain PersistencePolicy}.
   */
  private void commit() {
    if (commitEveryChange) {
      store.commit();
    }
  }

  private MVMap<String, String> vectorBuckets() {
    return store.openMap(VECTOR_BUCKETS_MAP);
  }

  @Override
  public VectorBucketMetadata fetch(String vectorBucketName) {
    String json = vectorBuckets().get(requireVectorBucketName(vectorBucketName));
    return json == null ? null : JsonUtils.fromJson(json, VectorBucketMetadata.class);
  }

  @Override
  public boolean exists(String vectorBucketName) {
    return vectorBuckets().containsKey(requireVectorBucketName(vectorBucketName));
  }

  @Override
  public String store(String vectorBucketName, VectorBucketMetadata vectorBucketMetadata) {
    if (StringUtils.isBlank(vectorBucketMetadata.getVectorBucketName())) {
      throw new IllegalArgumentException("Invalid vector bucket name '"
          + vectorBucketMetadata.getVectorBucketName() + "'.");
    }
    String name = requireVectorBucketName(vectorBucketMetadata.getVectorBucketName());
    vectorBuckets().put(name, JsonUtils.toJson(vectorBucketMetadata));
    commit();
    return name;
  }

  @Override
  public void delete(String vectorBucketName) {
    String name = requireVectorBucketName(vectorBucketName);
    if (vectorBuckets().remove(name) == null) {
      throw new IllegalStateException("Failed to delete metadata of vector bucket " + vectorBucketName);
    }
    commit();
  }

  @Override
  public List<VectorBucketMetadata> fetchAll() {
    List<VectorBucketMetadata> all = new ArrayList<>();
    for (String json : vectorBuckets().values()) {
      all.add(JsonUtils.fromJson(json, VectorBucketMetadata.class));
    }
    return all;
  }

  /**
   * A vector bucket that can't be named can't be stored. A blank name is rejected like Amazon S3 rejects it; a name
   * that holds a {@code /} is rejected as well, so that a vector bucket is named the same way wherever LocalS3 keeps
   * it, whether or not the name happens to be usable as a key here.
   */
  private static String requireVectorBucketName(String vectorBucketName) {
    if (StringUtils.isBlank(vectorBucketName) || vectorBucketName.indexOf('/') >= 0) {
      throw new InvalidBucketNameException(String.valueOf(vectorBucketName));
    }
    return vectorBucketName;
  }

}
