package com.robothy.s3.core.service.s3vectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.robothy.s3.core.model.internal.s3vectors.VectorObjectMetadata;
import java.util.Objects;

/**
 * The condition that the metadata of a vector must satisfy for a
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_s3vectors_QueryVectors.html">QueryVectors</a>
 * request to return it, e.g. {@code {"genre": {"$eq": "documentary"}, "year": {"$gte": 2020}}}.
 * {@linkplain MetadataFilter} documents the operators it is written with.
 *
 * <p>It is the type that the search API of this module takes, rather than the JSON document the condition
 * arrives as: {@linkplain VectorSearchEngine} is a domain interface that anyone may implement, and an
 * implementation of it has no business depending on the JSON library that LocalS3 happens to read requests
 * with. {@linkplain #fromJson} is the one place that library reaches, i.e. the boundary that a request
 * crosses on its way in.
 *
 * <p>An instance is immutable and safe to share, and {@linkplain #none()} is the condition of a request
 * that carries none, which every vector satisfies.
 */
public final class MetadataFilterExpression {

  private static final MetadataFilterExpression NONE = new MetadataFilterExpression(null);

  /**
   * The condition as it arrived. Package private: {@linkplain MetadataFilter} evaluates it, and nothing
   * outside of this package reads it, so that the representation can change without changing the API.
   */
  private final JsonNode filter;

  private MetadataFilterExpression(JsonNode filter) {
    this.filter = filter;
  }

  /**
   * The condition of a request that carries none, which every vector satisfies.
   *
   * @return a condition that keeps every vector.
   */
  public static MetadataFilterExpression none() {
    return NONE;
  }

  /**
   * Read a condition off the JSON document of a request. This is the boundary that the JSON representation
   * is left behind at: the callers of the search API pass the returned instance, not the document.
   *
   * <p>The document is not validated here. An operator that no vector could be matched by, e.g. a
   * {@code $nonsense}, is reported when the condition is evaluated, which is where Amazon S3 reports it too,
   * as an {@code InvalidRequest} error.
   *
   * @param filter the {@code filter} of the request; {@code null} if it carries none.
   * @return the condition the document describes; {@linkplain #none()} if it describes none.
   */
  public static MetadataFilterExpression fromJson(JsonNode filter) {
    if (Objects.isNull(filter) || filter.isNull() || filter.isEmpty()) {
      return NONE;
    }
    return new MetadataFilterExpression(filter);
  }

  /**
   * Whether the condition holds for every vector, so that evaluating it would keep all of them.
   *
   * @return {@code true} if the request carries no condition.
   */
  public boolean isEmpty() {
    return Objects.isNull(filter);
  }

  /**
   * Whether a vector satisfies this condition.
   *
   * @param vector the vector to evaluate the condition against.
   * @return {@code true} if the metadata of the vector satisfies it.
   * @throws com.robothy.s3.core.exception.vectors.LocalS3VectorException if the condition is not a valid
   *     one, e.g. because it names an operator that doesn't exist.
   */
  public boolean matches(VectorObjectMetadata vector) {
    return isEmpty() || MetadataFilter.matches(vector, filter);
  }

  /**
   * The condition as it arrived, for {@linkplain MetadataFilter} to evaluate.
   */
  JsonNode json() {
    return filter;
  }

  @Override
  public String toString() {
    return "MetadataFilterExpression(" + (isEmpty() ? "none" : filter) + ")";
  }

}
