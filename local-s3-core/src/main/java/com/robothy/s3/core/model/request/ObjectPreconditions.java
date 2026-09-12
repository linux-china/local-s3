package com.robothy.s3.core.model.request;

import java.util.Objects;
import lombok.Builder;
import lombok.Getter;

/**
 * The preconditions of a conditional request: the {@code If-Match}, {@code If-None-Match},
 * {@code If-Modified-Since} and {@code If-Unmodified-Since} headers that
 * <a href="https://www.rfc-editor.org/rfc/rfc9110.html#section-13">RFC 9110, section 13</a> defines.
 * Amazon S3 evaluates them on a read, i.e. {@code GetObject} and {@code HeadObject}, and evaluates the two
 * entity tag conditions on {@code PutObject} as a
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-requests.html">conditional
 * write</a>: {@code If-None-Match: *} stores an object only if the key holds none, and {@code If-Match}
 * only if it holds the one that the given entity tag identifies, which makes a put a compare-and-swap.
 *
 * <p>The entity tag conditions are held as the value of the header, i.e. a comma separated list of entity
 * tags, e.g. {@code "a", "b"}, or {@code *}. {@linkplain com.robothy.s3.core.assertions.PreconditionAssertions}
 * evaluates them. The dates are held as epoch milliseconds; a date that isn't a valid HTTP date is not a
 * precondition at all and must be left out, like RFC 9110 requires.
 */
@Builder
@Getter
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
        && Objects.isNull(ifModifiedSince) && Objects.isNull(ifUnmodifiedSince);
  }

}
