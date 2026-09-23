package com.robothy.s3.core.iceberg;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the metadata of a table or a view that is being created, in the JSON of the
 * <a href="https://iceberg.apache.org/spec/#table-metadata">Iceberg table specification</a>, which is what the
 * {@code metadata.json} of the table holds and what a {@code LoadTableResult} carries.
 *
 * <p>LocalS3 builds this JSON itself, rather than through the Iceberg library, so that a service stays as small and
 * quick to start as it is. What it decides is what a catalog decides: the table UUID, the field IDs of the schema,
 * which are re-assigned from {@code 1} (see {@linkplain FreshSchemaIds}), the IDs that the schema, the partition spec
 * and the sort order are registered under, and the derived {@code last-column-id} and {@code last-partition-id} that a
 * later commit asserts against.
 */
final class TableMetadataFactory {

  /**
   * The format version of a table that the request doesn't choose one for. Version 2 is what the Iceberg clients
   * write by default, and every engine that speaks the REST protocol reads it.
   */
  static final int DEFAULT_FORMAT_VERSION = 2;

  /**
   * The largest format version that LocalS3 keeps: a table may be created at, or upgraded to, version 3, whose
   * fields, e.g. row lineage, LocalS3 carries through without interpreting them.
   */
  static final int MAX_FORMAT_VERSION = 3;

  /**
   * The property that a create request sets the format version with, which isn't kept among the properties of the
   * table: it becomes {@code format-version}.
   */
  static final String FORMAT_VERSION_PROPERTY = "format-version";

  /**
   * The ID before the first one that a partition field gets, which is what {@code last-partition-id} is for an
   * unpartitioned table.
   */
  static final int PARTITION_FIELD_ID_START = 1000;

  /**
   * The first format version that assigns a row ID to every row, which is what {@code next-row-id} counts. A table of
   * this version or newer carries it, and a commit that adds a snapshot advances it.
   */
  static final int ROW_LINEAGE_FORMAT_VERSION = 3;

  private TableMetadataFactory() {
  }

  /**
   * Build the metadata of a new table.
   *
   * @param location the location of the table, e.g. {@code s3://warehouse/db/orders}.
   * @param request the {@code CreateTableRequest}: its {@code schema}, and optionally its {@code partition-spec},
   *     {@code write-order} and {@code properties}.
   * @return the metadata of an empty table, which holds no snapshot.
   * @throws IcebergCatalogException if the request carries no schema, or one that can't be read.
   */
  static ObjectNode createTableMetadata(String location, ObjectNode request) {
    JsonNode schema = request.get("schema");
    if (schema == null || !schema.isObject()) {
      throw IcebergCatalogException.badRequest("Invalid create table request: schema is required.");
    }
    ObjectNode properties = IcebergJson.objectOrEmpty(request.get("properties"));
    int formatVersion = formatVersion(properties);

    ObjectNode metadata = IcebergJson.newObject();
    metadata.put("format-version", formatVersion);
    metadata.put("table-uuid", UUID.randomUUID().toString());
    metadata.put("location", location);
    if (formatVersion > 1) {
      metadata.put("last-sequence-number", 0);
    }
    metadata.put("last-updated-ms", System.currentTimeMillis());

    ObjectNode currentSchema = (ObjectNode) schema.deepCopy();
    currentSchema.put("schema-id", 0);
    ObjectNode spec = partitionSpec(request.get("partition-spec"));
    ObjectNode sortOrder = sortOrder(request.get("write-order"));
    // The IDs of a new table are the catalog's to assign, and the spec and the order follow the fields they sort and
    // partition by, so the three are numbered together.
    metadata.put("last-column-id", FreshSchemaIds.assign(currentSchema, spec, sortOrder));
    metadata.set("schemas", IcebergJson.newArray().add(currentSchema));
    metadata.put("current-schema-id", 0);

    metadata.set("partition-specs", IcebergJson.newArray().add(spec));
    metadata.put("default-spec-id", 0);
    metadata.put("last-partition-id", lastPartitionId(spec));

    metadata.set("sort-orders", IcebergJson.newArray().add(sortOrder));
    metadata.put("default-sort-order-id", sortOrder.path("order-id").asInt(0));

    metadata.set("properties", properties);
    metadata.put("current-snapshot-id", -1L);
    if (formatVersion >= ROW_LINEAGE_FORMAT_VERSION) {
      // Row lineage is always on from v3 on, and next-row-id is not optional there: a client cannot read a v3 table
      // whose metadata is missing it.
      metadata.put("next-row-id", 0L);
    }
    metadata.set("refs", IcebergJson.newObject());
    metadata.set("snapshots", IcebergJson.newArray());
    metadata.set("statistics", IcebergJson.newArray());
    metadata.set("partition-statistics", IcebergJson.newArray());
    metadata.set("snapshot-log", IcebergJson.newArray());
    metadata.set("metadata-log", IcebergJson.newArray());
    return metadata;
  }

  /**
   * Build the metadata of a new view, whose shape differs from a table's: it has versions rather than snapshots, and
   * a version carries the SQL of the view.
   *
   * @param location the location of the view.
   * @param request the {@code CreateViewRequest}: its {@code schema}, {@code view-version} and {@code properties}.
   * @return the metadata of the view.
   * @throws IcebergCatalogException if the request carries no schema or no view version.
   */
  static ObjectNode createViewMetadata(String location, ObjectNode request) {
    JsonNode schema = request.get("schema");
    if (schema == null || !schema.isObject()) {
      throw IcebergCatalogException.badRequest("Invalid create view request: schema is required.");
    }
    JsonNode version = request.get("view-version");
    if (version == null || !version.isObject()) {
      throw IcebergCatalogException.badRequest("Invalid create view request: view-version is required.");
    }

    ObjectNode metadata = IcebergJson.newObject();
    metadata.put("view-uuid", UUID.randomUUID().toString());
    metadata.put("format-version", 1);
    metadata.put("location", location);

    ObjectNode currentSchema = (ObjectNode) schema.deepCopy();
    currentSchema.put("schema-id", 0);
    metadata.set("schemas", IcebergJson.newArray().add(currentSchema));

    ObjectNode viewVersion = (ObjectNode) version.deepCopy();
    viewVersion.put("version-id", 1);
    viewVersion.put("schema-id", 0);
    IcebergMetadataUpdater.checkOneQueryPerDialect(viewVersion);
    if (!viewVersion.has("timestamp-ms")) {
      viewVersion.put("timestamp-ms", System.currentTimeMillis());
    }
    metadata.set("versions", IcebergJson.newArray().add(viewVersion));
    metadata.put("current-version-id", 1);

    ArrayNode versionLog = IcebergJson.newArray();
    ObjectNode entry = IcebergJson.newObject();
    entry.put("timestamp-ms", viewVersion.path("timestamp-ms").asLong());
    entry.put("version-id", 1);
    versionLog.add(entry);
    metadata.set("version-log", versionLog);

    metadata.set("properties", IcebergJson.objectOrEmpty(request.get("properties")));
    return metadata;
  }

  /**
   * The format version that a create request asks for, which it passes as the {@code format-version} property, and
   * which is then removed from the properties: it belongs to the metadata itself.
   *
   * @param properties the properties of the request, which this modifies.
   * @return the format version.
   * @throws IcebergCatalogException if the property isn't a supported version.
   */
  private static int formatVersion(ObjectNode properties) {
    JsonNode property = properties.remove(FORMAT_VERSION_PROPERTY);
    if (property == null || property.isNull()) {
      return DEFAULT_FORMAT_VERSION;
    }
    int version;
    try {
      version = property.isNumber() ? property.asInt() : Integer.parseInt(property.asString().trim());
    } catch (NumberFormatException e) {
      throw IcebergCatalogException.badRequest("Invalid format version: " + property.asString());
    }
    if (version < 1 || version > MAX_FORMAT_VERSION) {
      throw IcebergCatalogException.badRequest("Unsupported format version: v" + version);
    }
    return version;
  }

  /**
   * The partition spec of a create request, registered as spec {@code 0}. A request without one, or with an empty
   * one, gets the unpartitioned spec.
   *
   * @param requested the {@code partition-spec} of the request; {@code null} for none.
   * @return the spec, with its {@code spec-id} and the field IDs of its fields assigned.
   */
  private static ObjectNode partitionSpec(JsonNode requested) {
    ObjectNode spec = IcebergJson.newObject();
    spec.put("spec-id", 0);
    ArrayNode fields = IcebergJson.newArray();
    if (requested != null && requested.isObject()) {
      JsonNode requestedFields = requested.get("fields");
      if (requestedFields != null && requestedFields.isArray()) {
        int nextFieldId = PARTITION_FIELD_ID_START;
        for (JsonNode requestedField : requestedFields) {
          ObjectNode field = (ObjectNode) requestedField.deepCopy();
          if (!field.hasNonNull("field-id")) {
            field.put("field-id", nextFieldId);
          }
          nextFieldId = Math.max(nextFieldId, field.path("field-id").asInt()) + 1;
          fields.add(field);
        }
      }
    }
    spec.set("fields", fields);
    return spec;
  }

  /**
   * The {@code last-partition-id} of a table whose only spec is this one: the largest field ID it assigns, or
   * {@value #PARTITION_FIELD_ID_START}{@code  - 1} if it is unpartitioned, which is what Iceberg records for a table
   * that has never had a partition field.
   *
   * @param spec the partition spec.
   * @return the last assigned partition field ID.
   */
  static int lastPartitionId(ObjectNode spec) {
    int last = PARTITION_FIELD_ID_START - 1;
    for (JsonNode field : spec.path("fields")) {
      last = Math.max(last, field.path("field-id").asInt(last));
    }
    return last;
  }

  /**
   * The sort order of a create request, registered as order {@code 0} when it sorts by nothing and order {@code 1}
   * otherwise: Iceberg reserves order {@code 0} for the unsorted order.
   *
   * @param requested the {@code write-order} of the request; {@code null} for none.
   * @return the sort order.
   */
  private static ObjectNode sortOrder(JsonNode requested) {
    ArrayNode fields = IcebergJson.newArray();
    if (requested != null && requested.isObject()) {
      JsonNode requestedFields = requested.get("fields");
      if (requestedFields != null && requestedFields.isArray()) {
        requestedFields.forEach(field -> fields.add(field.deepCopy()));
      }
    }
    ObjectNode order = IcebergJson.newObject();
    order.put("order-id", fields.isEmpty() ? 0 : 1);
    order.set("fields", fields);
    return order;
  }

  /**
   * The location of a table that a create request doesn't name one for: the location of its namespace, or the
   * warehouse, followed by the levels of the namespace and the name of the table.
   *
   * @param warehouse the warehouse location, without a trailing {@code /}.
   * @param namespaceLocation the {@code location} property of the namespace; {@code null} if it has none.
   * @param namespace the levels of the namespace.
   * @param name the name of the table.
   * @param unique whether to end the location in a random suffix rather than in the name alone, so that a table
   *     created under the name of a dropped one doesn't land among the files of that one.
   * @return the location.
   */
  static String defaultLocation(String warehouse, String namespaceLocation, List<String> namespace, String name,
                                boolean unique) {
    StringBuilder location = new StringBuilder();
    if (namespaceLocation != null && !namespaceLocation.isBlank()) {
      location.append(IcebergJson.stripTrailingSlash(namespaceLocation));
    } else {
      location.append(IcebergJson.stripTrailingSlash(warehouse));
      for (String level : namespace) {
        location.append('/').append(level);
      }
    }
    location.append('/').append(name);
    if (unique) {
      // The suffix that the Iceberg catalogs add for unique-table-location: the name, a dash and a random ID.
      location.append('-').append(UUID.randomUUID().toString().replace("-", ""));
    }
    return location.toString();
  }

  /**
   * The name of the metadata file of a version of a table, e.g. {@code 00003-b1f0...json}, which is how a Hive or a
   * JDBC catalog names it: the version padded to five digits, so that the files of a table sort in order, and a
   * random ID, so that two writers never pick the same name.
   *
   * @param version the version number.
   * @return the file name.
   */
  static String metadataFileName(int version) {
    return String.format(Locale.ROOT, "%05d-%s.metadata.json", version, UUID.randomUUID());
  }

}
