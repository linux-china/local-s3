package com.robothy.s3.core.iceberg;

import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.h2.mvstore.MVMap;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the namespaces and the tables of the Iceberg catalog in the {@linkplain LocalS3Store} of a LocalS3 service,
 * beside its S3 buckets and its vector buckets: one file holds everything a data directory knows, so that a copy of
 * the directory is a consistent point of the whole service.
 *
 * <p>What is kept here is small — a namespace is its levels and its properties, and a table is a pointer to the
 * metadata file that it currently is — and the metadata files themselves are objects of the warehouse bucket, written
 * through the S3 services like any other object.
 *
 * <p>An {@code IN_MEMORY} service opens an in-memory store and, if it starts from a data directory, copies the records
 * of that directory into it, so its catalog starts as the directory left it and changes nothing there. That copy is
 * what {@linkplain #loadFrom} does, and it is cheap for the same reason: the records are pointers, not tables.
 *
 * <p><b>Concurrency.</b> {@linkplain MVMap} is thread-safe, and {@linkplain #replaceTable} is a compare-and-set, which
 * is what makes a commit atomic: two commits that start from the same metadata file both build a new one, and only the
 * one that swaps the pointer first wins. The loser is answered {@code 409 CommitFailedException} and its client
 * retries against the table as it now is, which is how a real catalog behaves.
 */
public final class IcebergCatalogStore {

  /**
   * The map of the namespaces, by {@linkplain IcebergIdentifier#namespaceKey}. Prefixed like the maps of the vector
   * buckets, so that it can't collide with a map named after an S3 bucket.
   */
  static final String NAMESPACES_MAP = "iceberg/namespaces";

  /**
   * The map of the tables and the views, by {@linkplain IcebergIdentifier#key()}.
   */
  static final String TABLES_MAP = "iceberg/tables";

  private final LocalS3Store localS3Store;

  private final MVMap<String, String> namespaces;

  private final MVMap<String, String> tables;

  private IcebergCatalogStore(LocalS3Store localS3Store) {
    this.localS3Store = Objects.requireNonNull(localS3Store);
    this.namespaces = localS3Store.store().openMap(NAMESPACES_MAP);
    this.tables = localS3Store.store().openMap(TABLES_MAP);
  }

  /**
   * Open the catalog of a store.
   *
   * @param localS3Store the store of the service, which this takes no hold of: the caller owns it.
   * @return the catalog store.
   */
  public static IcebergCatalogStore create(LocalS3Store localS3Store) {
    return new IcebergCatalogStore(localS3Store);
  }

  /**
   * Copy the namespaces and the tables of another store into this one, which an {@code IN_MEMORY} service does with
   * the store of the data directory it starts from. What is already here is left alone, so this is only called on an
   * empty catalog.
   *
   * @param source the store to copy from, e.g. one opened {@linkplain LocalS3Store#readOnly read-only}.
   */
  public void loadFrom(IcebergCatalogStore source) {
    namespaces.putAll(source.namespaces);
    tables.putAll(source.tables);
  }

  /**
   * Forget every namespace and table, which is what a reset of an {@code IN_MEMORY} service does before it loads its
   * initial records again. The metadata files of the tables aren't deleted here: they are objects of the service, and
   * the reset of its S3 half drops them.
   */
  public void clear() {
    namespaces.clear();
    tables.clear();
  }

  /**
   * The number of namespaces and tables kept, e.g. to log what a service loaded.
   *
   * @return the number of records.
   */
  public int size() {
    return namespaces.size() + tables.size();
  }

  /*
   * Namespaces.
   */

  /**
   * The namespace of a key.
   *
   * @param key the key, see {@linkplain IcebergIdentifier#namespaceKey}.
   * @return the namespace; {@code null} if there is none.
   */
  @Nullable
  public IcebergNamespaceRecord getNamespace(String key) {
    String json = namespaces.get(key);
    return json == null ? null : JsonUtils.fromJson(json, IcebergNamespaceRecord.class);
  }

  /**
   * Store a namespace, replacing the one of the same key.
   *
   * @param record the namespace.
   */
  public void putNamespace(IcebergNamespaceRecord record) {
    namespaces.put(record.key(), JsonUtils.toJson(record));
    commit();
  }

  /**
   * Store a namespace if its key holds none.
   *
   * @param record the namespace.
   * @return {@code true} if it was stored; {@code false} if the namespace already exists.
   */
  public boolean putNamespaceIfAbsent(IcebergNamespaceRecord record) {
    boolean created = namespaces.putIfAbsent(record.key(), JsonUtils.toJson(record)) == null;
    if (created) {
      commit();
    }
    return created;
  }

  /**
   * Remove a namespace. Its tables aren't removed: the caller checks that it holds none.
   *
   * @param key the key of the namespace.
   * @return {@code true} if it was removed; {@code false} if there was none.
   */
  public boolean removeNamespace(String key) {
    boolean removed = namespaces.remove(key) != null;
    if (removed) {
      commit();
    }
    return removed;
  }

  /**
   * The namespaces directly under a parent, i.e. the ones with one level more than it, e.g. {@code db.schema} under
   * {@code db} but not {@code db.schema.inner}.
   *
   * @param parent the levels of the parent; empty for the top-level namespaces.
   * @return the namespaces, ordered by key.
   */
  public List<IcebergNamespaceRecord> listNamespaces(List<String> parent) {
    List<IcebergNamespaceRecord> children = new ArrayList<>();
    int depth = parent.size() + 1;
    String prefix = parent.isEmpty() ? "" : IcebergIdentifier.namespaceKey(parent) + IcebergIdentifier.SEPARATOR;
    Iterator<String> keys = namespaces.keyIterator(prefix);
    while (keys.hasNext()) {
      String key = keys.next();
      if (!key.startsWith(prefix)) {
        break;
      }
      IcebergNamespaceRecord record = getNamespace(key);
      if (record != null && record.levels().size() == depth) {
        children.add(record);
      }
    }
    return children;
  }

  /*
   * Tables and views.
   */

  /**
   * The table or the view of an identifier.
   *
   * @param identifier the identifier.
   * @return the record; {@code null} if there is none.
   */
  @Nullable
  public IcebergTableRecord getTable(IcebergIdentifier identifier) {
    String json = tables.get(identifier.key());
    return json == null ? null : JsonUtils.fromJson(json, IcebergTableRecord.class);
  }

  /**
   * Store a table if its identifier holds none, which is how a table is created without overwriting one that another
   * request created meanwhile.
   *
   * @param record the table.
   * @return {@code true} if it was stored; {@code false} if the identifier is taken.
   */
  public boolean putTableIfAbsent(IcebergTableRecord record) {
    boolean created = tables.putIfAbsent(record.key(), JsonUtils.toJson(record)) == null;
    if (created) {
      commit();
    }
    return created;
  }

  /**
   * Replace the pointer of a table, if it is still the one the commit started from. This is the step that decides a
   * commit: of two commits built from the same metadata file, the first to get here wins and the second is told that
   * the table moved under it.
   *
   * @param expected the record the commit started from.
   * @param updated the record the commit leaves, at its new metadata file.
   * @return {@code true} if the pointer was replaced; {@code false} if the table has changed or is gone.
   */
  public boolean replaceTable(IcebergTableRecord expected, IcebergTableRecord updated) {
    boolean replaced = tables.replace(expected.key(), JsonUtils.toJson(expected), JsonUtils.toJson(updated));
    if (replaced) {
      commit();
    }
    return replaced;
  }

  /**
   * Remove a table.
   *
   * @param identifier the identifier.
   * @return the record that was removed; {@code null} if there was none.
   */
  @Nullable
  public IcebergTableRecord removeTable(IcebergIdentifier identifier) {
    String json = tables.remove(identifier.key());
    if (json == null) {
      return null;
    }
    commit();
    return JsonUtils.fromJson(json, IcebergTableRecord.class);
  }

  /**
   * Whether any table or view keeps its metadata file under a location, which is what makes purging that location
   * unsafe: the files there belong to something the catalog still points at.
   *
   * @param location the location, without a trailing {@code /}, e.g. {@code s3://warehouse/db/orders}.
   * @return {@code true} if a table or a view of the catalog lives under it.
   */
  public boolean anyTableUnder(String location) {
    String prefix = IcebergJson.stripTrailingSlash(location) + "/";
    for (String json : tables.values()) {
      if (json != null && JsonUtils.fromJson(json, IcebergTableRecord.class).metadataLocation().startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The tables of a namespace, or its views.
   *
   * @param namespace the levels of the namespace.
   * @param views whether to list the views rather than the tables.
   * @return the records, ordered by key.
   */
  public List<IcebergTableRecord> listTables(List<String> namespace, boolean views) {
    List<IcebergTableRecord> found = new ArrayList<>();
    String prefix = IcebergIdentifier.tablePrefix(namespace);
    Iterator<String> keys = tables.keyIterator(prefix);
    while (keys.hasNext()) {
      String key = keys.next();
      if (!key.startsWith(prefix)) {
        break;
      }
      String json = tables.get(key);
      if (json == null) {
        continue;
      }
      IcebergTableRecord record = JsonUtils.fromJson(json, IcebergTableRecord.class);
      if (record.view() == views) {
        found.add(record);
      }
    }
    return found;
  }

  /**
   * Whether a namespace holds a table or a view, which a drop of the namespace refuses.
   *
   * @param namespace the levels of the namespace.
   * @return {@code true} if it holds one.
   */
  public boolean hasTables(List<String> namespace) {
    String prefix = IcebergIdentifier.tablePrefix(namespace);
    Iterator<String> keys = tables.keyIterator(prefix);
    return keys.hasNext() && keys.next().startsWith(prefix);
  }

  /**
   * Whether a namespace holds another namespace, which a drop of it refuses like a namespace with tables.
   *
   * @param namespace the levels of the namespace.
   * @return {@code true} if it holds one.
   */
  public boolean hasChildNamespaces(List<String> namespace) {
    String prefix = IcebergIdentifier.namespaceKey(namespace) + IcebergIdentifier.SEPARATOR;
    Iterator<String> keys = namespaces.keyIterator(prefix);
    return keys.hasNext() && keys.next().startsWith(prefix);
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
