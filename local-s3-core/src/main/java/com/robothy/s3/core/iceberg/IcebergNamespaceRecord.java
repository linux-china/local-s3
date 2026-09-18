package com.robothy.s3.core.iceberg;

import java.util.List;
import java.util.Map;

/**
 * A namespace of the Iceberg catalog, as the catalog store keeps it: its levels and its properties. The tables of a
 * namespace aren't part of it, they are records of their own, so that creating a table writes one record rather than
 * the namespace and everything in it.
 *
 * @param levels the levels of the namespace, e.g. {@code [db, schema]}.
 * @param properties the properties of the namespace, e.g. its {@code location}; empty if it has none.
 */
public record IcebergNamespaceRecord(List<String> levels, Map<String, String> properties) {

  public IcebergNamespaceRecord {
    levels = List.copyOf(levels);
    properties = Map.copyOf(properties);
  }

  /**
   * The key that the catalog store keeps this namespace under.
   *
   * @return the key.
   */
  public String key() {
    return IcebergIdentifier.namespaceKey(levels);
  }

  /**
   * This namespace with other properties.
   *
   * @param newProperties the properties of the result.
   * @return a new record.
   */
  public IcebergNamespaceRecord withProperties(Map<String, String> newProperties) {
    return new IcebergNamespaceRecord(levels, newProperties);
  }

}
