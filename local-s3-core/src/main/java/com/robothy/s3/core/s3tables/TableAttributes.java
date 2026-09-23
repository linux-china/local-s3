package com.robothy.s3.core.s3tables;

import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * What the S3 Tables API knows about a table beyond what an Iceberg catalog does: its ID, its version token, when it
 * was created and last changed, and the configuration that was stored on it.
 *
 * <p>The table itself — the pointer to its current {@code metadata.json} — lives in the Iceberg catalog of the table
 * bucket, so the S3 Tables API and the Iceberg REST endpoint of the same table bucket are two views of one catalog: a
 * table created with {@code CreateTable} is the table that Spark loads over REST, and a commit made over REST is what
 * {@code GetTableMetadataLocation} then answers. A table that was created over REST has no record here until this API
 * is first asked about it, and {@linkplain S3TablesService} then gives it one.
 *
 * <p><b>The version token</b> is the optimistic lock of the API. It is opaque, and a new one is drawn on every change
 * of the table, so a client that read the table, built a new metadata file and calls
 * {@code UpdateTableMetadataLocation} with the token it read commits only if nothing else changed the table meanwhile.
 * {@linkplain #metadataLocation()} is what makes that hold across the two protocols: it records the pointer the
 * current token was drawn for, so a commit made over the REST endpoint is noticed the next time this API reads the
 * table, and the token that a client is still holding is refused.
 *
 * @param tableId the ID that the service assigned the table, which its ARN names.
 * @param namespace the namespace that holds the table.
 * @param name the name of the table.
 * @param versionToken the current version token.
 * @param format the open table format of the table, which is {@code ICEBERG}.
 * @param type {@code customer} for a table that a client created, {@code aws} for one that a service manages.
 * @param createdAt when it was created, in milliseconds since the epoch.
 * @param createdBy the account that created it.
 * @param modifiedAt when it last changed, in milliseconds since the epoch.
 * @param modifiedBy the account that last changed it.
 * @param warehouseLocation the location that the files of the table are written under.
 * @param metadataLocation the metadata file that the table was at when {@linkplain #versionToken()} was drawn;
 *     {@code null} if it was never read.
 * @param configuration the configuration documents that were stored on it, by the keys of this class; empty if none
 *     were.
 */
public record TableAttributes(String tableId, String namespace, String name, String versionToken, String format,
                              String type, long createdAt, String createdBy, long modifiedAt, String modifiedBy,
                              String warehouseLocation, @Nullable String metadataLocation,
                              Map<String, String> configuration) {

  /**
   * The only open table format that Amazon S3 Tables serves, and the only one this API takes.
   */
  public static final String FORMAT_ICEBERG = "ICEBERG";

  /**
   * The type of a table that a client created, which every table of LocalS3 is.
   */
  public static final String TYPE_CUSTOMER = "customer";

  /**
   * The key of the {@code EncryptionConfiguration} of the table.
   */
  public static final String ENCRYPTION = "encryption";

  /**
   * The key of the {@code StorageClassConfiguration} of the table.
   */
  public static final String STORAGE_CLASS = "storageClass";

  /**
   * The key of the resource policy of the table.
   */
  public static final String POLICY = "policy";

  /**
   * The key of the {@code TableRecordExpirationConfigurationValue} of the table.
   */
  public static final String RECORD_EXPIRATION = "recordExpiration";

  /**
   * The key of the {@code TableReplicationConfiguration} of the table.
   */
  public static final String REPLICATION = "replication";

  public TableAttributes {
    configuration = configuration == null ? Map.of() : Map.copyOf(configuration);
    type = type == null ? TYPE_CUSTOMER : type;
    format = format == null ? FORMAT_ICEBERG : format;
  }

  /**
   * The key of the maintenance configuration of a type, so that the types the API grows don't each need a key of their
   * own here.
   *
   * @param maintenanceType the type of maintenance, e.g. {@code icebergCompaction}.
   * @return the key.
   */
  public static String maintenance(String maintenanceType) {
    return "maintenance:" + maintenanceType;
  }

  /**
   * A table as it is created.
   *
   * @param tableId the ID of the table.
   * @param namespace the namespace that holds it.
   * @param name the name of the table.
   * @param accountId the account of the service.
   * @param warehouseLocation the location its files are written under.
   * @param metadataLocation the metadata file it was created at; {@code null} if it has none.
   * @param configuration the configuration of the create request; empty if it carried none.
   * @return the record.
   */
  public static TableAttributes created(String tableId, String namespace, String name, String accountId,
                                        String warehouseLocation, @Nullable String metadataLocation,
                                        Map<String, String> configuration) {
    long now = System.currentTimeMillis();
    return new TableAttributes(tableId, namespace, name, S3TablesVersionTokens.next(), FORMAT_ICEBERG, TYPE_CUSTOMER,
        now, accountId, now, accountId, warehouseLocation, metadataLocation, configuration);
  }

  /**
   * This table as a commit leaves it: at the metadata file the commit wrote, with a new version token, modified now.
   *
   * @param newMetadataLocation the metadata file the table is now at.
   * @return a new record.
   */
  public TableAttributes committed(@Nullable String newMetadataLocation) {
    return new TableAttributes(tableId, namespace, name, S3TablesVersionTokens.next(), format, type, createdAt,
        createdBy, System.currentTimeMillis(), modifiedBy, warehouseLocation, newMetadataLocation, configuration);
  }

  /**
   * This table under another identifier, which a rename moves it to. The version token is drawn again: a rename is a
   * change of the table like any other.
   *
   * @param newNamespace the namespace of the result.
   * @param newName the name of the result.
   * @return a new record.
   */
  public TableAttributes renamedTo(String newNamespace, String newName) {
    return new TableAttributes(tableId, newNamespace, newName, S3TablesVersionTokens.next(), format, type, createdAt,
        createdBy, System.currentTimeMillis(), modifiedBy, warehouseLocation, metadataLocation, configuration);
  }

  /**
   * One configuration document of this table.
   *
   * @param key one of the keys of this class.
   * @return the document; {@code null} if none was stored under that key.
   */
  @Nullable
  public String get(String key) {
    return configuration.get(key);
  }

  /**
   * This table with one configuration document set or removed. The version token is left alone: configuring a table
   * isn't a change to the table the token guards, which is where its metadata is.
   *
   * @param key one of the keys of this class.
   * @param value the document; {@code null} to remove it.
   * @return a new record.
   */
  public TableAttributes with(String key, @Nullable String value) {
    Map<String, String> updated = new LinkedHashMap<>(configuration);
    if (value == null) {
      updated.remove(key);
    } else {
      updated.put(key, value);
    }
    return new TableAttributes(tableId, namespace, name, versionToken, format, type, createdAt, createdBy, modifiedAt,
        modifiedBy, warehouseLocation, metadataLocation, updated);
  }

  /**
   * This table with its replication configuration set or removed, and a new version token: the replication operations
   * of the API are guarded by the token the way a commit is.
   *
   * @param value the {@code TableReplicationConfiguration}; {@code null} to remove it.
   * @return a new record.
   */
  public TableAttributes withReplication(@Nullable String value) {
    Map<String, String> updated = new LinkedHashMap<>(configuration);
    if (value == null) {
      updated.remove(REPLICATION);
    } else {
      updated.put(REPLICATION, value);
    }
    return new TableAttributes(tableId, namespace, name, S3TablesVersionTokens.next(), format, type, createdAt,
        createdBy, System.currentTimeMillis(), modifiedBy, warehouseLocation, metadataLocation, updated);
  }

}
