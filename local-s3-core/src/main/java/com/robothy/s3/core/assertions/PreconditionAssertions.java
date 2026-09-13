package com.robothy.s3.core.assertions;

import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.PreconditionFailedException;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import java.util.Objects;

/**
 * Evaluates the {@linkplain ObjectPreconditions} of a conditional request, in the order that
 * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13.2.2">RFC 9110, section 13.2.2</a>
 * defines: an entity tag condition takes precedence over the date condition it pairs with, so
 * {@code If-Match} makes {@code If-Unmodified-Since} irrelevant, and {@code If-None-Match} makes
 * {@code If-Modified-Since} irrelevant.
 */
public class PreconditionAssertions {

  /**
   * Names of the headers that a failed condition is reported with, which travel in the {@code Condition}
   * element of the error that Amazon S3 answers with.
   */
  public static final String IF_MATCH = "If-Match";

  public static final String IF_NONE_MATCH = "If-None-Match";

  public static final String IF_UNMODIFIED_SINCE = "If-Unmodified-Since";

  /**
   * Evaluate the preconditions of a conditional read, i.e. of {@code GetObject} or {@code HeadObject},
   * against the version of the object that the request resolved to.
   *
   * <p>A date is compared with the second that the object was last modified in, not with the millisecond,
   * because that is the precision of the {@code Last-Modified} header that the client derived the date it
   * sends from. Without the truncation an object stored at {@code 12:00:00.500} would count as modified
   * after the {@code 12:00:00} that a client read off its own copy of it.
   *
   * @param preconditions the preconditions of the request; {@linkplain ObjectPreconditions#none()} if it
   *     carries none.
   * @param etag the entity tag of the resolved version of the object.
   * @param lastModified the epoch milliseconds that the resolved version was stored at.
   * @return {@code true} if the client already holds the object, so that the read answers
   *     {@code 304 Not Modified} with no content; {@code false} to answer with the object.
   * @throws PreconditionFailedException if the {@code If-Match} or the {@code If-Unmodified-Since}
   *     condition didn't hold, which answers {@code 412 Precondition Failed}.
   */
  public static boolean assertReadPreconditionsHold(ObjectPreconditions preconditions, String etag,
                                                    long lastModified) {
    if (preconditions.isEmpty()) {
      return false;
    }

    if (Objects.nonNull(preconditions.getIfMatch())) {
      if (!anyEtagMatches(preconditions.getIfMatch(), etag)) {
        throw new PreconditionFailedException(IF_MATCH);
      }
    } else if (Objects.nonNull(preconditions.getIfUnmodifiedSince())
        && toSeconds(lastModified) > toSeconds(preconditions.getIfUnmodifiedSince())) {
      throw new PreconditionFailedException(IF_UNMODIFIED_SINCE);
    }

    if (Objects.nonNull(preconditions.getIfNoneMatch())) {
      return anyEtagMatches(preconditions.getIfNoneMatch(), etag);
    }
    return Objects.nonNull(preconditions.getIfModifiedSince())
        && toSeconds(lastModified) <= toSeconds(preconditions.getIfModifiedSince());
  }

  /**
   * Evaluate the preconditions of a conditional write, i.e. of {@code PutObject}, against the object that
   * the key holds. The caller must hold the write lock of the bucket, so that the object is stored in the
   * same locked section that the condition was evaluated in; otherwise the compare-and-swap that
   * {@code If-Match} implements wouldn't protect anything.
   *
   * <p>Only the entity tag conditions are evaluated, which are the ones that Amazon S3 documents for
   * {@code PutObject}; a date condition of a write is ignored, like it is there.
   *
   * @param preconditions the preconditions of the request; {@linkplain ObjectPreconditions#none()} if it
   *     carries none.
   * @param key the object key that is written, which an error reports.
   * @param objectMetadata the metadata of the object that the key holds; {@code null} if it holds none.
   * @throws PreconditionFailedException if the {@code If-Match} or the {@code If-None-Match} condition
   *     didn't hold, which answers {@code 412 Precondition Failed}.
   * @throws ObjectNotExistException if {@code If-Match} was given and the key holds no object, which
   *     Amazon S3 reports as a missing object rather than as a failed condition.
   */
  public static void assertWritePreconditionsHold(ObjectPreconditions preconditions, String key,
                                                  ObjectMetadata objectMetadata) {
    if (Objects.isNull(preconditions.getIfMatch()) && Objects.isNull(preconditions.getIfNoneMatch())) {
      return;
    }

    // A key whose latest version is a delete marker holds no object, like one that was never stored.
    VersionedObjectMetadata current = Objects.isNull(objectMetadata) || objectMetadata.getLatest().isDeleted()
        ? null : objectMetadata.getLatest();

    if (Objects.nonNull(preconditions.getIfMatch())) {
      if (Objects.isNull(current)) {
        throw new ObjectNotExistException(key);
      }
      if (!anyEtagMatches(preconditions.getIfMatch(), current.getEtag())) {
        throw new PreconditionFailedException(IF_MATCH);
      }
    }

    if (Objects.nonNull(preconditions.getIfNoneMatch()) && Objects.nonNull(current)
        && anyEtagMatches(preconditions.getIfNoneMatch(), current.getEtag())) {
      throw new PreconditionFailedException(IF_NONE_MATCH);
    }
  }

  /**
   * Whether an {@code If-Match} or {@code If-None-Match} header matches an object with the given entity
   * tag. {@linkplain ObjectPreconditions#WILDCARD} matches it whatever the tag is; otherwise the header is
   * a comma separated list of entity tags, which a client may also have sent as a repeated header, and
   * matches if it holds the one of the object.
   *
   * @param headerValue the value of the header.
   * @param etag the entity tag of the object; {@code null} if it has none.
   * @return {@code true} if the header matches the object.
   */
  private static boolean anyEtagMatches(String headerValue, String etag) {
    if (ObjectPreconditions.WILDCARD.equals(headerValue.trim())) {
      return true;
    }
    if (Objects.isNull(etag)) {
      return false;
    }

    String current = normalizeEtag(etag);
    for (String candidate : headerValue.split(",")) {
      if (!candidate.isBlank() && normalizeEtag(candidate).equals(current)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Drop the quotes and the weak validator prefix of an entity tag, so that a client that quotes a tag it
   * received, which is how Amazon S3 sends one, matches the unquoted tag that LocalS3 answers with. LocalS3
   * only ever derives an entity tag from the content of an object, i.e. a strong one, so {@code W/} never
   * tells two of them apart.
   */
  static String normalizeEtag(String etag) {
    String value = etag.trim();
    if (value.startsWith("W/")) {
      value = value.substring("W/".length()).trim();
    }
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      value = value.substring(1, value.length() - 1);
    }
    return value;
  }

  private static long toSeconds(long epochMilli) {
    return Math.floorDiv(epochMilli, 1000L);
  }

}
