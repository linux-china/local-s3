package com.robothy.s3.core.model.internal.s3vectors;

import com.robothy.s3.datatypes.s3vectors.DistanceMetric;
import com.robothy.s3.datatypes.s3vectors.VectorDataType;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Internal metadata representation for S3 Vector Indexes.
 * This class stores the persistent configuration and state of a vector index, including every vector
 * that was put into it. Like {@link com.robothy.s3.core.model.internal.BucketMetadata} it carries no
 * generated {@code equals}, {@code hashCode} and {@code toString}, which would walk and dump them.
 */
@Getter
@Setter
public class VectorIndexMetadata {

  /**
   * The name of the vector index.
   */
  private String indexName;

  /**
   * The number of dimensions for vectors in this index.
   * Must be between 1 and 4096 inclusive.
   */
  private int dimension;

  /**
   * The data type of vector elements.
   */
  private VectorDataType dataType;

  /**
   * The distance metric used for vector similarity calculations.
   */
  private DistanceMetric distanceMetric;

  /**
   * Schema for metadata fields that can be attached to vectors.
   * Key is the field name, value is the field type.
   */
  private Map<String, String> metadataSchema;

  /**
   * List of metadata keys that are not filterable.
   * These keys can be stored but cannot be used in queries for filtering.
   */
  private java.util.List<String> nonFilterableMetadataKeys;

  /**
   * Creation timestamp in milliseconds since epoch.
   */
  private long creationDate;

  /**
   * The current status of the vector index.
   * Possible values: "CREATING", "ACTIVE", "DELETING", "FAILED"
   */
  private String status;

  /**
   * The tags of this vector index, by key. Sorted, so that the stored settings of an index don't differ by the order
   * its tags were added in.
   */
  private ConcurrentSkipListMap<String, String> tags = new ConcurrentSkipListMap<>();

  /**
   * Collection of vector objects stored in this index.
   * Key is the vector ID, value is the vector object metadata.
   */
  private Map<String, VectorObjectMetadata> vectorObjects;

  /**
   * Whether a metadata store writes this index a vector at a time, and so needs to be told which vectors changed; see
   * {@linkplain #trackChanges()}. An index that no store tracks, e.g. of a service that keeps its vectors in memory,
   * records nothing, so that the IDs it would record don't pile up.
   */
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private transient volatile boolean tracksChanges;

  /**
   * The IDs of the vectors that were added, replaced or removed since the metadata store last wrote this index.
   */
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private final transient Set<String> changedVectorIds = ConcurrentHashMap.newKeySet();

  /**
   * Whether the vectors were changed as a whole, e.g. cleared or replaced by another map, since the metadata store last
   * wrote this index, so that it writes all of them rather than {@linkplain #changedVectorIds}.
   */
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private transient volatile boolean allVectorsChanged;

  /**
   * Create a VectorIndexMetadata instance.
   */
  public VectorIndexMetadata() {
    this.status = "CREATING";
    this.vectorObjects = new ConcurrentHashMap<>();
  }

  /**
   * Set the dimension and validate it's within allowed range.
   * 
   * @param dimension the dimension to set
   * @throws IllegalArgumentException if dimension is out of range
   */
  public void setDimension(int dimension) {
    if (dimension < 1 || dimension > 4096) {
      throw new IllegalArgumentException("Vector dimension must be between 1 and 4096, got: " + dimension);
    }
    this.dimension = dimension;
  }

  /**
   * Check if the index is in ACTIVE status.
   * 
   * @return true if the index is active
   */
  public boolean isActive() {
    return "ACTIVE".equals(status);
  }

  /**
   * Mark the index as active.
   */
  public void setActive() {
    this.status = "ACTIVE";
  }

  /**
   * Mark the index as failed.
   */
  public void setFailed() {
    this.status = "FAILED";
  }

  /**
   * Mark the index as deleting.
   */
  public void setDeleting() {
    this.status = "DELETING";
  }

  /**
   * Add a vector object to this index, replacing the vector object of the same ID.
   *
   * @param vectorObject the vector object metadata to add
   * @return the replaced vector object, whose vector data is no longer referenced; {@code null} if there was none.
   */
  public VectorObjectMetadata addVectorObject(VectorObjectMetadata vectorObject) {
    // Validate dimension compatibility
    vectorObject.validateDimension(this.dimension);
    VectorObjectMetadata replaced = vectorObjects.put(vectorObject.getVectorId(), vectorObject);
    recordChanged(vectorObject.getVectorId());
    return replaced;
  }

  /**
   * Get a vector object by ID.
   * 
   * @param vectorId the vector ID
   * @return the vector object metadata, or null if not found
   */
  public VectorObjectMetadata getVectorObject(String vectorId) {
    return vectorObjects.get(vectorId);
  }

  /**
   * Remove a vector object from this index.
   * 
   * @param vectorId the vector ID to remove
   * @return the removed vector object metadata, or null if not found
   */
  public VectorObjectMetadata removeVectorObject(String vectorId) {
    VectorObjectMetadata removed = vectorObjects.remove(vectorId);
    if (removed != null) {
      recordChanged(vectorId);
    }
    return removed;
  }

  /**
   * Check if a vector object exists in this index.
   * 
   * @param vectorId the vector ID to check
   * @return true if the vector exists
   */
  public boolean containsVectorObject(String vectorId) {
    return vectorObjects.containsKey(vectorId);
  }

  /**
   * Get the number of vector objects in this index.
   * 
   * @return the count of vector objects
   */
  public int getVectorObjectCount() {
    return vectorObjects.size();
  }

  /**
   * Clear all vector objects from this index.
   */
  public void clearVectorObjects() {
    vectorObjects.clear();
    recordAllChanged();
  }

  /**
   * Replace the vectors of this index, e.g. when the index is read.
   *
   * @param vectorObjects the vectors, by vector ID.
   */
  public void setVectorObjects(Map<String, VectorObjectMetadata> vectorObjects) {
    this.vectorObjects = vectorObjects;
    recordAllChanged();
  }

  /**
   * Start recording the vectors that change, so that a metadata store that has written this index, or read it, writes
   * only those; see {@linkplain #drainChangedVectorIds()}. The vectors are changed through the methods of this index,
   * not through the map of {@linkplain #getVectorObjects()}, whose changes aren't recorded.
   */
  public void trackChanges() {
    changedVectorIds.clear();
    allVectorsChanged = false;
    tracksChanges = true;
  }

  /**
   * Whether a metadata store tracks the changes of this index, see {@linkplain #trackChanges()}.
   *
   * @return {@code true} if the changes are recorded.
   */
  public boolean tracksChanges() {
    return tracksChanges;
  }

  /**
   * Take whether the vectors were changed as a whole since this was last called, and forget it. A store that gets
   * {@code true} writes every vector, and forgets the IDs of {@linkplain #drainChangedVectorIds()} as well.
   *
   * @return {@code true} if every vector needs to be written.
   */
  public boolean drainAllVectorsChanged() {
    boolean all = allVectorsChanged;
    allVectorsChanged = false;
    return all;
  }

  /**
   * Take the IDs of the vectors that were added, replaced or removed since this was last called, and forget them.
   * Called by the metadata store when it writes the index, which writes the vector an ID holds now, or deletes it.
   *
   * @return the IDs of the changed vectors.
   */
  public List<String> drainChangedVectorIds() {
    if (changedVectorIds.isEmpty()) {
      return List.of();
    }
    List<String> drained = List.copyOf(changedVectorIds);
    drained.forEach(changedVectorIds::remove);
    return drained;
  }

  private void recordChanged(String vectorId) {
    if (tracksChanges) {
      changedVectorIds.add(vectorId);
    }
  }

  private void recordAllChanged() {
    if (tracksChanges) {
      allVectorsChanged = true;
      changedVectorIds.clear();
    }
  }

  /**
   * Names the index and its configuration, and leaves out the vectors it holds; read those through
   * {@linkplain #getVectorObjects()}, or count them with {@linkplain #getVectorObjectCount()}.
   */
  @Override
  public String toString() {
    return "VectorIndexMetadata(indexName=" + indexName + ", dimension=" + dimension
        + ", dataType=" + dataType + ", distanceMetric=" + distanceMetric + ", status=" + status + ")";
  }

}
