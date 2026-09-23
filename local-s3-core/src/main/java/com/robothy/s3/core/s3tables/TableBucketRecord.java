package com.robothy.s3.core.s3tables;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A table bucket, as {@linkplain S3TablesStore} keeps it.
 *
 * <p>A table bucket of Amazon S3 is a bucket of its own kind: it holds tables rather than objects, and its files are
 * not reachable through the S3 API at all. LocalS3 keeps it simpler, and truer to what a test needs — the tables of a
 * table bucket live in an ordinary bucket of the same service, named by {@link #warehouse()}. The engine that loaded a
 * table then reads and writes its data files with its own {@code S3FileIO} against the same endpoint, with the same
 * credentials, and a test can look at what was written with an {@code S3Client}.
 *
 * <p>What the API can be configured with — encryption, storage class, a resource policy, maintenance, metrics,
 * replication — is kept as one map of JSON documents rather than as a field each, see {@linkplain #configuration()}.
 * None of it changes how LocalS3 behaves, so none of it deserves a field: what a client stores is what it reads back,
 * whatever Amazon would have done with it.
 *
 * @param name the name of the table bucket, which is unique in the service.
 * @param tableBucketId the ID that the service assigned it.
 * @param ownerAccountId the account that owns it, which is the one of the service.
 * @param createdAt when it was created, in milliseconds since the epoch.
 * @param type {@code customer} for a table bucket that was created through the API, {@code aws} for one that a
 *     service created. LocalS3 creates none of its own, so this is {@code customer}.
 * @param warehouse the {@code s3://} location of the bucket that the tables of this table bucket are written to,
 *     without a trailing {@code /}.
 * @param configuration the configuration documents that were stored on it, by the keys of this class; empty if none
 *     were.
 */
public record TableBucketRecord(String name, String tableBucketId, String ownerAccountId, long createdAt,
                                String type, String warehouse, Map<String, String> configuration) {

  /**
   * The type of a table bucket that a client created, which every table bucket of LocalS3 is.
   */
  public static final String TYPE_CUSTOMER = "customer";

  /**
   * The key of the {@code EncryptionConfiguration} of the table bucket.
   */
  public static final String ENCRYPTION = "encryption";

  /**
   * The key of the {@code StorageClassConfiguration} of the table bucket.
   */
  public static final String STORAGE_CLASS = "storageClass";

  /**
   * The key of the resource policy of the table bucket.
   */
  public static final String POLICY = "policy";

  /**
   * The key of the ID of the metrics configuration of the table bucket.
   */
  public static final String METRICS_ID = "metricsId";

  /**
   * The key of the {@code TableBucketReplicationConfiguration} of the table bucket.
   */
  public static final String REPLICATION = "replication";

  /**
   * The key of the version token that guards the replication configuration of the table bucket.
   */
  public static final String REPLICATION_VERSION = "replicationVersionToken";

  public TableBucketRecord {
    configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    type = type == null ? TYPE_CUSTOMER : type;
  }

  /**
   * The key of the maintenance configuration of a type, so that the types the API grows don't each need a key of their
   * own here.
   *
   * @param maintenanceType the type of maintenance, e.g. {@code icebergUnreferencedFileRemoval}.
   * @return the key.
   */
  public static String maintenance(String maintenanceType) {
    return "maintenance:" + maintenanceType;
  }

  /**
   * A table bucket as it is created: with only the configuration its create request carried.
   *
   * @param name the name.
   * @param tableBucketId the ID of the table bucket.
   * @param ownerAccountId the account of the service.
   * @param warehouse the location of the bucket its tables are written to.
   * @param configuration the configuration of the create request; empty if it carried none.
   * @return the record.
   */
  public static TableBucketRecord created(String name, String tableBucketId, String ownerAccountId, String warehouse,
                                          Map<String, String> configuration) {
    return new TableBucketRecord(name, tableBucketId, ownerAccountId, System.currentTimeMillis(), TYPE_CUSTOMER,
        warehouse, configuration);
  }

  /**
   * One configuration document of this table bucket.
   *
   * @param key one of the keys of this class.
   * @return the document; {@code null} if none was stored under that key.
   */
  @Nullable
  public String get(String key) {
    return configuration.get(key);
  }

  /**
   * This table bucket with one configuration document set or removed.
   *
   * @param key one of the keys of this class.
   * @param value the document; {@code null} to remove it.
   * @return a new record.
   */
  public TableBucketRecord with(String key, @Nullable String value) {
    Map<String, String> updated = new LinkedHashMap<>(configuration);
    if (value == null) {
      updated.remove(key);
    } else {
      updated.put(key, value);
    }
    return new TableBucketRecord(name, tableBucketId, ownerAccountId, createdAt, type, warehouse, updated);
  }

  /**
   * This table bucket with its tables written somewhere else, which creating it settles once the bucket behind it is
   * created.
   *
   * @param newWarehouse the location of the bucket its tables are written to.
   * @return a new record.
   */
  public TableBucketRecord withWarehouse(String newWarehouse) {
    return new TableBucketRecord(name, tableBucketId, ownerAccountId, createdAt, type, newWarehouse, configuration);
  }

}
