package com.robothy.s3.core.iceberg;

import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.h2.mvstore.MVMap;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the namespaces and the table pointers of the Iceberg catalog in the {@linkplain LocalS3Store} of a LocalS3
 * service, beside its buckets, so a copy of the data directory is a consistent point of the whole service. The metadata
 * files themselves are objects of the warehouse bucket.
 *
 * <p>An {@code IN_MEMORY} service that starts from a data directory copies its records into an in-memory store
 * ({@linkplain #loadFrom}), which is cheap because the records are pointers, not tables.
 *
 * <p>{@linkplain #replaceTable} is a compare-and-set on a thread-safe {@linkplain MVMap}, which is what makes a commit
 * atomic: the loser of two commits from the same metadata file is answered {@code 409 CommitFailedException}.
 *
 * <p>The steps that read one map and write the other — a table created in a namespace, a namespace dropped once it is
 * empty, a catalog closed once it is empty — are serialized on the namespaces map, see
 * {@code docs/iceberg-impl.md#提交与并发}.
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

  /**
   * Set once the table bucket of this catalog is deleted; guarded by {@linkplain #namespaces}.
   */
  private boolean closed;

  private IcebergCatalogStore(LocalS3Store localS3Store, String namespacesMap, String tablesMap) {
    this.localS3Store = Objects.requireNonNull(localS3Store);
    this.namespaces = localS3Store.store().openMap(Objects.requireNonNull(namespacesMap, "namespacesMap"));
    this.tables = localS3Store.store().openMap(Objects.requireNonNull(tablesMap, "tablesMap"));
  }

  /**
   * Open the catalog of a store.
   *
   * @param localS3Store the store of the service, which this takes no hold of: the caller owns it.
   * @return the catalog store.
   */
  public static IcebergCatalogStore create(LocalS3Store localS3Store) {
    return new IcebergCatalogStore(localS3Store, NAMESPACES_MAP, TABLES_MAP);
  }

  /**
   * Open a catalog of a store that is kept in maps of its own, which is how each table bucket of the
   * {@link com.robothy.s3.core.s3tables.S3TablesService S3 Tables API} gets a catalog that no other table bucket can
   * see: one LocalS3 service then serves many catalogs, rather than the single one of
   * {@linkplain #create(LocalS3Store)} that its Iceberg REST endpoint serves by default.
   *
   * @param localS3Store the store of the service, which this takes no hold of: the caller owns it.
   * @param namespacesMap the name of the map to keep the namespaces in.
   * @param tablesMap the name of the map to keep the tables in.
   * @return the catalog store.
   */
  public static IcebergCatalogStore create(LocalS3Store localS3Store, String namespacesMap, String tablesMap) {
    return new IcebergCatalogStore(localS3Store, namespacesMap, tablesMap);
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
   * Replace a namespace, if it is still the record the caller read: an update of its properties must neither bring
   * back a namespace that was dropped meanwhile nor overwrite the properties another update set meanwhile.
   *
   * @param expected the record the caller read.
   * @param updated the record to leave.
   * @return {@code true} if it was replaced; {@code false} if the namespace has changed or is gone.
   */
  public boolean replaceNamespace(IcebergNamespaceRecord expected, IcebergNamespaceRecord updated) {
    String updatedJson = JsonUtils.toJson(updated);
    while (true) {
      // Compared as values, like replaceTable, so that a record written by an older LocalS3 can still be updated.
      String json = namespaces.get(expected.key());
      if (json == null || !JsonUtils.fromJson(json, IcebergNamespaceRecord.class).equals(expected)) {
        return false;
      }
      if (namespaces.replace(expected.key(), json, updatedJson)) {
        commit();
        return true;
      }
    }
  }

  /**
   * Store a namespace if its key holds none.
   *
   * @param record the namespace.
   * @return {@code true} if it was stored; {@code false} if the namespace already exists.
   */
  public boolean putNamespaceIfAbsent(IcebergNamespaceRecord record) {
    boolean created;
    synchronized (namespaces) {
      requireOpen();
      created = namespaces.putIfAbsent(record.key(), JsonUtils.toJson(record)) == null;
    }
    if (created) {
      commit();
    }
    return created;
  }

  /**
   * Remove a namespace that holds neither a table nor another namespace. The check and the removal are one step, so a
   * table created meanwhile either lands before it, and the namespace stays, or is refused after it.
   *
   * @param namespace the levels of the namespace.
   * @throws IcebergCatalogException if the namespace doesn't exist, or isn't empty.
   */
  public void removeEmptyNamespace(List<String> namespace) {
    synchronized (namespaces) {
      if (hasTables(namespace) || hasChildNamespaces(namespace)) {
        throw IcebergCatalogException.namespaceNotEmpty(namespace);
      }
      if (namespaces.remove(IcebergIdentifier.namespaceKey(namespace)) == null) {
        throw IcebergCatalogException.noSuchNamespace(namespace);
      }
    }
    commit();
  }

  /**
   * Close the catalog if it holds neither a namespace nor a table, after which nothing can be created in it, which is
   * what deleting its table bucket does: a namespace created by a request that found the table bucket just before it
   * was deleted would otherwise be left behind in it.
   *
   * @return {@code true} if it is closed; {@code false} if it isn't empty.
   */
  public boolean closeIfEmpty() {
    synchronized (namespaces) {
      if (namespaces.isEmpty() && tables.isEmpty()) {
        closed = true;
      }
      return closed;
    }
  }

  /**
   * The namespaces directly under a parent, i.e. the ones with one level more than it, e.g. {@code db.schema} under
   * {@code db} but not {@code db.schema.inner}. A namespace that was never created itself but is the parent of one
   * that was is among them: {@code db.schema.inner} alone lists {@code db.schema} under {@code db}.
   *
   * @param parent the levels of the parent; empty for the top-level namespaces.
   * @return the levels of the namespaces, ordered by key.
   */
  public List<List<String>> listNamespaces(List<String> parent) {
    Set<List<String>> children = new LinkedHashSet<>();
    int depth = parent.size() + 1;
    String prefix = parent.isEmpty() ? "" : IcebergIdentifier.namespaceKey(parent) + IcebergIdentifier.SEPARATOR;
    Iterator<String> keys = namespaces.keyIterator(prefix);
    while (keys.hasNext()) {
      String key = keys.next();
      if (!key.startsWith(prefix)) {
        break;
      }
      List<String> levels = IcebergIdentifier.parseNamespace(key);
      if (levels.size() >= depth) {
        children.add(levels.subList(0, depth));
      }
    }
    return List.copyOf(children);
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
   * request created meanwhile. Its namespace is checked in the same step, so a namespace dropped meanwhile can't be
   * left holding it.
   *
   * @param record the table.
   * @return {@code true} if it was stored; {@code false} if the identifier is taken.
   * @throws IcebergCatalogException if its namespace doesn't exist.
   */
  public boolean putTableIfAbsent(IcebergTableRecord record) {
    boolean created;
    synchronized (namespaces) {
      requireOpen();
      List<String> namespace = record.namespace();
      if (!namespace.isEmpty() && !namespaces.containsKey(IcebergIdentifier.namespaceKey(namespace))) {
        throw IcebergCatalogException.noSuchNamespace(namespace);
      }
      created = tables.putIfAbsent(record.key(), JsonUtils.toJson(record)) == null;
    }
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
    String updatedJson = JsonUtils.toJson(updated);
    while (true) {
      // Compared as values, not as the JSON of `expected`, so that a record written by an older LocalS3 can still be
      // committed to.
      String json = tables.get(expected.key());
      if (json == null || !JsonUtils.fromJson(json, IcebergTableRecord.class).equals(expected)) {
        return false;
      }
      if (tables.replace(expected.key(), json, updatedJson)) {
        commit();
        return true;
      }
    }
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
   * Remove a table, if it is still the record the caller read: a commit that landed on it meanwhile, or a table that
   * was dropped and created again under its name, is left in place.
   *
   * @param expected the record the caller read.
   * @return {@code true} if it was removed; {@code false} if the table has changed or is gone.
   */
  public boolean removeTable(IcebergTableRecord expected) {
    while (true) {
      // Compared as values, like replaceTable.
      String json = tables.get(expected.key());
      if (json == null || !JsonUtils.fromJson(json, IcebergTableRecord.class).equals(expected)) {
        return false;
      }
      if (tables.remove(expected.key(), json)) {
        commit();
        return true;
      }
    }
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

  private void requireOpen() {
    if (closed) {
      throw IcebergCatalogException.catalogClosed();
    }
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
