package com.robothy.s3.core.iceberg;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A table or a view of the Iceberg catalog, as the catalog store keeps it. It is a <em>pointer</em>, not the table: the
 * metadata of the table is a JSON file in the object store, which the catalog writes and this record names. That is
 * what an Iceberg catalog is, and it is what makes a commit atomic — replacing the pointer with
 * {@linkplain IcebergCatalogStore#replaceTable} is the one step that decides whether a commit won.
 *
 * @param namespace the levels of the namespace that holds the table.
 * @param name the name of the table.
 * @param view whether this is a view rather than a table; the two share a namespace of names.
 * @param metadataLocation the {@code s3://} location of the metadata file that the table currently is.
 * @param previousMetadataLocation the location the table was at before the last commit; {@code null} for a table
 *     that was never committed to.
 * @param version the number of the metadata file, which names the next one; it starts at {@code 0} and a commit
 *     increments it, like the {@code <version>-<uuid>.metadata.json} of a Hive or JDBC catalog.
 */
public record IcebergTableRecord(List<String> namespace, String name, boolean view, String metadataLocation,
                                 @Nullable String previousMetadataLocation, int version) {

  public IcebergTableRecord {
    namespace = List.copyOf(namespace);
  }

  /**
   * The identifier of this table.
   *
   * @return the identifier.
   */
  public IcebergIdentifier identifier() {
    return new IcebergIdentifier(namespace, name);
  }

  /**
   * The key that the catalog store keeps this table under.
   *
   * @return the key.
   */
  public String key() {
    return identifier().key();
  }

  /**
   * This table as the commit that wrote a new metadata file leaves it: at that file, with the current one behind it,
   * and one version further on.
   *
   * @param newMetadataLocation the location of the new metadata file.
   * @return a new record.
   */
  public IcebergTableRecord committed(String newMetadataLocation) {
    return new IcebergTableRecord(namespace, name, view, newMetadataLocation, metadataLocation, version + 1);
  }

  /**
   * This table under another identifier, which a rename moves it to.
   *
   * @param identifier the identifier of the result.
   * @return a new record.
   */
  public IcebergTableRecord renamedTo(IcebergIdentifier identifier) {
    return new IcebergTableRecord(identifier.namespace(), identifier.name(), view, metadataLocation,
        previousMetadataLocation, version);
  }

}
