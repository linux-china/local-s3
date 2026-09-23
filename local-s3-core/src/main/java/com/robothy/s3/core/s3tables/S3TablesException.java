package com.robothy.s3.core.s3tables;

import java.util.List;

/**
 * A request of the S3 Tables API that is refused, carrying what the answer needs: the HTTP status and the name of the
 * modelled exception.
 *
 * <p>The Amazon S3 Tables API is a {@code rest-json} service, and its clients tell one failure from another by the
 * {@code x-amzn-errortype} header rather than by a code in the body, so the name here is the one of the service model:
 * {@code NotFoundException}, {@code ConflictException}, {@code BadRequestException}, {@code ForbiddenException} or
 * {@code MethodNotAllowedException}. An SDK that reads one of those raises the matching exception class, which is what
 * a test written against the real service asserts on.
 */
public final class S3TablesException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final int status;

  private final String errorType;

  /**
   * Create an exception.
   *
   * @param status the HTTP status of the answer.
   * @param errorType the name of the modelled exception, e.g. {@code NotFoundException}.
   * @param message the message that the answer carries.
   */
  public S3TablesException(int status, String errorType, String message) {
    super(message);
    this.status = status;
    this.errorType = errorType;
  }

  /**
   * The HTTP status of the answer.
   *
   * @return the status.
   */
  public int status() {
    return status;
  }

  /**
   * The name of the modelled exception, which the {@code x-amzn-errortype} header carries.
   *
   * @return the name.
   */
  public String errorType() {
    return errorType;
  }

  /**
   * A request that names something that isn't there.
   *
   * @param message the message.
   * @return the exception.
   */
  public static S3TablesException notFound(String message) {
    return new S3TablesException(404, "NotFoundException", message);
  }

  /**
   * A request that is malformed, or that names something the service can't do.
   *
   * @param message the message.
   * @return the exception.
   */
  public static S3TablesException badRequest(String message) {
    return new S3TablesException(400, "BadRequestException", message);
  }

  /**
   * A request that clashes with what is already there, e.g. a name that is taken, or a write whose
   * {@code versionToken} is no longer the current one.
   *
   * @param message the message.
   * @return the exception.
   */
  public static S3TablesException conflict(String message) {
    return new S3TablesException(409, "ConflictException", message);
  }

  /**
   * A request that isn't allowed, e.g. one that deletes a table bucket that still holds tables.
   *
   * @param message the message.
   * @return the exception.
   */
  public static S3TablesException forbidden(String message) {
    return new S3TablesException(403, "ForbiddenException", message);
  }

  /**
   * A path of the API that is addressed with a method it doesn't answer.
   *
   * @param message the message.
   * @return the exception.
   */
  public static S3TablesException methodNotAllowed(String message) {
    return new S3TablesException(405, "MethodNotAllowedException", message);
  }

  /**
   * The failure of a request that leaves out a field the API requires.
   *
   * @param field the name of the field.
   * @return the exception.
   */
  public static S3TablesException missingField(String field) {
    return badRequest("The request is missing the required field '" + field + "'.");
  }

  /**
   * The failure of a request whose field holds a value the API doesn't take.
   *
   * @param field the name of the field.
   * @param value the value that was sent.
   * @param allowed the values that the field takes.
   * @return the exception.
   */
  public static S3TablesException invalidValue(String field, String value, List<String> allowed) {
    return badRequest("The field '" + field + "' takes " + String.join(" or ", allowed) + "; got: " + value + ".");
  }

}
