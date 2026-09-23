package com.robothy.s3.core.storage;

import java.util.List;

/**
 * Metadata store abstraction.
 *
 * <p>A failure to read or write metadata is reported with an {@linkplain java.io.UncheckedIOException},
 * whose cause is the {@linkplain java.io.IOException} of the file system.
 *
 * @param <T> the metadata type.
 */
public interface MetadataStore<T> {

  /**
   * Fet metadata from current metadata store.
   *
   * @param name the metadata instance name.
   * @return fetched metadata instance.
   */
  T fetch(String name);

  /**
   * Store a metadata instance. If a metadata with the same name already exist
   * in the store, the existing one will be overridden.
   *
   * <p>The change is visible to the readers of the store at once, and is made durable by {@linkplain #sync()}.
   *
   * @param metadataObject metadata instance to store.
   * @return the name of stored metadata.
   */
  String store(String name, T metadataObject);

  /**
   * Check whether a metadata instance exists in the store.
   *
   * @param name the metadata instance name.
   * @return {@code true} if the metadata exists; otherwise {@code false}.
   */
  boolean exists(String name);

  /**
   * Delete a metadata from store by name.
   *
   * @param name metadata instance name.
   * @throws IllegalStateException if the metadata not exist in the store.
   */
  void delete(String name);

  /**
   * Make the changes that {@linkplain #store} and {@linkplain #delete} wrote so far durable, as far as the store
   * promises that, e.g. commit them to its file. Called once a change is written and its lock released, so that the
   * changes of concurrent requests are made durable together rather than one by one.
   *
   * <p>A store that has nothing to make durable, e.g. one in memory, does nothing.
   */
  default void sync() {
  }

  /**
   * Fetch all metadata instances from the store.
   *
   * @return all metadata instances in the store.
   */
  List<T> fetchAll();

}
