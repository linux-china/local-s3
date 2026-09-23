package com.robothy.s3.core.iceberg;

import java.util.List;

/**
 * A failure of the Iceberg REST catalog, which carries the HTTP status and the exception type that the
 * <a href="https://github.com/apache/iceberg/blob/main/open-api/rest-catalog-open-api.yaml">REST catalog API</a>
 * answers it with.
 *
 * <p>The clients of the catalog map the answer back to an exception of their own by those two: the
 * {@code ErrorHandlers} of the Iceberg Java client throws a {@code NoSuchTableException} for a {@code 404} whose type
 * isn't {@code NoSuchNamespaceException}, an {@code AlreadyExistsException} for a {@code 409} of a load or a create,
 * and a {@code CommitFailedException} for a {@code 409} of a commit, which is what makes a client retry the commit
 * against the table as it now is. So the status and the type are part of the protocol rather than decoration, and the
 * factories below are the ones the service raises.
 */
public class IcebergCatalogException extends RuntimeException {

  private final int status;

  private final String type;

  /**
   * Create an exception.
   *
   * @param status the HTTP status of the answer, e.g. {@code 404}.
   * @param type the exception type the answer names, e.g. {@code NoSuchTableException}.
   * @param message the message of the answer.
   */
  public IcebergCatalogException(int status, String type, String message) {
    super(message, null, false, false);
    this.status = status;
    this.type = type;
  }

  /**
   * The HTTP status that the failure is answered with.
   *
   * @return the status.
   */
  public int status() {
    return status;
  }

  /**
   * The exception type that the answer names, which a client maps back to an exception of its own.
   *
   * @return the type.
   */
  public String type() {
    return type;
  }

  /**
   * A request that the catalog can't read or can't apply, e.g. one whose schema is missing, or whose partition spec
   * names a field the schema doesn't hold.
   *
   * <p>The type is {@code IllegalArgumentException} because that is what a catalog built on the Iceberg library raises
   * for a request like this — every {@code Preconditions.checkArgument} of its metadata builders — and the Java client
   * reads the type: a {@code 400} that names it is re-raised as an {@link IllegalArgumentException} with this message,
   * and one that names anything else as a {@code BadRequestException} with "Malformed request" in front of it. A client
   * that tells the two apart, as the Iceberg test suites do, would otherwise see the wrong one.
   *
   * @param message what is wrong with the request.
   * @return the exception.
   */
  public static IcebergCatalogException badRequest(String message) {
    return new IcebergCatalogException(400, "IllegalArgumentException", message);
  }

  /**
   * An operation that the catalog of LocalS3 doesn't support.
   *
   * @param message the operation, and what to do instead.
   * @return the exception.
   */
  public static IcebergCatalogException unsupported(String message) {
    return new IcebergCatalogException(501, "UnsupportedOperationException", message);
  }

  public static IcebergCatalogException noSuchNamespace(List<String> namespace) {
    return new IcebergCatalogException(404, "NoSuchNamespaceException",
        "Namespace does not exist: " + IcebergIdentifier.toString(namespace));
  }

  public static IcebergCatalogException noSuchTable(IcebergIdentifier identifier) {
    return new IcebergCatalogException(404, "NoSuchTableException", "Table does not exist: " + identifier);
  }

  public static IcebergCatalogException noSuchView(IcebergIdentifier identifier) {
    return new IcebergCatalogException(404, "NoSuchViewException", "View does not exist: " + identifier);
  }

  public static IcebergCatalogException namespaceExists(List<String> namespace) {
    return new IcebergCatalogException(409, "AlreadyExistsException",
        "Namespace already exists: " + IcebergIdentifier.toString(namespace));
  }

  public static IcebergCatalogException tableExists(IcebergIdentifier identifier) {
    return new IcebergCatalogException(409, "AlreadyExistsException", "Table already exists: " + identifier);
  }

  public static IcebergCatalogException viewExists(IcebergIdentifier identifier) {
    return new IcebergCatalogException(409, "AlreadyExistsException", "View already exists: " + identifier);
  }

  /**
   * A table or a view that is created under a name a table already holds, which the REST API tells apart from a name
   * the same kind of thing holds: a client that asked for a view and finds a table has to be told which of the two it
   * found, and its message is the one the specification uses.
   *
   * @param identifier the name that is taken.
   * @return the exception.
   */
  public static IcebergCatalogException tableWithSameNameExists(IcebergIdentifier identifier) {
    return new IcebergCatalogException(409, "AlreadyExistsException",
        "Table with same name already exists: " + identifier);
  }

  /**
   * A table or a view that is created under a name a view already holds.
   *
   * @param identifier the name that is taken.
   * @return the exception.
   */
  public static IcebergCatalogException viewWithSameNameExists(IcebergIdentifier identifier) {
    return new IcebergCatalogException(409, "AlreadyExistsException",
        "View with same name already exists: " + identifier);
  }

  /**
   * A rename whose destination is taken, by a table or by a view.
   *
   * @param source the name being renamed.
   * @param destination the name that is taken.
   * @param destinationIsView whether what holds the destination is a view.
   * @return the exception.
   */
  public static IcebergCatalogException renameTargetExists(IcebergIdentifier source, IcebergIdentifier destination,
                                                           boolean destinationIsView) {
    return new IcebergCatalogException(409, "AlreadyExistsException", "Cannot rename " + source + " to " + destination
        + ". " + (destinationIsView ? "View" : "Table") + " already exists");
  }

  public static IcebergCatalogException namespaceNotEmpty(List<String> namespace) {
    return new IcebergCatalogException(409, "NamespaceNotEmptyException",
        "Namespace is not empty: " + IcebergIdentifier.toString(namespace));
  }

  /**
   * A commit that the table as it now is doesn't satisfy, which a client answers by refreshing the table and
   * applying its changes again.
   *
   * @param message the requirement that doesn't hold.
   * @return the exception.
   */
  public static IcebergCatalogException commitFailed(String message) {
    return new IcebergCatalogException(409, "CommitFailedException", message);
  }

}
