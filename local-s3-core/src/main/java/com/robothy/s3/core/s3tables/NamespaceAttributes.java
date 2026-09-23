package com.robothy.s3.core.s3tables;

/**
 * What the S3 Tables API knows about a namespace beyond what an Iceberg catalog does: its ID, when it was created and
 * by whom.
 *
 * <p>The namespace itself lives in the Iceberg catalog of the table bucket, see {@linkplain S3TablesService}, which is
 * what the Iceberg REST endpoint of the same table bucket serves. This record only adds the fields that
 * {@code GetNamespace} answers and an Iceberg catalog has no place for, so that the two views of a table bucket cannot
 * disagree about which namespaces exist.
 *
 * @param namespaceId the ID that the service assigned the namespace.
 * @param createdAt when it was created, in milliseconds since the epoch.
 * @param createdBy the account that created it.
 */
public record NamespaceAttributes(String namespaceId, long createdAt, String createdBy) {
}
