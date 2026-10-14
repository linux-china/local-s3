package com.robothy.s3.rest.handler;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The query parameters that name an operation of Amazon S3, as the HTTP bindings of the AWS SDKs declare them, e.g.
 * {@code ?acl}. See {@code docs/apis.md#not-routed-at-all}: a request that carries one of them, and that no route of its
 * method and path names it for, is answered {@code 501} rather than by the operation without parameters.
 *
 * <p>Parameters that only qualify an operation, e.g. {@code versionId} of {@code GetObject} or {@code partNumber}
 * of {@code HeadObject}, aren't subresources: the operation without parameters answers them.
 */
final class S3Subresources {

  static final Set<String> NAMES = Set.of(
      "abac", "accelerate", "acl", "analytics", "annotation", "attributes", "cors", "delete", "encryption",
      "intelligent-tiering", "inventory", "legal-hold", "lifecycle", "location", "logging", "metadataAnnotationTable",
      "metadataConfiguration", "metadataInventoryTable", "metadataJournalTable", "metadataTable", "metrics",
      "notification", "object-lock", "ownershipControls", "policy", "policyStatus", "publicAccessBlock",
      "renameObject", "replication", "requestPayment", "restore", "retention", "select", "session", "tagging",
      "torrent", "uploadId", "uploads", "versioning", "versions", "website");

  private S3Subresources() {
  }

  static boolean any(Map<String, List<String>> params) {
    return params != null && params.keySet().stream().anyMatch(NAMES::contains);
  }

}
