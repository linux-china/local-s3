package com.robothy.s3.core.iceberg;

import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Reads and writes the JSON of the Iceberg REST catalog: the documents of its requests and responses, and the table
 * metadata that a catalog writes to the object store.
 *
 * <p>The metadata is handled as a tree rather than as classes of its own. The Iceberg table specification is wide —
 * schemas, partition specs, sort orders, snapshots, refs, statistics, and more in every version — and a catalog only
 * decides a few of those fields; the rest it carries from the request to the metadata file unchanged. Reading it as a
 * tree keeps whatever a newer client sends intact, rather than dropping the fields that a model of today doesn't know,
 * and it is why LocalS3 needs no Iceberg library to serve the catalog.
 */
public final class IcebergJson {

  /**
   * Configured like the other mappers of LocalS3, i.e. the way Jackson 2 configured one, so that the properties of a
   * document stay in the order they were written in rather than sorted.
   */
  private static final JsonMapper MAPPER = JsonMapper.builderWithJackson2Defaults()
      .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
      .build();

  private IcebergJson() {
  }

  public static ObjectNode newObject() {
    return MAPPER.createObjectNode();
  }

  public static ArrayNode newArray() {
    return MAPPER.createArrayNode();
  }

  /**
   * Read a JSON document.
   *
   * @param json the document.
   * @return the tree.
   * @throws IcebergCatalogException if the document isn't a JSON object.
   */
  public static ObjectNode read(String json) {
    if (json == null || json.isBlank()) {
      return newObject();
    }
    JsonNode node;
    try {
      node = MAPPER.readTree(json);
    } catch (JacksonException e) {
      throw IcebergCatalogException.badRequest("Malformed JSON: " + e.getOriginalMessage());
    }
    if (!node.isObject()) {
      throw IcebergCatalogException.badRequest("Expected a JSON object.");
    }
    return (ObjectNode) node;
  }

  /**
   * Write a tree as a JSON document.
   *
   * @param node the tree.
   * @return the document.
   */
  public static String write(JsonNode node) {
    return MAPPER.writeValueAsString(node);
  }

  /**
   * A copy of an object node, or an empty one if the node is absent or isn't an object.
   *
   * @param node the node; {@code null} for none.
   * @return a new object node.
   */
  public static ObjectNode objectOrEmpty(JsonNode node) {
    return node != null && node.isObject() ? (ObjectNode) node.deepCopy() : newObject();
  }

  /**
   * The string properties of an object node, e.g. the properties of a namespace or of a table.
   *
   * @param node the node; {@code null} for none.
   * @return the properties, in the order they are written in; empty if there are none.
   */
  public static Map<String, String> toStringMap(JsonNode node) {
    Map<String, String> properties = new LinkedHashMap<>();
    if (node != null && node.isObject()) {
      for (Map.Entry<String, JsonNode> property : node.properties()) {
        JsonNode value = property.getValue();
        if (!value.isNull()) {
          properties.put(property.getKey(), value.asString());
        }
      }
    }
    return properties;
  }

  /**
   * An object node of a map of strings.
   *
   * @param properties the properties.
   * @return a new object node.
   */
  public static ObjectNode fromStringMap(Map<String, String> properties) {
    ObjectNode node = newObject();
    properties.forEach(node::put);
    return node;
  }

  /**
   * The largest field ID that a schema assigns, which is the {@code last-column-id} of a table whose schema it is.
   *
   * <p>Iceberg assigns an ID to every field of a struct ({@code id}), to the element of a list ({@code element-id})
   * and to the key and the value of a map ({@code key-id}, {@code value-id}), at any depth, so the whole schema is
   * walked rather than only its top-level fields: a table whose only nested field is inside a list of structs must
   * still report that field's ID, or the next commit of the client asserts against the wrong one.
   *
   * @param schema the schema.
   * @return the largest ID; {@code 0} if the schema assigns none.
   */
  public static int maxFieldId(JsonNode schema) {
    return maxFieldId(schema, 0);
  }

  private static int maxFieldId(JsonNode node, int max) {
    if (node == null) {
      return max;
    }
    if (node.isObject()) {
      for (Map.Entry<String, JsonNode> property : node.properties()) {
        String name = property.getKey();
        JsonNode value = property.getValue();
        if (value.isIntegralNumber()
            && ("id".equals(name) || "element-id".equals(name) || "key-id".equals(name) || "value-id".equals(name))) {
          max = Math.max(max, value.asInt());
        } else {
          max = maxFieldId(value, max);
        }
      }
    } else if (node.isArray()) {
      for (JsonNode element : node) {
        max = maxFieldId(element, max);
      }
    }
    return max;
  }

  /**
   * A location without its trailing {@code /}, so that joining it with a name doesn't double the separator.
   *
   * @param location the location.
   * @return the location without a trailing separator.
   */
  public static String stripTrailingSlash(String location) {
    String stripped = location;
    while (stripped.endsWith("/")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped;
  }

}
