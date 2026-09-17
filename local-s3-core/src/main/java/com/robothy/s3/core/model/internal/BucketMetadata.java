package com.robothy.s3.core.model.internal;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.robothy.s3.core.assertions.ObjectAssertions;
import com.robothy.s3.core.converters.deserializer.ObjectMetadataMapConverter;
import com.robothy.s3.core.converters.deserializer.UploadMetadataMapConverter;
import com.robothy.s3.core.model.BucketLifecycleConfiguration;
import com.robothy.s3.core.model.BucketObjectLockConfiguration;
import com.robothy.s3.core.util.BucketEncryptionConfigurations;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.datatypes.CORSConfiguration;
import com.robothy.s3.datatypes.PublicAccessBlockConfiguration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * The state that LocalS3 holds for a bucket, which grows with every object and multipart upload that the
 * bucket receives.
 *
 * <p>It carries no {@code equals} and {@code hashCode} of its own, so two buckets are the same one only if
 * they are the same instance. That is the semantics this class wants: it is mutable state that a request
 * changes under the lock of its bucket, so a value that was derived from its contents, e.g. a hash code,
 * stops describing it as soon as the next request runs, and looking it up by one would find nothing. A
 * generated pair would also walk every object of the bucket on each call.
 */
@Getter
@Setter
public class BucketMetadata {

  /**
   * Create a {@linkplain BucketMetadata} instance.
   */
  public BucketMetadata() {

  }

  /**
   * The objects of the bucket, by key, each as a reference that reads its metadata when it is needed. The keys of
   * every object are held here, which is what the listings navigate; only the metadata of the objects in use is in
   * heap, see {@linkplain ObjectMetadataRef}.
   */
  @JsonDeserialize(converter = ObjectMetadataMapConverter.class)
  private NavigableMap<String, ObjectMetadataRef> objectMap = new ConcurrentSkipListMap<>();

  /**
   * The greatest ID that the generator produced for anything this bucket references, e.g. a version ID or the ID of a
   * stored file, as far as the bucket has been written; {@code null} for a bucket that has never been written, or one
   * written by a LocalS3 that didn't record it.
   *
   * <p>It is persisted so that a service that opens a data directory can seed its ID generator above the IDs that are
   * already in use without reading the metadata of every object to find them, which is what made opening a directory
   * cost its whole contents.
   *
   * @see com.robothy.s3.core.service.loader.DefaultFileSystemS3MetadataLoader
   */
  private Long maxId;

  /**
   * The keys whose objects changed since the metadata store last wrote the bucket, so that it only writes those. Not
   * part of the persisted state: a bucket that is read from the store has nothing left to write.
   *
   * @see BucketChangeScope
   */
  @JsonIgnore
  private final transient Set<String> changedObjectKeys = ConcurrentHashMap.newKeySet();

  /**
   * Whether every version of every changed object must be written, rather than only the versions that changed, e.g.
   * to seed another store, which holds none of them. Set by {@linkplain #markAllChanged()}.
   */
  @JsonIgnore
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private transient volatile boolean allVersionsChanged;

  /**
   * The keys whose multipart uploads changed since the metadata store last wrote the bucket.
   */
  @JsonIgnore
  private final transient Set<String> changedUploadKeys = ConcurrentHashMap.newKeySet();

  private long creationDate;

  /**
   * The <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateBucket.html#AmazonS3-CreateBucket-request-LocationConstraint">region</a> where the bucket is located.
   */
  private String region;

  /**
   * key - object key.
   */
  @JsonDeserialize(converter = UploadMetadataMapConverter.class)
  private NavigableMap<String, NavigableMap<String, UploadMetadata>> uploads = new ConcurrentSkipListMap<>();

  /**
   * null - default, user not set versioning.
   * true - enabled
   * false - suspended
   */
  private Boolean versioningEnabled;

  private String bucketName;

  private Collection<Map<String, String>> tagging;

  private AccessControlPolicy acl;

  private String policy;

  private String replication;

  private String encryption;
  
  private PublicAccessBlockConfiguration publicAccessBlock;

  private CORSConfiguration cors;

  /**
   * The lifecycle configuration of the bucket, which LocalS3 stores but never applies; {@code null} for none.
   */
  private BucketLifecycleConfiguration lifecycle;

  /**
   * The object lock configuration of the bucket; {@code null} if the bucket doesn't have Object Lock enabled. Once
   * enabled, Object Lock can't be disabled, and the versioning of the bucket can't be suspended.
   */
  private BucketObjectLockConfiguration objectLock;

  /**
   * Get metadata of the specified object.
   *
   * @param key the object key.
   * @return the object metadata of the specified object key.
   */
  public Optional<ObjectMetadata> getObjectMetadata(String key) {
    ObjectMetadataRef ref = objectMap.get(key);
    if (ref == null) {
      return Optional.empty();
    }
    if (BucketChangeScope.isChanging(bucketName)) {
      // The caller changes the object in place, e.g. adds a version or sets the tagging of one, which only the key
      // recorded here tells the metadata store about. The reference is pinned with it, so that the metadata the
      // caller is about to change isn't dropped from heap before it is written.
      changedObjectKeys.add(key);
      ref.pin();
    }
    return Optional.of(ref.get());
  }

  /**
   * The reference to the metadata of an object, which reads it when it is needed: for the callers that walk the
   * objects of a bucket without needing all of their metadata.
   *
   * @param key the object key.
   * @return the reference; empty if the key holds no object.
   */
  public Optional<ObjectMetadataRef> getObjectMetadataRef(String key) {
    return Optional.ofNullable(objectMap.get(key));
  }

  /**
   * Put an {@linkplain ObjectMetadata} instance.
   *
   * @param key the object key.
   * @param objectMetadata object metadata.
   * @return added object metadata.
   */
  public ObjectMetadata putObjectMetadata(String key, ObjectMetadata objectMetadata) {
    ObjectAssertions.assertObjectKeyIsValid(key);
    ObjectMetadataRef ref = ObjectMetadataRef.of(objectMetadata);
    ref.pin();
    objectMap.put(key, ref);
    changedObjectKeys.add(key);
    return objectMetadata;
  }

  /**
   * Put a reference to the metadata of an object, without reading it: called by a metadata store that loads a bucket.
   * The key is not recorded as changed, since the store it comes from holds it already.
   *
   * @param key the object key.
   * @param ref the reference to the metadata of the object.
   */
  public void putObjectMetadataRef(String key, ObjectMetadataRef ref) {
    objectMap.put(key, ref);
  }

  /**
   * Remove the metadata of an object, so that the metadata store deletes it.
   *
   * @param key the object key.
   * @return the removed object metadata; {@code null} if the key held none.
   */
  public ObjectMetadata removeObjectMetadata(String key) {
    ObjectMetadataRef removed = objectMap.remove(key);
    changedObjectKeys.add(key);
    return removed == null ? null : removed.get();
  }

  /**
   * Record that the multipart uploads of a key changed, so that the metadata store writes them. The services change
   * {@linkplain #getUploads()} in place, which the metadata can't see.
   *
   * @param key the object key of the upload.
   */
  public void markUploadsChanged(String key) {
    changedUploadKeys.add(key);
  }

  /**
   * Record every object and multipart upload of the bucket as changed, so that the metadata store writes the whole
   * bucket, e.g. to seed a store with a bucket that was read from another one.
   */
  public void markAllChanged() {
    allVersionsChanged = true;
    changedObjectKeys.addAll(objectMap.keySet());
    objectMap.values().forEach(ObjectMetadataRef::pin);
    changedUploadKeys.addAll(uploads.keySet());
  }

  /**
   * Record the greatest generated ID that this bucket references, keeping the greatest one seen.
   *
   * @param id the ID; ignored if it is not greater than the one recorded.
   */
  public void recordMaxId(long id) {
    if (maxId == null || id > maxId) {
      maxId = id;
    }
  }

  /**
   * Take the object keys that changed since this was last called, and forget them. Called by the metadata store when
   * it writes the bucket.
   *
   * @return the changed object keys.
   */
  public List<String> drainChangedObjectKeys() {
    return drain(changedObjectKeys);
  }

  /**
   * Take whether every version of the changed objects must be written, see {@linkplain #markAllChanged()}, and forget
   * it.
   *
   * @return {@code true} if the store must write every version of the changed objects.
   */
  public boolean drainAllVersionsChanged() {
    boolean all = allVersionsChanged;
    allVersionsChanged = false;
    return all;
  }

  /**
   * Take the keys whose multipart uploads changed since this was last called, and forget them.
   *
   * @return the keys whose uploads changed.
   */
  public List<String> drainChangedUploadKeys() {
    return drain(changedUploadKeys);
  }

  private static List<String> drain(Set<String> keys) {
    if (keys.isEmpty()) {
      return List.of();
    }
    List<String> drained = List.copyOf(keys);
    drained.forEach(keys::remove);
    return drained;
  }

  /**
   * Get tagging of current bucket.
   *
   * @return tagging of current bucket.
   */
  public Optional<Collection<Map<String, String>>> getTagging() {
    return Optional.ofNullable(tagging);
  }

  /**
   * Set tagging of current bucket. The tagging will be completely overridden if exists.
   *
   * @param tagging new tagging.
   */
  public void setTagging(Collection<Map<String, String>> tagging) {
    this.tagging = tagging;
  }

  /**
   * Get ACL of current bucket.
   *
   * @return the ACL of current bucket.
   */
  public Optional<AccessControlPolicy> getAcl() {
    return Optional.ofNullable(acl);
  }

  /**
   * Set ACL of this bucket.
   *
   * @param acl new ACL.
   */
  public void setAcl(AccessControlPolicy acl) {
    this.acl = acl;
  }

  public Optional<String> getPolicy() {
    return Optional.ofNullable(policy);
  }

  /**
   * Set policy JSON for current bucket.
   *
   * @param policy policy.
   */
  public void setPolicy(String policy) {
    this.policy = policy;
  }

  /**
   * Get replication configuration.
   *
   * @return replication configuration of current bucket.
   */
  public Optional<String> getReplication() {
    return Optional.ofNullable(replication);
  }

  /**
   * Set replication configuration.
   *
   * @param replication replication configuration.
   */
  public void setReplication(String replication) {
    this.replication = replication;
  }

  /**
   * Get the lifecycle configuration.
   *
   * @return the lifecycle configuration of the bucket; empty if it has none.
   */
  public Optional<BucketLifecycleConfiguration> getLifecycle() {
    return Optional.ofNullable(lifecycle);
  }

  /**
   * Get the object lock configuration.
   *
   * @return the object lock configuration of the bucket; empty if the bucket doesn't have Object Lock enabled.
   */
  public Optional<BucketObjectLockConfiguration> getObjectLock() {
    return Optional.ofNullable(objectLock);
  }

  /**
   * Get the bucket encryption.
   *
   * @return the bucket encryption.
   */
  public Optional<String> getEncryption() {
    return Optional.ofNullable(encryption);
  }

  /**
   * Set bucket encryption.
   *
   * @param encryption the bucket encryption.
   */
  public void setEncryption(String encryption) {
    this.encryption = encryption;
  }

  /**
   * The encryption that the objects of the bucket are stored with when a request names none, read from the
   * {@linkplain #getEncryption() encryption configuration}; see
   * {@linkplain BucketEncryptionConfigurations#defaultEncryption(String)}. The document is read once per configuration.
   *
   * @return the default encryption; {@code null} if the bucket has none.
   */
  @JsonIgnore
  public ServerSideEncryption getDefaultEncryption() {
    DefaultEncryption cached = defaultEncryption;
    if (cached == null || cached.configuration() != encryption) {
      cached = new DefaultEncryption(encryption, BucketEncryptionConfigurations.defaultEncryption(encryption));
      defaultEncryption = cached;
    }
    return cached.encryption();
  }

  /**
   * The default encryption read from a configuration document, which is compared by identity: a new document is read
   * again even if it is equal.
   */
  private record DefaultEncryption(String configuration, ServerSideEncryption encryption) {
  }

  @JsonIgnore
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private transient volatile DefaultEncryption defaultEncryption;

  /**
   * Get public access block configuration.
   *
   * @return the public access block configuration.
   */
  public Optional<PublicAccessBlockConfiguration> getPublicAccessBlock() {
    return Optional.ofNullable(publicAccessBlock);
  }

  /**
   * Set public access block configuration.
   *
   * @param publicAccessBlock public access block configuration.
   */
  public void setPublicAccessBlock(PublicAccessBlockConfiguration publicAccessBlock) {
    this.publicAccessBlock = publicAccessBlock;
  }

  /**
   * Get the CORS configuration.
   *
   * @return the CORS configuration of current bucket.
   */
  public Optional<CORSConfiguration> getCors() {
    return Optional.ofNullable(cors);
  }

  /**
   * Set the CORS configuration.
   *
   * @param cors CORS configuration; {@code null} to delete it.
   */
  public void setCors(CORSConfiguration cors) {
    this.cors = cors;
  }

  /**
   * Names the bucket and the settings that identify it, and leaves out the objects and the multipart
   * uploads it holds: a bucket under test holds an unbounded number of both, so a generated
   * {@code toString} would turn a {@code log.debug("{}", bucketMetadata)} into a dump of the whole bucket.
   * Read the contents through {@linkplain #getObjectMap()} and {@linkplain #getUploads()} instead.
   */
  @Override
  public String toString() {
    return "BucketMetadata(bucketName=" + bucketName + ", region=" + region
        + ", creationDate=" + creationDate + ", versioningEnabled=" + versioningEnabled + ")";
  }

}
