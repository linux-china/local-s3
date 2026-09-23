package com.robothy.s3.core.s3tables;

import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.h2.mvstore.MVMap;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the table buckets of the S3 Tables API, and what that API knows about their namespaces and tables beyond what
 * an Iceberg catalog does, in the {@linkplain LocalS3Store} of a LocalS3 service — beside its S3 buckets, its vector
 * buckets and its Iceberg catalog, so that one file still holds everything a data directory knows.
 *
 * <p>The namespaces and the tables themselves are <em>not</em> kept here. Each table bucket has an Iceberg catalog of
 * its own, in maps named after it, and that catalog is the one truth about which namespaces and tables exist and where
 * each table's metadata file is; see {@linkplain #catalogMaps}. This store adds only what the S3 Tables API answers and
 * an Iceberg catalog has no place for: the IDs, the timestamps, the version tokens and the configuration documents.
 *
 * <p><b>Concurrency.</b> {@linkplain MVMap} is thread-safe, and the writes that must not race — creating a table
 * bucket, a namespace or a table under a name that may be taken — go through the {@code putIfAbsent} and
 * {@code replace} of the map rather than through a read followed by a write.
 */
public final class S3TablesStore {

  /**
   * The map of the table buckets, by name. Prefixed like the maps of the vector buckets and of the Iceberg catalog, so
   * that it can't collide with a map named after an S3 bucket.
   */
  static final String BUCKETS_MAP = "s3tables/buckets";

  /**
   * The map of the namespace attributes, by {@linkplain #namespaceKey}.
   */
  static final String NAMESPACES_MAP = "s3tables/namespaces";

  /**
   * The map of the table attributes, by {@linkplain #tableKey}.
   */
  static final String TABLES_MAP = "s3tables/tables";

  /**
   * The map of the table keys, by table ID, which is what an operation that addresses a table by its ARN looks it up
   * through: an ARN names the ID that the service assigned, not the name the table currently has, so a renamed table
   * keeps the ARN it was created with.
   */
  static final String TABLE_IDS_MAP = "s3tables/tableIds";

  /**
   * The map of the tags of the resources, by ARN.
   */
  static final String TAGS_MAP = "s3tables/tags";

  /**
   * The prefix of the maps of the Iceberg catalog of a table bucket.
   */
  static final String CATALOG_MAP_PREFIX = "s3tables/catalog/";

  /**
   * Separates the parts of the keys of this store. It is the unit separator, which no name of the API holds.
   */
  private static final char SEPARATOR = '\u001F';

  private final LocalS3Store localS3Store;

  private final MVMap<String, String> buckets;

  private final MVMap<String, String> namespaces;

  private final MVMap<String, String> tables;

  private final MVMap<String, String> tableIds;

  private final MVMap<String, String> tags;

  private S3TablesStore(LocalS3Store localS3Store) {
    this.localS3Store = Objects.requireNonNull(localS3Store);
    this.buckets = localS3Store.store().openMap(BUCKETS_MAP);
    this.namespaces = localS3Store.store().openMap(NAMESPACES_MAP);
    this.tables = localS3Store.store().openMap(TABLES_MAP);
    this.tableIds = localS3Store.store().openMap(TABLE_IDS_MAP);
    this.tags = localS3Store.store().openMap(TAGS_MAP);
  }

  /**
   * Open the table buckets of a store.
   *
   * @param localS3Store the store of the service, which this takes no hold of: the caller owns it.
   * @return the store of the table buckets.
   */
  public static S3TablesStore create(LocalS3Store localS3Store) {
    return new S3TablesStore(localS3Store);
  }

  /**
   * The names of the two maps that the Iceberg catalog of a table bucket is kept in, which are named after the table
   * bucket so that the catalogs of two table buckets cannot see each other's namespaces or tables.
   *
   * @param tableBucket the name of the table bucket.
   * @return the name of its namespaces map and the name of its tables map.
   */
  public static CatalogMaps catalogMaps(String tableBucket) {
    return new CatalogMaps(CATALOG_MAP_PREFIX + tableBucket + "/namespaces",
        CATALOG_MAP_PREFIX + tableBucket + "/tables");
  }

  /**
   * The names of the maps that the Iceberg catalog of a table bucket is kept in.
   *
   * @param namespacesMap the name of the map of the namespaces.
   * @param tablesMap the name of the map of the tables.
   */
  public record CatalogMaps(String namespacesMap, String tablesMap) {
  }

  /**
   * Copy the table buckets of another store into this one, which an {@code IN_MEMORY} service does with the store of
   * the data directory it starts from. The Iceberg catalogs of the table buckets are copied by the caller, which knows
   * how to open them.
   *
   * @param source the store to copy from, e.g. one opened {@linkplain LocalS3Store#readOnly read-only}.
   */
  public void loadFrom(S3TablesStore source) {
    buckets.putAll(source.buckets);
    namespaces.putAll(source.namespaces);
    tables.putAll(source.tables);
    tableIds.putAll(source.tableIds);
    tags.putAll(source.tags);
  }

  /**
   * Forget every table bucket, which is what a reset of an {@code IN_MEMORY} service does before it loads its initial
   * records again. The Iceberg catalogs of the table buckets are cleared by the caller.
   */
  public void clear() {
    buckets.clear();
    namespaces.clear();
    tables.clear();
    tableIds.clear();
    tags.clear();
  }

  /**
   * The number of records kept, e.g. to log what a service loaded.
   *
   * @return the number of table buckets, namespaces and tables.
   */
  public int size() {
    return buckets.size() + namespaces.size() + tables.size();
  }

  /*
   * Table buckets.
   */

  /**
   * The table bucket of a name.
   *
   * @param name the name.
   * @return the record; {@code null} if there is none.
   */
  @Nullable
  public TableBucketRecord getBucket(String name) {
    String json = buckets.get(name);
    return json == null ? null : JsonUtils.fromJson(json, TableBucketRecord.class);
  }

  /**
   * Store a table bucket if its name holds none.
   *
   * @param record the table bucket.
   * @return {@code true} if it was stored; {@code false} if the name is taken.
   */
  public boolean putBucketIfAbsent(TableBucketRecord record) {
    boolean created = buckets.putIfAbsent(record.name(), JsonUtils.toJson(record)) == null;
    if (created) {
      commit();
    }
    return created;
  }

  /**
   * Store a table bucket, replacing the one of the same name.
   *
   * @param record the table bucket.
   */
  public void putBucket(TableBucketRecord record) {
    buckets.put(record.name(), JsonUtils.toJson(record));
    commit();
  }

  /**
   * Remove a table bucket.
   *
   * @param name the name.
   * @return {@code true} if it was removed.
   */
  public boolean removeBucket(String name) {
    boolean removed = buckets.remove(name) != null;
    if (removed) {
      commit();
    }
    return removed;
  }

  /**
   * The table buckets, ordered by name.
   *
   * @return the records.
   */
  public List<TableBucketRecord> listBuckets() {
    List<TableBucketRecord> found = new ArrayList<>(buckets.size());
    for (String json : buckets.values()) {
      if (json != null) {
        found.add(JsonUtils.fromJson(json, TableBucketRecord.class));
      }
    }
    return found;
  }

  /*
   * Namespaces.
   */

  /**
   * The attributes of a namespace.
   *
   * @param tableBucket the name of the table bucket.
   * @param namespace the name of the namespace.
   * @return the attributes; {@code null} if there are none.
   */
  @Nullable
  public NamespaceAttributes getNamespace(String tableBucket, String namespace) {
    String json = namespaces.get(namespaceKey(tableBucket, namespace));
    return json == null ? null : JsonUtils.fromJson(json, NamespaceAttributes.class);
  }

  /**
   * Store the attributes of a namespace.
   *
   * @param tableBucket the name of the table bucket.
   * @param namespace the name of the namespace.
   * @param attributes the attributes.
   */
  public void putNamespace(String tableBucket, String namespace, NamespaceAttributes attributes) {
    namespaces.put(namespaceKey(tableBucket, namespace), JsonUtils.toJson(attributes));
    commit();
  }

  /**
   * Remove the attributes of a namespace.
   *
   * @param tableBucket the name of the table bucket.
   * @param namespace the name of the namespace.
   */
  public void removeNamespace(String tableBucket, String namespace) {
    if (namespaces.remove(namespaceKey(tableBucket, namespace)) != null) {
      commit();
    }
  }

  /*
   * Tables.
   */

  /**
   * The attributes of a table.
   *
   * @param tableBucket the name of the table bucket.
   * @param namespace the name of the namespace.
   * @param name the name of the table.
   * @return the attributes; {@code null} if there are none.
   */
  @Nullable
  public TableAttributes getTable(String tableBucket, String namespace, String name) {
    String json = tables.get(tableKey(tableBucket, namespace, name));
    return json == null ? null : JsonUtils.fromJson(json, TableAttributes.class);
  }

  /**
   * The table of an ID, which is how an operation that addresses a table by its ARN finds it.
   *
   * @param tableBucket the name of the table bucket.
   * @param tableId the ID of the table.
   * @return the attributes; {@code null} if there is no such table.
   */
  @Nullable
  public TableAttributes getTableById(String tableBucket, String tableId) {
    String key = tableIds.get(tableIdKey(tableBucket, tableId));
    if (key == null) {
      return null;
    }
    String json = tables.get(key);
    return json == null ? null : JsonUtils.fromJson(json, TableAttributes.class);
  }

  /**
   * Store the attributes of a table if its name holds none, which is how the attributes of a table that was created
   * over the Iceberg REST endpoint are given to it without two concurrent readers giving it two different IDs.
   *
   * @param tableBucket the name of the table bucket.
   * @param attributes the attributes.
   * @return {@code true} if they were stored; {@code false} if the table already had some.
   */
  public boolean putTableIfAbsent(String tableBucket, TableAttributes attributes) {
    String key = tableKey(tableBucket, attributes.namespace(), attributes.name());
    boolean created = tables.putIfAbsent(key, JsonUtils.toJson(attributes)) == null;
    if (created) {
      tableIds.put(tableIdKey(tableBucket, attributes.tableId()), key);
      commit();
    }
    return created;
  }

  /**
   * Store the attributes of a table, and index it by its ID.
   *
   * @param tableBucket the name of the table bucket.
   * @param attributes the attributes.
   */
  public void putTable(String tableBucket, TableAttributes attributes) {
    String key = tableKey(tableBucket, attributes.namespace(), attributes.name());
    tables.put(key, JsonUtils.toJson(attributes));
    tableIds.put(tableIdKey(tableBucket, attributes.tableId()), key);
    commit();
  }

  /**
   * Replace the attributes of a table if its version token is still the expected one, which is the optimistic lock of
   * the API.
   *
   * @param tableBucket the name of the table bucket.
   * @param expected the attributes the caller read.
   * @param updated the attributes to store.
   * @return {@code true} if they were replaced; {@code false} if the table changed or is gone.
   */
  public boolean replaceTable(String tableBucket, TableAttributes expected, TableAttributes updated) {
    String key = tableKey(tableBucket, expected.namespace(), expected.name());
    boolean replaced = tables.replace(key, JsonUtils.toJson(expected), JsonUtils.toJson(updated));
    if (replaced) {
      tableIds.put(tableIdKey(tableBucket, updated.tableId()), key);
      commit();
    }
    return replaced;
  }

  /**
   * Remove the attributes of a table, and its entry in the index by ID.
   *
   * @param tableBucket the name of the table bucket.
   * @param attributes the attributes of the table.
   */
  public void removeTable(String tableBucket, TableAttributes attributes) {
    tables.remove(tableKey(tableBucket, attributes.namespace(), attributes.name()));
    tableIds.remove(tableIdKey(tableBucket, attributes.tableId()));
    commit();
  }

  /**
   * The tables of a table bucket, or of one of its namespaces, ordered by key.
   *
   * @param tableBucket the name of the table bucket.
   * @param namespace the name of the namespace; {@code null} for every namespace of the table bucket.
   * @return the attributes of the tables.
   */
  public List<TableAttributes> listTables(String tableBucket, @Nullable String namespace) {
    String prefix = namespace == null ? tableBucket + SEPARATOR
        : tableBucket + SEPARATOR + namespace + SEPARATOR;
    List<TableAttributes> found = new ArrayList<>();
    Iterator<String> keys = tables.keyIterator(prefix);
    while (keys.hasNext()) {
      String key = keys.next();
      if (!key.startsWith(prefix)) {
        break;
      }
      String json = tables.get(key);
      if (json != null) {
        found.add(JsonUtils.fromJson(json, TableAttributes.class));
      }
    }
    return found;
  }

  /*
   * Tags.
   */

  /**
   * The tags of a resource.
   *
   * @param arn the ARN of the resource.
   * @return the tags, in the order they were stored; empty if it has none.
   */
  public Map<String, String> getTags(String arn) {
    String json = tags.get(arn);
    if (json == null) {
      return new LinkedHashMap<>();
    }
    @SuppressWarnings("unchecked")
    Map<String, String> stored = JsonUtils.fromJson(json, LinkedHashMap.class);
    return new LinkedHashMap<>(stored);
  }

  /**
   * Store the tags of a resource.
   *
   * @param arn the ARN of the resource.
   * @param values the tags; empty to remove them all.
   */
  public void putTags(String arn, Map<String, String> values) {
    if (values.isEmpty()) {
      tags.remove(arn);
    } else {
      tags.put(arn, JsonUtils.toJson(values));
    }
    commit();
  }

  /**
   * Remove the tags of a resource, which a delete of that resource does.
   *
   * @param arn the ARN of the resource.
   */
  public void removeTags(String arn) {
    if (tags.remove(arn) != null) {
      commit();
    }
  }

  /**
   * The store that this keeps its records in, which the caller opens the Iceberg catalogs of the table buckets
   * through.
   *
   * @return the store of the service.
   */
  public LocalS3Store localS3Store() {
    return localS3Store;
  }

  private static String namespaceKey(String tableBucket, String namespace) {
    return tableBucket + SEPARATOR + namespace;
  }

  private static String tableKey(String tableBucket, String namespace, String name) {
    return tableBucket + SEPARATOR + namespace + SEPARATOR + name;
  }

  private static String tableIdKey(String tableBucket, String tableId) {
    return tableBucket + SEPARATOR + tableId;
  }

  /**
   * Commit the change, if the store commits every change; a {@code FAST} store leaves that to its background thread,
   * and an in-memory store writes nothing either way.
   */
  private void commit() {
    if (localS3Store.commitsEveryChange()) {
      localS3Store.flush();
    }
  }

}
