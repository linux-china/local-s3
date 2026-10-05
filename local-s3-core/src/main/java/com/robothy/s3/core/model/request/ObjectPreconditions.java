package com.robothy.s3.core.model.request;

import java.util.Objects;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * The preconditions of a conditional request, the {@code If-*} headers of
 * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13">RFC 9110, section 13</a>; which operation evaluates
 * which is described in {@code docs/semantics.md#conditional-requests}, and
 * {@linkplain com.robothy.s3.core.assertions.PreconditionAssertions} evaluates them.
 *
 * <p>The entity tag conditions are held as the value of the header, e.g. {@code "a", "b"} or {@code *}; the dates as
 * epoch milliseconds. A date that isn't a valid HTTP date is no precondition at all and must be left out, as RFC 9110
 * requires.
 */
@Builder
@Getter
@EqualsAndHashCode
public class ObjectPreconditions {

  /**
   * The value of an {@code If-Match} or {@code If-None-Match} header that matches the object whatever its
   * entity tag is, as long as the key holds one.
   */
  public static final String WILDCARD = "*";

  private static final ObjectPreconditions NONE = ObjectPreconditions.builder().build();

  /**
   * Value of the {@code If-Match} header; {@code null} if the request doesn't carry it.
   */
  private String ifMatch;

  /**
   * Value of the {@code If-None-Match} header; {@code null} if the request doesn't carry it.
   */
  private String ifNoneMatch;

  /**
   * The {@code If-Modified-Since} date in epoch milliseconds; {@code null} if the request doesn't carry it.
   */
  private Long ifModifiedSince;

  /**
   * The {@code If-Unmodified-Since} date in epoch milliseconds; {@code null} if the request doesn't carry it.
   */
  private Long ifUnmodifiedSince;

  /**
   * Value of the {@code x-amz-if-match-last-modified-time} header of {@code DeleteObject}, or of the
   * {@code LastModifiedTime} of an object of {@code DeleteObjects}, in epoch milliseconds: the object is only
   * deleted if it was last modified in that second. {@code null} if the request doesn't carry it.
   */
  private Long ifMatchLastModifiedTime;

  /**
   * Value of the {@code x-amz-if-match-size} header of {@code DeleteObject}, or of the {@code Size} of an object
   * of {@code DeleteObjects}: the object is only deleted if it has that many bytes. {@code null} if the request
   * doesn't carry it.
   */
  private Long ifMatchSize;

  /**
   * The preconditions of an unconditional request, which every object satisfies.
   *
   * @return preconditions that hold for every object.
   */
  public static ObjectPreconditions none() {
    return NONE;
  }

  /**
   * Whether the request carries no precondition, so that nothing has to be evaluated.
   *
   * @return {@code true} if the request is unconditional.
   */
  public boolean isEmpty() {
    return Objects.isNull(ifMatch) && Objects.isNull(ifNoneMatch)
        && Objects.isNull(ifModifiedSince) && Objects.isNull(ifUnmodifiedSince)
        && Objects.isNull(ifMatchLastModifiedTime) && Objects.isNull(ifMatchSize);
  }

}
