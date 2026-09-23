package com.robothy.s3.core.iceberg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Iceberg REST catalog of a LocalS3 service: the namespaces and the tables, and the commits that move a table
 * from one metadata file to the next.
 *
 * <p>The two halves of a table live in different places, which is what an Iceberg catalog <em>is</em>. The table
 * itself — its schema, its snapshots, the manifests that list its data files — is a {@code metadata.json} written to
 * the object store, here through {@linkplain IcebergMetadataFiles}. The catalog keeps only a pointer to the file that
 * the table currently is, in {@linkplain IcebergCatalogStore}. A commit writes a new metadata file and then moves that
 * pointer, and moving the pointer is a compare-and-set: of two writers that started from the same file, exactly one
 * moves it, and the other is told its commit failed so that its client retries against the table as it now is.
 *
 * <p>The documents are handled as JSON trees rather than as classes of the Iceberg model, see
 * {@linkplain IcebergJson}: this keeps the service small and lets it carry through the fields that a newer client
 * sends, and it is why LocalS3 serves the catalog without depending on Iceberg at all.
 *
 * <p>This class is HTTP-free — it takes and returns the documents of the protocol, not requests — so that the
 * controllers stay thin and it can be tested directly.
 */
public final class IcebergCatalogService {

  /**
   * The property that names where a namespace keeps its tables, which a table created in it inherits.
   */
  static final String LOCATION_PROPERTY = "location";

  /**
   * The property with which a table or a view keeps its metadata files somewhere else than under its own location.
   */
  private static final String METADATA_LOCATION_PROPERTY = "write.metadata.path";

  /**
   * The number of superseded metadata files that a table records when it doesn't configure
   * {@code write.metadata.previous-versions-max}, which is the default of Iceberg.
   */
  private static final int DEFAULT_PREVIOUS_VERSIONS_MAX = 100;

  private static final Logger log = LoggerFactory.getLogger(IcebergCatalogService.class);

  private final IcebergCatalogStore store;

  private final IcebergMetadataFiles files;

  /**
   * The warehouse location, without a trailing {@code /}, e.g. {@code s3://warehouse}.
   */
  private final String warehouse;

  /**
   * Whether the location of a table that is created without one of its own carries a random suffix.
   */
  private final boolean uniqueTableLocation;

  /**
   * Create the catalog with locations derived from the names of the tables.
   *
   * @param store the namespaces and the table pointers.
   * @param files the metadata files, which are objects of the service.
   * @param warehouse the warehouse location, e.g. {@code s3://warehouse/}.
   */
  public IcebergCatalogService(IcebergCatalogStore store, IcebergMetadataFiles files, String warehouse) {
    this(store, files, warehouse, false);
  }

  /**
   * Create the catalog.
   *
   * @param store the namespaces and the table pointers.
   * @param files the metadata files, which are objects of the service.
   * @param warehouse the warehouse location, e.g. {@code s3://warehouse/}.
   * @param uniqueTableLocation whether the location of a table that is created without one of its own ends in a random
   *     suffix, which is the {@code unique-table-location} of the Iceberg catalogs: it keeps a table that is created
   *     under the name of a dropped one from landing among the files of that one.
   */
  public IcebergCatalogService(IcebergCatalogStore store, IcebergMetadataFiles files, String warehouse,
                               boolean uniqueTableLocation) {
    this.store = Objects.requireNonNull(store, "store");
    this.files = Objects.requireNonNull(files, "files");
    this.warehouse = IcebergJson.stripTrailingSlash(Objects.requireNonNull(warehouse, "warehouse"));
    this.uniqueTableLocation = uniqueTableLocation;
  }

  /**
   * The warehouse location of the catalog.
   *
   * @return the location, without a trailing {@code /}.
   */
  public String warehouse() {
    return warehouse;
  }

  /*
   * Namespaces.
   */

  /**
   * The namespaces directly under a parent.
   *
   * @param parent the levels of the parent; empty for the top-level namespaces.
   * @return a {@code ListNamespacesResponse}.
   * @throws IcebergCatalogException if the parent doesn't exist.
   */
  public ObjectNode listNamespaces(List<String> parent) {
    requireNamespace(parent);
    ArrayNode namespaces = IcebergJson.newArray();
    for (IcebergNamespaceRecord record : store.listNamespaces(parent)) {
      ArrayNode levels = IcebergJson.newArray();
      record.levels().forEach(levels::add);
      namespaces.add(levels);
    }
    ObjectNode response = IcebergJson.newObject();
    response.set("namespaces", namespaces);
    return response;
  }

  /**
   * Create a namespace.
   *
   * @param request a {@code CreateNamespaceRequest}: its {@code namespace} and its {@code properties}.
   * @return a {@code CreateNamespaceResponse}.
   * @throws IcebergCatalogException if the namespace already exists.
   */
  public ObjectNode createNamespace(ObjectNode request) {
    List<String> levels = levels(request.get("namespace"));
    if (levels.isEmpty()) {
      throw IcebergCatalogException.badRequest("Cannot create the empty namespace.");
    }
    Map<String, String> properties = IcebergJson.toStringMap(request.get("properties"));
    if (!store.putNamespaceIfAbsent(new IcebergNamespaceRecord(levels, properties))) {
      throw IcebergCatalogException.namespaceExists(levels);
    }
    return namespaceResponse(new IcebergNamespaceRecord(levels, properties));
  }

  /**
   * Load a namespace.
   *
   * @param namespace the levels of the namespace.
   * @return a {@code GetNamespaceResponse}.
   * @throws IcebergCatalogException if the namespace doesn't exist.
   */
  public ObjectNode loadNamespace(List<String> namespace) {
    return namespaceResponse(requireNamespace(namespace));
  }

  /**
   * Whether a namespace exists.
   *
   * @param namespace the levels of the namespace.
   * @return {@code true} if it exists.
   */
  public boolean namespaceExists(List<String> namespace) {
    return !namespace.isEmpty() && store.getNamespace(IcebergIdentifier.namespaceKey(namespace)) != null;
  }

  /**
   * Drop a namespace, which must hold neither a table nor another namespace.
   *
   * @param namespace the levels of the namespace.
   * @throws IcebergCatalogException if it doesn't exist, or isn't empty.
   */
  public void dropNamespace(List<String> namespace) {
    requireNamespace(namespace);
    if (store.hasTables(namespace) || store.hasChildNamespaces(namespace)) {
      throw IcebergCatalogException.namespaceNotEmpty(namespace);
    }
    store.removeNamespace(IcebergIdentifier.namespaceKey(namespace));
  }

  /**
   * Set and remove the properties of a namespace. A property that is both removed and set is set: the updates are
   * applied after the removals, like the REST catalog specifies.
   *
   * @param namespace the levels of the namespace.
   * @param request an {@code UpdateNamespacePropertiesRequest}: its {@code removals} and its {@code updates}.
   * @return an {@code UpdateNamespacePropertiesResponse}, naming what was removed, updated and missing.
   * @throws IcebergCatalogException if the namespace doesn't exist.
   */
  public ObjectNode updateNamespaceProperties(List<String> namespace, ObjectNode request) {
    IcebergNamespaceRecord record = requireNamespace(namespace);
    Map<String, String> properties = new LinkedHashMap<>(record.properties());
    ArrayNode removed = IcebergJson.newArray();
    ArrayNode missing = IcebergJson.newArray();
    ArrayNode updated = IcebergJson.newArray();

    for (JsonNode removal : request.path("removals")) {
      String name = removal.asString();
      if (properties.remove(name) != null) {
        removed.add(name);
      } else {
        missing.add(name);
      }
    }
    for (Map.Entry<String, String> update : IcebergJson.toStringMap(request.get("updates")).entrySet()) {
      properties.put(update.getKey(), update.getValue());
      updated.add(update.getKey());
    }
    store.putNamespace(record.withProperties(properties));

    ObjectNode response = IcebergJson.newObject();
    response.set("removed", removed);
    response.set("updated", updated);
    response.set("missing", missing);
    return response;
  }

  /*
   * Tables.
   */

  /**
   * The tables, or the views, of a namespace.
   *
   * @param namespace the levels of the namespace.
   * @param views whether to list the views rather than the tables.
   * @return a {@code ListTablesResponse}.
   * @throws IcebergCatalogException if the namespace doesn't exist.
   */
  public ObjectNode listTables(List<String> namespace, boolean views) {
    requireNamespace(namespace);
    ArrayNode identifiers = IcebergJson.newArray();
    for (IcebergTableRecord record : store.listTables(namespace, views)) {
      identifiers.add(identifierNode(record.identifier()));
    }
    ObjectNode response = IcebergJson.newObject();
    response.set("identifiers", identifiers);
    return response;
  }

  /**
   * Create a table.
   *
   * <p>A request with {@code stage-create} doesn't create anything: the table is built and answered, but neither its
   * metadata file nor its pointer is written, and the transaction of the client later commits it with an
   * {@code assert-create} requirement. That is how {@code CREATE TABLE ... AS SELECT} works — the table appears only
   * once the data has been written.
   *
   * @param namespace the levels of the namespace.
   * @param request a {@code CreateTableRequest}.
   * @return a {@code LoadTableResult}, whose {@code metadata-location} is absent for a staged create.
   * @throws IcebergCatalogException if the namespace doesn't exist, or the table does.
   */
  public ObjectNode createTable(List<String> namespace, ObjectNode request) {
    IcebergNamespaceRecord namespaceRecord = requireNamespace(namespace);
    String name = requireName(request);
    IcebergIdentifier identifier = IcebergIdentifier.of(namespace, name);
    requireNameFree(identifier, false);

    String location = request.path("location").asString(null);
    if (location == null || location.isBlank()) {
      location = TableMetadataFactory.defaultLocation(warehouse,
          namespaceRecord.properties().get(LOCATION_PROPERTY), namespace, name, uniqueTableLocation);
    }
    location = IcebergJson.stripTrailingSlash(location);
    ObjectNode metadata = TableMetadataFactory.createTableMetadata(location, request);

    if (request.path("stage-create").asBoolean(false)) {
      return loadTableResult(null, metadata);
    }

    String metadataLocation = writeMetadata(location, 0, metadata);
    IcebergTableRecord record = new IcebergTableRecord(namespace, name, false, metadataLocation, null, 0);
    if (!store.putTableIfAbsent(record)) {
      throw nameTaken(identifier, false);
    }
    return loadTableResult(metadataLocation, metadata);
  }

  /**
   * Register a table, or a view, that already has a metadata file, e.g. one written by another catalog or one left
   * behind by a table that was dropped without purging it.
   *
   * @param namespace the levels of the namespace.
   * @param request a {@code RegisterTableRequest}: its {@code name} and its {@code metadata-location}.
   * @param views whether to register a view rather than a table.
   * @return a {@code LoadTableResult}, or a {@code LoadViewResult}.
   * @throws IcebergCatalogException if the namespace doesn't exist, the name is taken, or the metadata can't be read.
   */
  public ObjectNode registerTable(List<String> namespace, ObjectNode request, boolean views) {
    requireNamespace(namespace);
    String name = requireName(request);
    IcebergIdentifier identifier = IcebergIdentifier.of(namespace, name);
    requireNameFree(identifier, views);
    String metadataLocation = request.path("metadata-location").asString(null);
    if (metadataLocation == null || metadataLocation.isBlank()) {
      throw IcebergCatalogException.badRequest("Invalid register request: metadata-location is required.");
    }
    ObjectNode metadata = IcebergJson.read(files.read(metadataLocation));
    IcebergTableRecord record = new IcebergTableRecord(namespace, name, views, metadataLocation, null,
        versionOf(metadataLocation));
    if (!store.putTableIfAbsent(record)) {
      throw nameTaken(identifier, views);
    }
    return views ? loadViewResult(metadataLocation, metadata) : loadTableResult(metadataLocation, metadata);
  }

  /**
   * Load a table, or a view.
   *
   * @param identifier the identifier.
   * @param views whether to load a view rather than a table.
   * @return a {@code LoadTableResult}, or a {@code LoadViewResult}.
   * @throws IcebergCatalogException if it doesn't exist.
   */
  public ObjectNode loadTable(IcebergIdentifier identifier, boolean views) {
    IcebergTableRecord record = requireTable(identifier, views);
    ObjectNode metadata = IcebergJson.read(files.read(record.metadataLocation()));
    return views ? loadViewResult(record.metadataLocation(), metadata)
        : loadTableResult(record.metadataLocation(), metadata);
  }

  /**
   * Whether a table, or a view, exists.
   *
   * @param identifier the identifier.
   * @param views whether to look for a view rather than a table.
   * @return {@code true} if it exists.
   */
  public boolean tableExists(IcebergIdentifier identifier, boolean views) {
    IcebergTableRecord record = store.getTable(identifier);
    return record != null && record.view() == views;
  }

  /**
   * Commit to a table, or create one that a staged create prepared.
   *
   * <p>The requirements are checked, the updates applied, the new metadata file written, and only then is the pointer
   * of the table moved — with a compare-and-set against the record the commit started from. A commit that loses that
   * race is answered {@code 409 CommitFailedException}, which is what makes the client refresh and retry, and the
   * metadata file it wrote is left behind unreferenced, exactly as a Hive or JDBC catalog leaves it.
   *
   * @param identifier the identifier.
   * @param request a {@code CommitTableRequest}: its {@code requirements} and its {@code updates}.
   * @param views whether the target is a view.
   * @return a {@code LoadTableResult} of the table as the commit left it.
   * @throws IcebergCatalogException if a requirement doesn't hold, or the commit lost the race.
   */
  public ObjectNode updateTable(IcebergIdentifier identifier, ObjectNode request, boolean views) {
    Commit commit = prepareCommit(identifier, request, views);
    if (commit.base() == null) {
      IcebergTableRecord created = new IcebergTableRecord(identifier.namespace(), identifier.name(), views,
          commit.metadataLocation(), null, 0);
      if (!store.putTableIfAbsent(created)) {
        throw IcebergCatalogException.commitFailed(
            "Requirement failed: table already exists: " + identifier);
      }
    } else if (!store.replaceTable(commit.base(), commit.base().committed(commit.metadataLocation()))) {
      throw IcebergCatalogException.commitFailed("Cannot commit to " + identifier
          + ": it changed while this commit was being prepared. Refresh the table and try again.");
    }
    return views ? loadViewResult(commit.metadataLocation(), commit.metadata())
        : loadTableResult(commit.metadataLocation(), commit.metadata());
  }

  /**
   * Commit to several tables at once, which is how an engine applies a multi-table transaction.
   *
   * <p>Every commit is prepared first, and the pointers are moved afterwards. If one of them loses its race, the
   * pointers that were already moved are moved back and the whole transaction fails, so the tables end up either all
   * committed or all unchanged. The window in which another reader could see part of the transaction is the time it
   * takes to move the pointers, which is a few in-memory compare-and-sets: LocalS3 is a test double, and this is as
   * atomic as it gets without a transaction log of its own.
   *
   * @param request a {@code CommitTransactionRequest}: its {@code table-changes}.
   * @throws IcebergCatalogException if a requirement doesn't hold, or a commit lost its race.
   */
  public void commitTransaction(ObjectNode request) {
    JsonNode changes = request.get("table-changes");
    if (changes == null || !changes.isArray()) {
      throw IcebergCatalogException.badRequest("Invalid commit transaction request: table-changes is required.");
    }
    List<Commit> commits = new ArrayList<>();
    for (JsonNode change : changes) {
      if (!change.isObject()) {
        throw IcebergCatalogException.badRequest("Invalid table change: expected an object.");
      }
      ObjectNode tableChange = (ObjectNode) change;
      IcebergIdentifier identifier = identifier(tableChange.get("identifier"));
      commits.add(prepareCommit(identifier, tableChange, false));
    }

    List<Commit> applied = new ArrayList<>();
    for (Commit commit : commits) {
      boolean moved = commit.base() == null
          ? store.putTableIfAbsent(new IcebergTableRecord(commit.identifier().namespace(),
              commit.identifier().name(), false, commit.metadataLocation(), null, 0))
          : store.replaceTable(commit.base(), commit.base().committed(commit.metadataLocation()));
      if (!moved) {
        rollback(applied);
        throw IcebergCatalogException.commitFailed("Cannot commit the transaction: " + commit.identifier()
            + " changed while it was being prepared. Refresh the tables and try again.");
      }
      applied.add(commit);
    }
  }

  /**
   * Move the pointers of the commits of a failed transaction back to where they were.
   */
  private void rollback(List<Commit> applied) {
    for (Commit commit : applied) {
      if (commit.base() == null) {
        store.removeTable(commit.identifier());
      } else {
        store.replaceTable(commit.base().committed(commit.metadataLocation()), commit.base());
      }
    }
  }

  /**
   * Check the requirements of a commit and write the metadata file it produces, without moving the pointer of the
   * table yet.
   */
  private Commit prepareCommit(IcebergIdentifier identifier, ObjectNode request, boolean views) {
    IcebergTableRecord record = store.getTable(identifier);
    if (record != null && record.view() != views) {
      // A commit that creates, which is how a transaction of the client creates a table, is told that the name is
      // taken by the other kind of thing; one that changes something is told that what it addressed isn't there.
      if (hasCreateRequirement(request)) {
        throw nameTaken(identifier, views);
      }
      throw views ? IcebergCatalogException.noSuchView(identifier)
          : IcebergCatalogException.noSuchTable(identifier);
    }
    ObjectNode base = record == null ? null : IcebergJson.read(files.read(record.metadataLocation()));
    IcebergMetadataUpdater.checkRequirements(base, request.get("requirements"), views);
    if (base == null && !hasCreateRequirement(request)) {
      throw views ? IcebergCatalogException.noSuchView(identifier)
          : IcebergCatalogException.noSuchTable(identifier);
    }

    ObjectNode starting = base != null ? base : emptyMetadata(views);
    ObjectNode metadata = views
        ? IcebergMetadataUpdater.applyToView(starting, request.get("updates"))
        : IcebergMetadataUpdater.applyToTable(starting, request.get("updates"));
    IcebergMetadataUpdater.stripInternalFields(metadata);
    if (base != null && !views) {
      recordPreviousMetadata(metadata, base, record.metadataLocation());
    }

    String location = metadata.path("location").asString(null);
    if (location == null || location.isBlank()) {
      location = TableMetadataFactory.defaultLocation(warehouse, null, identifier.namespace(), identifier.name(),
          uniqueTableLocation);
      metadata.put("location", location);
    }
    int version = record == null ? 0 : record.version() + 1;
    return new Commit(identifier, record, writeMetadata(IcebergJson.stripTrailingSlash(location), version, metadata),
        metadata);
  }

  /**
   * Drop a table, or a view.
   *
   * <p>A purge deletes everything under the location of the table — its metadata files, its manifests and its data
   * files all live there — unless another table or view of the catalog lives under that location too. Two tables share
   * a location when one is created under the name a dropped or renamed one had, because the default location of a table
   * is derived from its name; purging then would delete the files of a table the catalog still points at, and losing a
   * live table is worse than leaving a dropped one's files behind. {@linkplain
   * com.robothy.s3.core.service.manager.iceberg.LocalS3IcebergManager#createInMemory unique table locations} keep the
   * two apart in the first place.
   *
   * @param identifier the identifier.
   * @param views whether to drop a view rather than a table.
   * @param purge whether to delete the data and the metadata files of the table as well; a drop without it only
   *     forgets the table, and its files stay in the bucket.
   * @throws IcebergCatalogException if it doesn't exist.
   */
  public void dropTable(IcebergIdentifier identifier, boolean views, boolean purge) {
    IcebergTableRecord record = requireTable(identifier, views);
    String location = null;
    if (purge) {
      // Read where the table keeps its files before the pointer to them is gone.
      try {
        location = IcebergJson.read(files.read(record.metadataLocation())).path("location").asString(null);
      } catch (IcebergCatalogException e) {
        location = null;
      }
    }
    store.removeTable(identifier);
    if (purge && location != null) {
      if (store.anyTableUnder(location)) {
        log.warn("Dropped {} without purging {}: another table of the catalog keeps its metadata there, and deleting"
            + " the location would take that table's files with it.", identifier, location);
      } else {
        files.purge(location);
      }
    }
  }

  /**
   * Rename a table, or a view, which moves its pointer and leaves its files where they are.
   *
   * @param request a {@code RenameTableRequest}: its {@code source} and its {@code destination}.
   * @param views whether the target is a view.
   * @throws IcebergCatalogException if the source doesn't exist, or the destination is taken.
   */
  public void renameTable(ObjectNode request, boolean views) {
    IcebergIdentifier source = identifier(request.get("source"));
    IcebergIdentifier destination = identifier(request.get("destination"));
    IcebergTableRecord record = requireTable(source, views);
    requireNamespace(destination.namespace());
    IcebergTableRecord taken = store.getTable(destination);
    if (taken != null) {
      throw IcebergCatalogException.renameTargetExists(source, destination, taken.view());
    }
    if (!store.putTableIfAbsent(record.renamedTo(destination))) {
      IcebergTableRecord raced = store.getTable(destination);
      throw IcebergCatalogException.renameTargetExists(source, destination, raced != null ? raced.view() : views);
    }
    store.removeTable(source);
  }

  /*
   * Views.
   */

  /**
   * Create a view.
   *
   * @param namespace the levels of the namespace.
   * @param request a {@code CreateViewRequest}.
   * @return a {@code LoadViewResult}.
   * @throws IcebergCatalogException if the namespace doesn't exist, or the view does.
   */
  public ObjectNode createView(List<String> namespace, ObjectNode request) {
    IcebergNamespaceRecord namespaceRecord = requireNamespace(namespace);
    String name = requireName(request);
    IcebergIdentifier identifier = IcebergIdentifier.of(namespace, name);
    requireNameFree(identifier, true);
    String location = request.path("location").asString(null);
    if (location == null || location.isBlank()) {
      location = TableMetadataFactory.defaultLocation(warehouse,
          namespaceRecord.properties().get(LOCATION_PROPERTY), namespace, name, uniqueTableLocation);
    }
    location = IcebergJson.stripTrailingSlash(location);
    ObjectNode metadata = TableMetadataFactory.createViewMetadata(location, request);
    String metadataLocation = writeMetadata(location, 0, metadata);
    if (!store.putTableIfAbsent(new IcebergTableRecord(namespace, name, true, metadataLocation, null, 0))) {
      throw nameTaken(identifier, true);
    }
    return loadViewResult(metadataLocation, metadata);
  }

  /*
   * The documents of the protocol.
   */

  private ObjectNode namespaceResponse(IcebergNamespaceRecord record) {
    ObjectNode response = IcebergJson.newObject();
    ArrayNode levels = IcebergJson.newArray();
    record.levels().forEach(levels::add);
    response.set("namespace", levels);
    response.set("properties", IcebergJson.fromStringMap(record.properties()));
    return response;
  }

  /**
   * A {@code LoadTableResult}. The metadata is answered inline, so that a client that just loaded a table doesn't
   * have to read the file as well; {@code config} is left to the caller, which knows the endpoint of the service.
   *
   * @param metadataLocation the location of the metadata file; {@code null} for a staged create, which has none.
   */
  private ObjectNode loadTableResult(@Nullable String metadataLocation, ObjectNode metadata) {
    ObjectNode result = IcebergJson.newObject();
    if (metadataLocation != null) {
      result.put("metadata-location", metadataLocation);
    }
    result.set("metadata", metadata);
    return result;
  }

  private ObjectNode loadViewResult(String metadataLocation, ObjectNode metadata) {
    ObjectNode result = IcebergJson.newObject();
    result.put("metadata-location", metadataLocation);
    result.set("metadata", metadata);
    return result;
  }

  private static ObjectNode identifierNode(IcebergIdentifier identifier) {
    ObjectNode node = IcebergJson.newObject();
    ArrayNode levels = IcebergJson.newArray();
    identifier.namespace().forEach(levels::add);
    node.set("namespace", levels);
    node.put("name", identifier.name());
    return node;
  }

  /**
   * The metadata that a commit with an {@code assert-create} requirement starts from: an empty table, which the
   * updates of that commit build into the table being created.
   */
  private static ObjectNode emptyMetadata(boolean views) {
    ObjectNode metadata = IcebergJson.newObject();
    if (views) {
      metadata.put("format-version", 1);
      metadata.set("schemas", IcebergJson.newArray());
      metadata.set("versions", IcebergJson.newArray());
      metadata.set("version-log", IcebergJson.newArray());
      metadata.set("properties", IcebergJson.newObject());
      return metadata;
    }
    metadata.put("format-version", 1);
    metadata.put("last-sequence-number", 0);
    metadata.put("last-column-id", 0);
    metadata.set("schemas", IcebergJson.newArray());
    metadata.put("current-schema-id", -1);
    metadata.set("partition-specs", IcebergJson.newArray());
    metadata.put("default-spec-id", -1);
    metadata.put("last-partition-id", TableMetadataFactory.PARTITION_FIELD_ID_START - 1);
    metadata.set("sort-orders", IcebergJson.newArray());
    metadata.put("default-sort-order-id", -1);
    metadata.set("properties", IcebergJson.newObject());
    metadata.put("current-snapshot-id", -1L);
    metadata.set("refs", IcebergJson.newObject());
    metadata.set("snapshots", IcebergJson.newArray());
    metadata.set("statistics", IcebergJson.newArray());
    metadata.set("partition-statistics", IcebergJson.newArray());
    metadata.set("snapshot-log", IcebergJson.newArray());
    metadata.set("metadata-log", IcebergJson.newArray());
    return metadata;
  }

  /**
   * Write the metadata file of a version of a table or a view.
   *
   * <p>It goes under {@code metadata/} of the location, unless the table sets {@code write.metadata.path}, the property
   * with which Iceberg keeps the metadata of a table somewhere else than its data — another prefix, or another bucket.
   *
   * @return the location of the file that was written.
   */
  private String writeMetadata(String location, int version, ObjectNode metadata) {
    String configured = metadata.path("properties").path(METADATA_LOCATION_PROPERTY).asString(null);
    String directory = configured == null || configured.isBlank()
        ? location + "/metadata" : IcebergJson.stripTrailingSlash(configured);
    String metadataLocation = directory + "/" + TableMetadataFactory.metadataFileName(version);
    files.write(metadataLocation, IcebergJson.write(metadata));
    return metadataLocation;
  }

  /**
   * Record the metadata file that a commit supersedes in the metadata log of the new one, which is what lets a reader
   * walk back through the versions of a table, e.g. to travel to a time before the current snapshot.
   *
   * <p>The log is trimmed to {@code write.metadata.previous-versions-max} entries, the property that Iceberg trims it
   * by, so that a table committed to a thousand times doesn't carry a thousand entries in every metadata file.
   *
   * @param metadata the metadata being written, which this modifies.
   * @param base the metadata being superseded, whose timestamp the entry carries.
   * @param previousLocation the location of the file being superseded.
   */
  private static void recordPreviousMetadata(ObjectNode metadata, ObjectNode base, String previousLocation) {
    JsonNode existing = metadata.get("metadata-log");
    ArrayNode log = existing instanceof ArrayNode array ? array : metadata.putArray("metadata-log");
    ObjectNode entry = IcebergJson.newObject();
    entry.put("timestamp-ms", base.path("last-updated-ms").asLong(System.currentTimeMillis()));
    entry.put("metadata-file", previousLocation);
    log.add(entry);

    int max = DEFAULT_PREVIOUS_VERSIONS_MAX;
    JsonNode configured = metadata.path("properties").get("write.metadata.previous-versions-max");
    if (configured != null && !configured.isNull()) {
      try {
        max = Integer.parseInt(configured.asString().trim());
      } catch (NumberFormatException e) {
        max = DEFAULT_PREVIOUS_VERSIONS_MAX;
      }
    }
    max = Math.max(max, 1);
    while (log.size() > max) {
      log.remove(0);
    }
  }

  /**
   * The version that a metadata file name carries, e.g. {@code 7} for {@code 00007-....metadata.json}, so that a
   * registered table keeps counting where its previous catalog left off.
   *
   * @return the version; {@code 0} if the name doesn't carry one.
   */
  private static int versionOf(String metadataLocation) {
    int slash = metadataLocation.lastIndexOf('/');
    String name = slash < 0 ? metadataLocation : metadataLocation.substring(slash + 1);
    int dash = name.indexOf('-');
    if (dash <= 0) {
      return 0;
    }
    try {
      return Integer.parseInt(name.substring(0, dash));
    } catch (NumberFormatException e) {
      return 0;
    }
  }

  private static boolean hasCreateRequirement(ObjectNode request) {
    for (JsonNode requirement : request.path("requirements")) {
      if ("assert-create".equals(requirement.path("type").asString(""))) {
        return true;
      }
    }
    return false;
  }

  private IcebergNamespaceRecord requireNamespace(List<String> namespace) {
    if (namespace.isEmpty()) {
      // The root namespace isn't a namespace of its own: it holds the top-level ones and nothing else.
      return new IcebergNamespaceRecord(List.of(), Map.of());
    }
    IcebergNamespaceRecord record = store.getNamespace(IcebergIdentifier.namespaceKey(namespace));
    if (record == null) {
      throw IcebergCatalogException.noSuchNamespace(namespace);
    }
    return record;
  }

  /**
   * Check that a name holds neither a table nor a view before one is created under it.
   *
   * @param identifier the name.
   * @param views whether what is being created is a view.
   * @throws IcebergCatalogException if the name is taken.
   */
  private void requireNameFree(IcebergIdentifier identifier, boolean views) {
    if (store.getTable(identifier) != null) {
      throw nameTaken(identifier, views);
    }
  }

  /**
   * The failure of creating a table, or a view, under a name that is taken: one message when the same kind of thing
   * holds it, and another that names the other kind, which is what a client needs to tell the two apart.
   *
   * @param identifier the name that is taken.
   * @param views whether what was being created is a view.
   * @return the exception to raise.
   */
  private IcebergCatalogException nameTaken(IcebergIdentifier identifier, boolean views) {
    IcebergTableRecord existing = store.getTable(identifier);
    // Gone again already: another request dropped it between the failure and this lookup, and there is nothing left to
    // tell apart, so the name is reported as taken by the kind that was being created.
    boolean existingIsView = existing != null ? existing.view() : views;
    if (existingIsView == views) {
      return views ? IcebergCatalogException.viewExists(identifier)
          : IcebergCatalogException.tableExists(identifier);
    }
    return existingIsView ? IcebergCatalogException.viewWithSameNameExists(identifier)
        : IcebergCatalogException.tableWithSameNameExists(identifier);
  }

  private IcebergTableRecord requireTable(IcebergIdentifier identifier, boolean views) {
    IcebergTableRecord record = store.getTable(identifier);
    if (record == null || record.view() != views) {
      throw views ? IcebergCatalogException.noSuchView(identifier)
          : IcebergCatalogException.noSuchTable(identifier);
    }
    return record;
  }

  private static String requireName(ObjectNode request) {
    String name = request.path("name").asString(null);
    if (name == null || name.isBlank()) {
      throw IcebergCatalogException.badRequest("Invalid request: name is required.");
    }
    return name;
  }

  private static IcebergIdentifier identifier(@Nullable JsonNode node) {
    if (node == null || !node.isObject()) {
      throw IcebergCatalogException.badRequest("Invalid table identifier: expected an object.");
    }
    String name = node.path("name").asString(null);
    if (name == null || name.isBlank()) {
      throw IcebergCatalogException.badRequest("Invalid table identifier: name is required.");
    }
    return IcebergIdentifier.of(levels(node.get("namespace")), name);
  }

  private static List<String> levels(@Nullable JsonNode node) {
    if (node == null || !node.isArray()) {
      return List.of();
    }
    List<String> levels = new ArrayList<>();
    for (JsonNode level : node) {
      levels.add(level.asString());
    }
    return List.copyOf(levels);
  }

  /**
   * A commit that is prepared: its metadata file is written, and only its pointer is left to move.
   *
   * @param identifier the table it commits to.
   * @param base the record the commit started from; {@code null} if it creates the table.
   * @param metadataLocation the location of the metadata file that was written.
   * @param metadata the metadata that was written.
   */
  private record Commit(IcebergIdentifier identifier, @Nullable IcebergTableRecord base, String metadataLocation,
                        ObjectNode metadata) {
  }

}
