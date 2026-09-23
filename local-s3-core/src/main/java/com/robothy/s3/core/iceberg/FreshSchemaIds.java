package com.robothy.s3.core.iceberg;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Assigns the field IDs of a table that is being created, which is the catalog's job rather than the client's.
 *
 * <p>The schema in a {@code CreateTableRequest} carries the IDs of wherever the client got it from — a Parquet file, a
 * {@code SELECT}, another table — and those IDs may be anything: {@code Schema.SCHEMA} in the Iceberg tests starts at
 * {@code 3}. A catalog doesn't keep them. It re-assigns the IDs of a new table from {@code 1}, in the order the fields
 * are written, which is what {@code TableMetadata.newTableMetadata} does in the Iceberg library and therefore what
 * every engine expects to read back: a client that creates a table and then asserts on its schema compares against the
 * re-assigned IDs, and one that appends to it writes data files whose field IDs are those.
 *
 * <p>Re-assigning the IDs means everything that points at a field has to move with it: the identifier fields of the
 * schema, the {@code source-id} of every partition field, and the {@code source-id} of every sort field. They are
 * remapped through the same old-to-new mapping, so a table partitioned by {@code bucket(id, 16)} still is after its
 * {@code id} column has become field {@code 1}.
 *
 * <p>The order the IDs are assigned in is Iceberg's: the direct fields of a struct all get their ID before the nested
 * types of any of them are visited, a list assigns its {@code element-id} before descending into the element, and a map
 * assigns {@code key-id} and then {@code value-id} before either of them. A schema that is nested differently would
 * otherwise come back with the fields numbered differently than the client's own copy of it.
 */
final class FreshSchemaIds {

  private FreshSchemaIds() {
  }

  /**
   * Assign fresh field IDs to the schema of a table being created, and remap what refers to them.
   *
   * @param schema the schema, which this modifies.
   * @param spec the partition spec of the table, whose {@code source-id}s are remapped; {@code null} for none.
   * @param sortOrder the sort order of the table, whose {@code source-id}s are remapped; {@code null} for none.
   * @return the largest ID assigned, which is the {@code last-column-id} of the table.
   * @throws IcebergCatalogException if the partition spec or the sort order names a field the schema doesn't hold.
   */
  static int assign(ObjectNode schema, @Nullable ObjectNode spec, @Nullable ObjectNode sortOrder) {
    Map<Integer, Integer> reassigned = new HashMap<>();
    Counter counter = new Counter();
    assignStruct(schema, reassigned, counter);
    remapIds(schema.get("identifier-field-ids"), reassigned, "identifier field");
    remapSourceIds(spec, reassigned, "partition spec");
    remapSourceIds(sortOrder, reassigned, "sort order");
    return counter.last;
  }

  /**
   * Assign the IDs of the direct fields of a struct, and only then descend into their types, which is the order the
   * Iceberg library assigns them in.
   */
  private static void assignStruct(ObjectNode struct, Map<Integer, Integer> reassigned, Counter counter) {
    JsonNode fields = struct.get("fields");
    if (fields == null || !fields.isArray()) {
      return;
    }
    for (JsonNode field : fields) {
      if (field instanceof ObjectNode object) {
        assignId(object, "id", reassigned, counter);
      }
    }
    for (JsonNode field : fields) {
      if (field instanceof ObjectNode object) {
        assignType(object.get("type"), reassigned, counter);
      }
    }
  }

  /**
   * Assign the IDs that a type holds: a struct's fields, a list's element, or a map's key and value. A primitive type,
   * which is a string rather than an object, holds none; nor does a type that LocalS3 doesn't know, e.g. one that a
   * newer specification adds, which is carried through as it came.
   */
  private static void assignType(@Nullable JsonNode type, Map<Integer, Integer> reassigned, Counter counter) {
    if (!(type instanceof ObjectNode object)) {
      return;
    }
    switch (object.path("type").asString("")) {
      case "struct" -> assignStruct(object, reassigned, counter);
      case "list" -> {
        assignId(object, "element-id", reassigned, counter);
        assignType(object.get("element"), reassigned, counter);
      }
      case "map" -> {
        assignId(object, "key-id", reassigned, counter);
        assignId(object, "value-id", reassigned, counter);
        assignType(object.get("key"), reassigned, counter);
        assignType(object.get("value"), reassigned, counter);
      }
      default -> {
        // A type without fields of its own, e.g. variant.
      }
    }
  }

  /**
   * Replace one ID with the next fresh one, remembering what it was so that a reference to it can be remapped.
   */
  private static void assignId(ObjectNode node, String field, Map<Integer, Integer> reassigned, Counter counter) {
    JsonNode current = node.get(field);
    int assigned = counter.next();
    if (current != null && current.isIntegralNumber()) {
      reassigned.put(current.asInt(), assigned);
    }
    node.put(field, assigned);
  }

  /**
   * Remap the {@code source-id}, or the {@code source-ids}, of every field of a partition spec or a sort order.
   */
  private static void remapSourceIds(@Nullable ObjectNode specOrOrder, Map<Integer, Integer> reassigned, String what) {
    if (specOrOrder == null) {
      return;
    }
    for (JsonNode field : specOrOrder.path("fields")) {
      if (!(field instanceof ObjectNode object)) {
        continue;
      }
      JsonNode sourceId = object.get("source-id");
      if (sourceId != null && sourceId.isIntegralNumber()) {
        object.put("source-id", remap(sourceId.asInt(), reassigned, what));
      }
      // A transform of several arguments names them in source-ids instead, e.g. a v3 spec.
      remapIds(object.get("source-ids"), reassigned, what);
    }
  }

  /**
   * Remap every ID of an array of field IDs, in place.
   */
  private static void remapIds(@Nullable JsonNode ids, Map<Integer, Integer> reassigned, String what) {
    if (!(ids instanceof ArrayNode array)) {
      return;
    }
    for (int i = 0; i < array.size(); i++) {
      JsonNode id = array.get(i);
      if (id != null && id.isIntegralNumber()) {
        array.set(i, remap(id.asInt(), reassigned, what));
      }
    }
  }

  private static int remap(int id, Map<Integer, Integer> reassigned, String what) {
    Integer assigned = reassigned.get(id);
    if (assigned == null) {
      throw IcebergCatalogException.badRequest("Invalid create table request: the " + what + " names the field "
          + id + ", which the schema of the table doesn't hold.");
    }
    return assigned;
  }

  /**
   * The IDs handed out, which start at {@code 1}: {@code last-column-id} is {@code 0} for a table with no field at all.
   */
  private static final class Counter {

    private int last;

    private int next() {
      return ++last;
    }

  }

}
