package com.robothy.s3.core.model.request;

import java.util.Objects;

/**
 * The validator of an {@code If-Range} header, which {@linkplain com.robothy.s3.core.assertions.PreconditionAssertions}
 * evaluates; see {@code docs/semantics.md#range-requests}.
 *
 * <p>The header is parsed by the caller, which knows the HTTP date formats; a value that is neither an entity tag nor
 * an HTTP date is still a condition, one that never holds, since RFC 9110 serves the whole object when it can't tell
 * that the client's copy is current.
 */
public final class IfRange {

  private static final IfRange UNMATCHABLE = new IfRange(null, null);

  private final String entityTag;

  private final Long lastModified;

  private IfRange(String entityTag, Long lastModified) {
    this.entityTag = entityTag;
    this.lastModified = lastModified;
  }

  /**
   * @param entityTag the entity tag as sent, quoted, possibly with the {@code W/} prefix.
   */
  public static IfRange entityTag(String entityTag) {
    return new IfRange(Objects.requireNonNull(entityTag, "entityTag"), null);
  }

  /**
   * @param lastModified the HTTP date in epoch milliseconds.
   */
  public static IfRange date(long lastModified) {
    return new IfRange(null, lastModified);
  }

  /**
   * A value that is neither an entity tag nor an HTTP date.
   */
  public static IfRange unmatchable() {
    return UNMATCHABLE;
  }

  /**
   * @return the entity tag; {@code null} if the validator isn't one.
   */
  public String getEntityTag() {
    return entityTag;
  }

  /**
   * @return the date in epoch milliseconds; {@code null} if the validator isn't one.
   */
  public Long getLastModified() {
    return lastModified;
  }

}
