package com.robothy.s3.core.model.internal;

import com.fasterxml.jackson.annotation.JsonValue;
import com.robothy.s3.core.util.JsonUtils;
import java.util.Objects;
import java.util.function.Function;

/**
 * A reference to the metadata of one object key of a bucket, which the bucket keeps instead of the metadata itself.
 *
 * <p>A bucket loaded from a metadata store holds a reference per key, and reads the metadata of a key from the store
 * the first time something needs it, see {@linkplain #lazy}. That is what keeps the heap of a service that opens a data
 * directory proportional to the number of keys it holds rather than to the size of all of their metadata: a key costs
 * its name and this reference until it is read. A bucket that keeps its objects in memory holds references that are
 * loaded already, see {@linkplain #of}, which cost nothing beyond the reference itself.
 *
 * <p>A loaded reference may be {@linkplain #evict() evicted} again by the {@linkplain ObjectMetadataCache} that bounds
 * how many of them are in heap at once, and reads its metadata again when it is next needed. A reference that a change
 * has taken hold of is {@linkplain #pin() pinned} until the change is written, so that the change, which is made on the
 * metadata in heap, can't be dropped before it is persisted.
 *
 * <p>It carries no {@code equals} and {@code hashCode} of its own: a reference stands for the metadata of one key of
 * one bucket, so two of them are the same reference only if they are the same instance, which is also what lets the
 * cache hold them.
 */
public final class ObjectMetadataRef {

  /**
   * Reads the persisted form of the metadata of a key, as JSON; {@code null} until the metadata has been written, so
   * that there is something to read it back from. Assigned once, by {@linkplain #attach}.
   */
  private volatile Function<String, String> source;

  private volatile String key;

  /**
   * Bounds how many references keep their metadata in heap; {@code null} until {@linkplain #attach}.
   */
  private volatile ObjectMetadataCache cache;

  /**
   * The metadata in heap; {@code null} if it has not been read yet, or was evicted. Guarded by {@code this} for
   * writing, so that concurrent readers of a key get the one instance rather than a copy each.
   */
  private volatile ObjectMetadata metadata;

  /**
   * The number of characters of the persisted form of the metadata, as an estimate of what holding it costs;
   * {@code 0} until it has been read from, or written to, a store.
   */
  private volatile int persistedSize;

  /**
   * Whether a change has taken hold of the metadata and it has not been written yet, which keeps it in heap.
   */
  private volatile boolean pinned;

  private ObjectMetadataRef(String key, ObjectMetadata metadata, Function<String, String> source,
                            ObjectMetadataCache cache) {
    this.key = key;
    this.metadata = metadata;
    this.source = source;
    this.cache = cache;
  }

  /**
   * A reference to metadata that is already in heap, e.g. the metadata of an object that was just stored, or of a
   * bucket that keeps its objects in memory. It can't be evicted until a store has written it and
   * {@linkplain #attach attached} it, since until then there is nothing to read it back from.
   *
   * @param metadata the metadata, not {@code null}.
   * @return the reference.
   */
  public static ObjectMetadataRef of(ObjectMetadata metadata) {
    return new ObjectMetadataRef(null, Objects.requireNonNull(metadata, "metadata"), null, null);
  }

  /**
   * A reference to the metadata of a key that is kept in a store, read the first time it is needed.
   *
   * @param key the object key.
   * @param source reads the persisted form of the metadata of a key, as JSON.
   * @param cache bounds how many such references keep their metadata in heap.
   * @return the reference.
   */
  public static ObjectMetadataRef lazy(String key, Function<String, String> source, ObjectMetadataCache cache) {
    return new ObjectMetadataRef(Objects.requireNonNull(key, "key"), null, Objects.requireNonNull(source, "source"),
        Objects.requireNonNull(cache, "cache"));
  }

  /**
   * The metadata, read from the store if it is not in heap, and kept there.
   *
   * @return the metadata; never {@code null}.
   * @throws IllegalStateException if the store no longer holds the key, which the bucket that references it does.
   */
  public ObjectMetadata get() {
    ObjectMetadata inHeap = metadata;
    ObjectMetadata answer = inHeap == null ? load() : inHeap;
    ObjectMetadataCache bound = cache;
    if (bound != null) {
      // Every read, not only the ones that had to load, so that the references in heap are the ones in use.
      bound.recordRead(this);
    }
    return answer;
  }

  /**
   * The metadata, without keeping it in heap if it had to be read: for the callers that walk every object of a bucket
   * once, e.g. counting them, which would otherwise evict everything that is cached in favour of what they walk.
   *
   * @return the metadata; never {@code null}.
   */
  public ObjectMetadata read() {
    ObjectMetadata inHeap = metadata;
    return inHeap == null ? parse(readJson()) : inHeap;
  }

  /**
   * The metadata as it is written, e.g. when a whole bucket is copied as JSON: a reference is written as the metadata
   * it stands for, and read back by {@linkplain com.robothy.s3.core.converters.deserializer.ObjectMetadataMapConverter}
   * as a reference that is loaded. Reading it doesn't cache it, so writing a bucket doesn't evict what is in use.
   *
   * @return the metadata.
   */
  @JsonValue
  public ObjectMetadata persisted() {
    return read();
  }

  /**
   * Whether the metadata is in heap, i.e. whether {@linkplain #get()} answers without reading the store.
   *
   * @return {@code true} if the metadata is in heap.
   */
  public boolean isLoaded() {
    return metadata != null;
  }

  /**
   * An estimate of what holding this metadata costs, as the number of characters of its persisted form; {@code 0} if
   * it has neither been read from nor written to a store, e.g. an object that was just stored.
   *
   * @return the size of the persisted form.
   */
  public int persistedSize() {
    return persistedSize;
  }

  /**
   * Record the size of the persisted form of the metadata, called by a store that wrote it.
   *
   * @param size the number of characters written.
   */
  public void persistedSize(int size) {
    this.persistedSize = size;
  }

  /**
   * Keep the metadata in heap until it is written, because a change has taken hold of it. Changes are made on the
   * instance that {@linkplain #get()} answers, so dropping it would drop them.
   */
  public void pin() {
    this.pinned = true;
  }

  /**
   * Let the metadata be evicted again, called by a store that has written it.
   */
  public void unpin() {
    this.pinned = false;
  }

  /**
   * Give a reference that was created in heap something to read its metadata back from, called by a store that has
   * written it. Until then the metadata is the only copy and is kept whatever the cache holds; afterwards the
   * reference behaves like one that was read from the store, so a session that stores a million objects doesn't end
   * up holding the metadata of all of them.
   *
   * <p>Does nothing to a reference that has a source already, so writing an object again is not a special case.
   *
   * @param key the object key.
   * @param source reads the persisted form of the metadata of a key, as JSON.
   * @param cache bounds how many references keep their metadata in heap.
   */
  public void attach(String key, Function<String, String> source, ObjectMetadataCache cache) {
    if (this.source != null) {
      return;
    }
    synchronized (this) {
      if (this.source != null) {
        return;
      }
      this.key = Objects.requireNonNull(key, "key");
      this.cache = Objects.requireNonNull(cache, "cache");
      // Assigned last: it is what the other two are read together with, and what makes the reference evictable.
      this.source = Objects.requireNonNull(source, "source");
    }
  }

  /**
   * Drop the metadata from heap, so that it is read again when it is next needed. Does nothing to a reference that is
   * pinned, or that has nothing to read it back from.
   *
   * @return {@code true} if the metadata was dropped.
   */
  public boolean evict() {
    if (pinned || source == null) {
      return false;
    }
    synchronized (this) {
      if (pinned || metadata == null) {
        return false;
      }
      metadata = null;
      return true;
    }
  }

  private ObjectMetadata load() {
    synchronized (this) {
      ObjectMetadata inHeap = metadata;
      if (inHeap != null) {
        return inHeap;
      }
      ObjectMetadata parsed = parse(readJson());
      metadata = parsed;
      return parsed;
    }
  }

  private String readJson() {
    Function<String, String> from = source;
    if (from == null) {
      // A reference with no source is never evicted, so its metadata is there; this can't be reached.
      throw new IllegalStateException("The metadata of an object that is only in memory is gone.");
    }
    String json = from.apply(key);
    if (json == null) {
      throw new IllegalStateException("The store no longer holds the metadata of object '" + key + "'.");
    }
    return json;
  }

  private ObjectMetadata parse(String json) {
    persistedSize = json.length();
    return JsonUtils.fromJson(json, ObjectMetadata.class);
  }

  @Override
  public String toString() {
    return "ObjectMetadataRef(key=" + key + ", loaded=" + isLoaded() + ")";
  }

}
