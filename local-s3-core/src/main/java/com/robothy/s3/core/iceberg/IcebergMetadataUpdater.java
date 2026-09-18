package com.robothy.s3.core.iceberg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Applies the {@code requirements} and the {@code updates} of a commit to the metadata of a table or a view, which is
 * what a commit to an Iceberg REST catalog is: the client doesn't send the new metadata, it sends the conditions that
 * the table it read must still satisfy and the changes to apply to it, and the catalog decides whether the commit wins.
 *
 * <p>The requirements are checked first, against the metadata as it now is, and all of them before anything is
 * applied. A requirement that doesn't hold means another writer committed in between, and the commit is refused with
 * {@code 409 CommitFailedException}, which is the answer a client retries on: it refreshes the table, replays its
 * changes against the new metadata and commits again. That retry is why the requirements must be checked exactly —
 * accepting a commit that should have lost would silently drop the other writer's work.
 *
 * <p>The updates are then applied in order to a copy of the metadata, so that a failure partway leaves the table as it
 * was. An update whose action LocalS3 doesn't know is refused rather than ignored, for the same reason: dropping it
 * would answer a client with metadata that isn't what it asked for.
 */
final class IcebergMetadataUpdater {

  private IcebergMetadataUpdater() {
  }

  /**
   * Check the requirements of a commit.
   *
   * @param base the metadata of the table as it now is; {@code null} if the table doesn't exist.
   * @param requirements the {@code requirements} of the request; {@code null} for none.
   * @param view whether the target is a view, whose UUID is asserted under another name.
   * @throws IcebergCatalogException if a requirement doesn't hold.
   */
  static void checkRequirements(@Nullable ObjectNode base, @Nullable JsonNode requirements, boolean view) {
    if (requirements == null || !requirements.isArray()) {
      return;
    }
    for (JsonNode requirement : requirements) {
      String type = requirement.path("type").asString("");
      if ("assert-create".equals(type)) {
        if (base != null) {
          throw IcebergCatalogException.commitFailed("Requirement failed: table already exists");
        }
        continue;
      }
      if (base == null) {
        throw IcebergCatalogException.commitFailed(
            "Requirement failed: " + (view ? "view" : "table") + " does not exist");
      }
      switch (type) {
        case "assert-table-uuid" -> assertEquals("UUID does not match",
            base.path("table-uuid").asString(null), requirement.path("uuid").asString(null));
        case "assert-view-uuid" -> assertEquals("view UUID does not match",
            base.path("view-uuid").asString(null), requirement.path("uuid").asString(null));
        case "assert-ref-snapshot-id" -> assertRef(base, requirement);
        case "assert-last-assigned-field-id" -> assertInt(base, "last-column-id",
            requirement, "last-assigned-field-id", "last assigned field id changed");
        case "assert-current-schema-id" -> assertInt(base, "current-schema-id",
            requirement, "current-schema-id", "current schema changed");
        case "assert-last-assigned-partition-id" -> assertInt(base, "last-partition-id",
            requirement, "last-assigned-partition-id", "last assigned partition id changed");
        case "assert-default-spec-id" -> assertInt(base, "default-spec-id",
            requirement, "default-spec-id", "default partition spec changed");
        case "assert-default-sort-order-id" -> assertInt(base, "default-sort-order-id",
            requirement, "default-sort-order-id", "default sort order changed");
        default -> throw IcebergCatalogException.badRequest("Unsupported requirement: " + type);
      }
    }
  }

  private static void assertEquals(String what, String current, String expected) {
    if (expected == null || !expected.equals(current)) {
      throw IcebergCatalogException.commitFailed(
          "Requirement failed: " + what + ": expected " + expected + " != " + current);
    }
  }

  private static void assertInt(ObjectNode base, String field, JsonNode requirement, String expectedField,
                                String what) {
    JsonNode expected = requirement.get(expectedField);
    if (expected == null || !expected.isIntegralNumber()) {
      throw IcebergCatalogException.badRequest("Invalid requirement: " + expectedField + " is required.");
    }
    int current = base.path(field).asInt(-1);
    if (current != expected.asInt()) {
      throw IcebergCatalogException.commitFailed(
          "Requirement failed: " + what + ": expected id " + expected.asInt() + " != " + current);
    }
  }

  /**
   * Check that a branch or a tag is where the commit expects it, which is the requirement that decides an ordinary
   * append: two writers that both append to {@code main} from the same snapshot send the same expected snapshot ID,
   * and only the first to commit finds it there.
   */
  private static void assertRef(ObjectNode base, JsonNode requirement) {
    String ref = requirement.path("ref").asString("");
    JsonNode expected = requirement.get("snapshot-id");
    JsonNode current = base.path("refs").get(ref);
    if (expected == null || expected.isNull()) {
      if (current != null) {
        throw IcebergCatalogException.commitFailed(
            "Requirement failed: branch or tag " + ref + " already exists");
      }
      return;
    }
    if (current == null) {
      throw IcebergCatalogException.commitFailed("Requirement failed: branch or tag " + ref
          + " is missing, expected " + expected.asLong());
    }
    long currentSnapshot = current.path("snapshot-id").asLong(-1);
    if (currentSnapshot != expected.asLong()) {
      throw IcebergCatalogException.commitFailed("Requirement failed: branch " + ref + " has changed: expected id "
          + expected.asLong() + " != " + currentSnapshot);
    }
  }

  /**
   * Apply the updates of a commit to the metadata of a table.
   *
   * @param base the metadata as it now is, which isn't modified.
   * @param updates the {@code updates} of the request; {@code null} for none.
   * @return the new metadata.
   * @throws IcebergCatalogException if an update can't be read or isn't supported.
   */
  static ObjectNode applyToTable(ObjectNode base, @Nullable JsonNode updates) {
    ObjectNode metadata = base.deepCopy();
    if (updates != null && updates.isArray()) {
      for (JsonNode update : updates) {
        applyTableUpdate(metadata, update);
      }
    }
    metadata.put("last-updated-ms", System.currentTimeMillis());
    return metadata;
  }

  /**
   * Apply the updates of a commit to the metadata of a view.
   *
   * @param base the metadata as it now is, which isn't modified.
   * @param updates the {@code updates} of the request; {@code null} for none.
   * @return the new metadata.
   * @throws IcebergCatalogException if an update can't be read or isn't supported.
   */
  static ObjectNode applyToView(ObjectNode base, @Nullable JsonNode updates) {
    ObjectNode metadata = base.deepCopy();
    if (updates != null && updates.isArray()) {
      for (JsonNode update : updates) {
        applyViewUpdate(metadata, update);
      }
    }
    return metadata;
  }

  private static void applyTableUpdate(ObjectNode metadata, JsonNode update) {
    String action = update.path("action").asString("");
    switch (action) {
      case "assign-uuid" -> metadata.put("table-uuid", required(update, "uuid").asString());
      case "upgrade-format-version" -> upgradeFormatVersion(metadata, update);
      case "add-schema" -> addSchema(metadata, update);
      case "set-current-schema" -> setCurrentSchema(metadata, update);
      case "add-spec" -> addSpec(metadata, update);
      case "set-default-spec" -> setDefaultId(metadata, update, "spec-id", "default-spec-id", "partition-specs");
      case "add-sort-order" -> addSortOrder(metadata, update);
      case "set-default-sort-order" ->
          setDefaultId(metadata, update, "sort-order-id", "default-sort-order-id", "sort-orders");
      case "add-snapshot" -> addSnapshot(metadata, update);
      case "remove-snapshots" -> removeSnapshots(metadata, update);
      case "set-snapshot-ref" -> setSnapshotRef(metadata, update);
      case "remove-snapshot-ref" -> removeSnapshotRef(metadata, update);
      case "set-location" -> metadata.put("location", required(update, "location").asString());
      case "set-properties" -> setProperties(metadata, update);
      case "remove-properties" -> removeProperties(metadata, update);
      case "set-statistics" -> setStatistics(metadata, update, "statistics", "statistics");
      case "remove-statistics" -> removeStatistics(metadata, update, "statistics");
      case "set-partition-statistics", "add-partition-statistics" ->
          setStatistics(metadata, update, "partition-statistics", "partition-statistics");
      case "remove-partition-statistics" -> removeStatistics(metadata, update, "partition-statistics");
      case "remove-partition-specs" -> removeById(metadata, "partition-specs", "spec-id",
          ids(update, "spec-ids"), metadata.path("default-spec-id").asInt(0), "default partition spec");
      case "remove-schemas" -> removeById(metadata, "schemas", "schema-id",
          ids(update, "schema-ids"), metadata.path("current-schema-id").asInt(0), "current schema");
      case "enable-row-lineage" -> enableRowLineage(metadata);
      case "add-encryption-key" -> addEncryptionKey(metadata, update);
      case "remove-encryption-key" -> removeEncryptionKey(metadata, update);
      default -> throw IcebergCatalogException.badRequest("Unsupported table update: " + action);
    }
  }

  private static void applyViewUpdate(ObjectNode metadata, JsonNode update) {
    String action = update.path("action").asString("");
    switch (action) {
      case "assign-uuid" -> metadata.put("view-uuid", required(update, "uuid").asString());
      case "upgrade-format-version" -> metadata.put("format-version",
          Math.max(metadata.path("format-version").asInt(1), required(update, "format-version").asInt()));
      case "add-schema" -> addSchema(metadata, update);
      case "add-view-version" -> addViewVersion(metadata, update);
      case "set-current-view-version" -> setCurrentViewVersion(metadata, update);
      case "set-location" -> metadata.put("location", required(update, "location").asString());
      case "set-properties" -> setProperties(metadata, update);
      case "remove-properties" -> removeProperties(metadata, update);
      default -> throw IcebergCatalogException.badRequest("Unsupported view update: " + action);
    }
  }

  /*
   * Table updates.
   */

  private static void upgradeFormatVersion(ObjectNode metadata, JsonNode update) {
    int requested = required(update, "format-version").asInt();
    int current = metadata.path("format-version").asInt(TableMetadataFactory.DEFAULT_FORMAT_VERSION);
    if (requested < current) {
      throw IcebergCatalogException.badRequest(
          "Cannot downgrade v" + current + " table to v" + requested);
    }
    if (requested > TableMetadataFactory.MAX_FORMAT_VERSION) {
      throw IcebergCatalogException.badRequest("Unsupported format version: v" + requested);
    }
    if (current < 2 && requested >= 2 && !metadata.has("last-sequence-number")) {
      metadata.put("last-sequence-number", 0);
    }
    metadata.put("format-version", requested);
  }

  /**
   * Register a schema, under the ID it names or the next free one, and carry {@code last-column-id} forward. A
   * schema that is already registered under the same ID replaces it, which is how Iceberg reuses an identical schema.
   */
  private static void addSchema(ObjectNode metadata, JsonNode update) {
    ObjectNode schema = objectField(update, "schema");
    ArrayNode schemas = array(metadata, "schemas");
    int schemaId = schema.hasNonNull("schema-id") ? schema.path("schema-id").asInt()
        : nextId(schemas, "schema-id");
    schema.put("schema-id", schemaId);
    replaceById(schemas, "schema-id", schemaId, schema);
    metadata.put("last-column-id",
        Math.max(metadata.path("last-column-id").asInt(0), IcebergJson.maxFieldId(schema)));
    metadata.put("__last-added-schema-id", schemaId);
  }

  private static void setCurrentSchema(ObjectNode metadata, JsonNode update) {
    int requested = required(update, "schema-id").asInt();
    int schemaId = requested == -1 ? lastAdded(metadata, "__last-added-schema-id", "schema") : requested;
    if (findById(array(metadata, "schemas"), "schema-id", schemaId) == null) {
      throw IcebergCatalogException.badRequest("Cannot set current schema to unknown schema: " + schemaId);
    }
    metadata.put("current-schema-id", schemaId);
  }

  private static void addSpec(ObjectNode metadata, JsonNode update) {
    ObjectNode spec = objectField(update, "spec");
    ArrayNode specs = array(metadata, "partition-specs");
    int specId = spec.hasNonNull("spec-id") ? spec.path("spec-id").asInt() : nextId(specs, "spec-id");
    spec.put("spec-id", specId);
    // A field the client didn't assign an ID gets the next free one, like an unassigned field of a create request.
    int nextFieldId = Math.max(metadata.path("last-partition-id").asInt(TableMetadataFactory.PARTITION_FIELD_ID_START
        - 1) + 1, TableMetadataFactory.PARTITION_FIELD_ID_START);
    for (JsonNode field : spec.path("fields")) {
      if (field instanceof ObjectNode partitionField && !partitionField.hasNonNull("field-id")) {
        partitionField.put("field-id", nextFieldId++);
      }
    }
    replaceById(specs, "spec-id", specId, spec);
    metadata.put("last-partition-id",
        Math.max(metadata.path("last-partition-id").asInt(TableMetadataFactory.PARTITION_FIELD_ID_START - 1),
            TableMetadataFactory.lastPartitionId(spec)));
    metadata.put("__last-added-spec-id", specId);
  }

  private static void addSortOrder(ObjectNode metadata, JsonNode update) {
    ObjectNode order = objectField(update, "sort-order");
    ArrayNode orders = array(metadata, "sort-orders");
    int orderId = order.hasNonNull("order-id") ? order.path("order-id").asInt() : nextId(orders, "order-id");
    order.put("order-id", orderId);
    replaceById(orders, "order-id", orderId, order);
    metadata.put("__last-added-sort-order-id", orderId);
  }

  /**
   * Set the default partition spec or sort order, resolving the {@code -1} that a client sends to mean "the one this
   * commit just added".
   */
  private static void setDefaultId(ObjectNode metadata, JsonNode update, String field, String defaultField,
                                   String collection) {
    int requested = required(update, field).asInt();
    String kind = "partition-specs".equals(collection) ? "spec" : "sort order";
    int id = requested == -1 ? lastAdded(metadata, "__last-added-" + field, kind) : requested;
    String idField = "partition-specs".equals(collection) ? "spec-id" : "order-id";
    if (findById(array(metadata, collection), idField, id) == null) {
      throw IcebergCatalogException.badRequest("Cannot set default " + kind + " to unknown id: " + id);
    }
    metadata.put(defaultField, id);
  }

  /**
   * Add a snapshot, which is what an append, an overwrite or a delete of a client produces. It doesn't become the
   * current snapshot here: the {@code set-snapshot-ref} of the same commit moves {@code main} to it.
   */
  private static void addSnapshot(ObjectNode metadata, JsonNode update) {
    ObjectNode snapshot = objectField(update, "snapshot");
    long snapshotId = snapshot.path("snapshot-id").asLong(-1);
    ArrayNode snapshots = array(metadata, "snapshots");
    for (JsonNode existing : snapshots) {
      if (existing.path("snapshot-id").asLong(-1) == snapshotId) {
        throw IcebergCatalogException.badRequest("Snapshot already exists for id: " + snapshotId);
      }
    }
    if (metadata.path("format-version").asInt(TableMetadataFactory.DEFAULT_FORMAT_VERSION) > 1) {
      long sequenceNumber = snapshot.path("sequence-number").asLong(0);
      metadata.put("last-sequence-number", Math.max(metadata.path("last-sequence-number").asLong(0), sequenceNumber));
    }
    snapshots.add(snapshot);
  }

  private static void removeSnapshots(ObjectNode metadata, JsonNode update) {
    Set<Long> removed = new HashSet<>();
    for (JsonNode id : update.path("snapshot-ids")) {
      removed.add(id.asLong());
    }
    if (removed.isEmpty()) {
      return;
    }
    ArrayNode snapshots = array(metadata, "snapshots");
    for (int i = snapshots.size() - 1; i >= 0; i--) {
      if (removed.contains(snapshots.get(i).path("snapshot-id").asLong(-1))) {
        snapshots.remove(i);
      }
    }
    // A ref that points at a snapshot that is gone would leave the table unreadable, and so would a log entry.
    ObjectNode refs = object(metadata, "refs");
    List<String> danglingRefs = new ArrayList<>();
    for (Map.Entry<String, JsonNode> ref : refs.properties()) {
      if (removed.contains(ref.getValue().path("snapshot-id").asLong(-1))) {
        danglingRefs.add(ref.getKey());
      }
    }
    danglingRefs.forEach(refs::remove);
    ArrayNode log = array(metadata, "snapshot-log");
    for (int i = log.size() - 1; i >= 0; i--) {
      if (removed.contains(log.get(i).path("snapshot-id").asLong(-1))) {
        log.remove(i);
      }
    }
    if (removed.contains(metadata.path("current-snapshot-id").asLong(-1))) {
      metadata.put("current-snapshot-id", -1L);
    }
  }

  /**
   * Move a branch or a tag. Moving {@code main} is what publishes a snapshot: it becomes the current snapshot of the
   * table, and the move is recorded in the snapshot log, which is what a time-travel read by timestamp walks.
   */
  private static void setSnapshotRef(ObjectNode metadata, JsonNode update) {
    String name = required(update, "ref-name").asString();
    long snapshotId = required(update, "snapshot-id").asLong();
    JsonNode snapshot = findSnapshot(metadata, snapshotId);
    if (snapshot == null) {
      throw IcebergCatalogException.badRequest(
          "Cannot set " + name + " to unknown snapshot: " + snapshotId);
    }
    ObjectNode ref = IcebergJson.newObject();
    ref.put("snapshot-id", snapshotId);
    ref.put("type", update.path("type").asString("branch"));
    copyIfPresent(update, ref, "min-snapshots-to-keep");
    copyIfPresent(update, ref, "max-snapshot-age-ms");
    copyIfPresent(update, ref, "max-ref-age-ms");
    object(metadata, "refs").set(name, ref);

    if ("main".equals(name)) {
      long previous = metadata.path("current-snapshot-id").asLong(-1);
      metadata.put("current-snapshot-id", snapshotId);
      if (previous != snapshotId) {
        ObjectNode entry = IcebergJson.newObject();
        entry.put("timestamp-ms", snapshot.path("timestamp-ms").asLong(System.currentTimeMillis()));
        entry.put("snapshot-id", snapshotId);
        array(metadata, "snapshot-log").add(entry);
      }
    }
  }

  private static void removeSnapshotRef(ObjectNode metadata, JsonNode update) {
    String name = required(update, "ref-name").asString();
    object(metadata, "refs").remove(name);
    if ("main".equals(name)) {
      metadata.put("current-snapshot-id", -1L);
    }
  }

  private static void setProperties(ObjectNode metadata, JsonNode update) {
    ObjectNode properties = object(metadata, "properties");
    JsonNode updates = update.path("updates");
    if (!updates.isObject()) {
      throw IcebergCatalogException.badRequest("Invalid set-properties update: updates is required.");
    }
    for (Map.Entry<String, JsonNode> property : updates.properties()) {
      properties.put(property.getKey(), property.getValue().asString());
    }
  }

  private static void removeProperties(ObjectNode metadata, JsonNode update) {
    ObjectNode properties = object(metadata, "properties");
    for (JsonNode name : update.path("removals")) {
      properties.remove(name.asString());
    }
  }

  private static void setStatistics(ObjectNode metadata, JsonNode update, String field, String collection) {
    JsonNode statistics = update.get(field);
    if (statistics == null || !statistics.isObject()) {
      throw IcebergCatalogException.badRequest("Invalid statistics update: " + field + " is required.");
    }
    // The older shape of the update carries the snapshot ID beside the statistics rather than only inside them.
    long snapshotId = statistics.path("snapshot-id").asLong(update.path("snapshot-id").asLong(-1));
    ArrayNode collected = array(metadata, collection);
    for (int i = collected.size() - 1; i >= 0; i--) {
      if (collected.get(i).path("snapshot-id").asLong(-1) == snapshotId) {
        collected.remove(i);
      }
    }
    collected.add(statistics.deepCopy());
  }

  private static void removeStatistics(ObjectNode metadata, JsonNode update, String collection) {
    long snapshotId = required(update, "snapshot-id").asLong();
    ArrayNode collected = array(metadata, collection);
    for (int i = collected.size() - 1; i >= 0; i--) {
      if (collected.get(i).path("snapshot-id").asLong(-1) == snapshotId) {
        collected.remove(i);
      }
    }
  }

  /**
   * Remove the schemas or the partition specs of a set of IDs, refusing to remove the one that the table currently
   * uses: a table without its current schema can't be read.
   */
  private static void removeById(ObjectNode metadata, String collection, String idField, Set<Integer> ids,
                                 int inUse, String what) {
    if (ids.isEmpty()) {
      return;
    }
    if (ids.contains(inUse)) {
      throw IcebergCatalogException.badRequest("Cannot remove the " + what + ": " + inUse);
    }
    ArrayNode collected = array(metadata, collection);
    for (int i = collected.size() - 1; i >= 0; i--) {
      if (ids.contains(collected.get(i).path(idField).asInt(-1))) {
        collected.remove(i);
      }
    }
  }

  private static void enableRowLineage(ObjectNode metadata) {
    if (metadata.path("format-version").asInt(TableMetadataFactory.DEFAULT_FORMAT_VERSION) < 3) {
      throw IcebergCatalogException.badRequest("Cannot enable row lineage for a table below v3.");
    }
    metadata.put("row-lineage", true);
    if (!metadata.hasNonNull("next-row-id")) {
      metadata.put("next-row-id", 0L);
    }
  }

  private static void addEncryptionKey(ObjectNode metadata, JsonNode update) {
    ObjectNode key = objectField(update, "encryption-key");
    String keyId = key.path("key-id").asString("");
    ArrayNode keys = array(metadata, "encryption-keys");
    for (JsonNode existing : keys) {
      if (keyId.equals(existing.path("key-id").asString(""))) {
        return;
      }
    }
    keys.add(key);
  }

  private static void removeEncryptionKey(ObjectNode metadata, JsonNode update) {
    String keyId = required(update, "key-id").asString();
    ArrayNode keys = array(metadata, "encryption-keys");
    for (int i = keys.size() - 1; i >= 0; i--) {
      if (keyId.equals(keys.get(i).path("key-id").asString(""))) {
        keys.remove(i);
      }
    }
  }

  /*
   * View updates.
   */

  private static void addViewVersion(ObjectNode metadata, JsonNode update) {
    ObjectNode version = objectField(update, "view-version");
    ArrayNode versions = array(metadata, "versions");
    int versionId = version.hasNonNull("version-id") && version.path("version-id").asInt() > 0
        ? version.path("version-id").asInt() : nextId(versions, "version-id");
    version.put("version-id", versionId);
    if (!version.hasNonNull("timestamp-ms")) {
      version.put("timestamp-ms", System.currentTimeMillis());
    }
    replaceById(versions, "version-id", versionId, version);
    metadata.put("__last-added-version-id", versionId);
  }

  private static void setCurrentViewVersion(ObjectNode metadata, JsonNode update) {
    int requested = required(update, "view-version-id").asInt();
    int versionId = requested == -1 ? lastAdded(metadata, "__last-added-version-id", "view version") : requested;
    JsonNode version = findById(array(metadata, "versions"), "version-id", versionId);
    if (version == null) {
      throw IcebergCatalogException.badRequest("Cannot set current version to unknown version: " + versionId);
    }
    metadata.put("current-version-id", versionId);
    ObjectNode entry = IcebergJson.newObject();
    entry.put("timestamp-ms", version.path("timestamp-ms").asLong(System.currentTimeMillis()));
    entry.put("version-id", versionId);
    array(metadata, "version-log").add(entry);
  }

  /*
   * The tree helpers. The "__last-added-*" fields are LocalS3's own, and removed before the metadata is written,
   * see stripInternalFields(): a commit may say "the schema I just added" with -1, which is only resolvable while
   * the updates of that commit are being applied.
   */

  /**
   * Remove the fields that only the updates of a commit use, so that they never reach a metadata file or a client.
   *
   * @param metadata the metadata, which this modifies.
   * @return the metadata.
   */
  static ObjectNode stripInternalFields(ObjectNode metadata) {
    List<String> internal = new ArrayList<>();
    for (Map.Entry<String, JsonNode> field : metadata.properties()) {
      if (field.getKey().startsWith("__")) {
        internal.add(field.getKey());
      }
    }
    internal.forEach(metadata::remove);
    return metadata;
  }

  private static int lastAdded(ObjectNode metadata, String field, String what) {
    JsonNode id = metadata.get(field);
    if (id == null || !id.isIntegralNumber()) {
      throw IcebergCatalogException.badRequest("Cannot set the last added " + what + ": none was added.");
    }
    return id.asInt();
  }

  private static JsonNode required(JsonNode update, String field) {
    JsonNode value = update.get(field);
    if (value == null || value.isNull()) {
      throw IcebergCatalogException.badRequest(
          "Invalid " + update.path("action").asString("update") + ": " + field + " is required.");
    }
    return value;
  }

  private static ObjectNode objectField(JsonNode update, String field) {
    JsonNode value = required(update, field);
    if (!value.isObject()) {
      throw IcebergCatalogException.badRequest(
          "Invalid " + update.path("action").asString("update") + ": " + field + " must be an object.");
    }
    return (ObjectNode) value.deepCopy();
  }

  private static Set<Integer> ids(JsonNode update, String field) {
    Set<Integer> ids = new HashSet<>();
    for (JsonNode id : update.path(field)) {
      ids.add(id.asInt());
    }
    return ids;
  }

  private static ArrayNode array(ObjectNode metadata, String field) {
    JsonNode node = metadata.get(field);
    if (node instanceof ArrayNode array) {
      return array;
    }
    return metadata.putArray(field);
  }

  private static ObjectNode object(ObjectNode metadata, String field) {
    JsonNode node = metadata.get(field);
    if (node instanceof ObjectNode object) {
      return object;
    }
    return metadata.putObject(field);
  }

  @Nullable
  private static JsonNode findById(ArrayNode collection, String idField, int id) {
    for (JsonNode element : collection) {
      if (element.path(idField).asInt(-1) == id) {
        return element;
      }
    }
    return null;
  }

  @Nullable
  private static JsonNode findSnapshot(ObjectNode metadata, long snapshotId) {
    for (JsonNode snapshot : metadata.path("snapshots")) {
      if (snapshot.path("snapshot-id").asLong(-1) == snapshotId) {
        return snapshot;
      }
    }
    return null;
  }

  private static void replaceById(ArrayNode collection, String idField, int id, ObjectNode element) {
    for (int i = 0; i < collection.size(); i++) {
      if (collection.get(i).path(idField).asInt(-1) == id) {
        collection.set(i, element);
        return;
      }
    }
    collection.add(element);
  }

  private static int nextId(ArrayNode collection, String idField) {
    int max = -1;
    for (JsonNode element : collection) {
      max = Math.max(max, element.path(idField).asInt(-1));
    }
    return max + 1;
  }

  private static void copyIfPresent(JsonNode from, ObjectNode to, String field) {
    JsonNode value = from.get(field);
    if (value != null && !value.isNull()) {
      to.set(field, value.deepCopy());
    }
  }

}
