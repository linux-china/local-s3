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
   * A request that the catalog can't read, e.g. one whose schema is missing.
   *
   * @param message what is wrong with the request.
   * @return the exception.
   */
  public static IcebergCatalogException badRequest(String message) {
    return new IcebergCatalogException(400, "BadRequestException", message);
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
