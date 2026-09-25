package com.robothy.s3.test;

import static com.robothy.s3.test.IcebergRemoteSigningIntegrationTest.count;
import static com.robothy.s3.test.IcebergRemoteSigningIntegrationTest.start;
import static com.robothy.s3.test.IcebergRemoteSigningIntegrationTest.writeAndReadBack;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.rest.LocalS3;
import java.util.HashMap;
import java.util.Map;
import org.apache.iceberg.CatalogProperties;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.rest.RESTCatalog;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * The credentials route of an Iceberg table: a loaded table names it in {@code client.refresh-credentials-endpoint},
 * and the {@code S3FileIO} of Iceberg takes its credentials from there, temporary ones that it refreshes before they
 * expire, rather than the keys of the service. The service verifies signatures here, so a table that is written and
 * read back is proof that the temporary credentials are accepted.
 */
@Tag("data-tools")
class IcebergCredentialRefreshIntegrationTest {

  @Test
  @Timeout(60)
  void a_client_reaches_the_files_of_a_table_with_the_credentials_of_its_credentials_route() throws Exception {
    try (LocalS3 localS3 = start(LocalS3.builder().port(-1).icebergCatalog(true)
        .credentials("refresh-key", "refresh-secret"));
         RESTCatalog catalog = new RESTCatalog()) {
      catalog.initialize("local", new HashMap<>(Map.of(
          CatalogProperties.URI, "http://127.0.0.1:" + localS3.getPort() + "/iceberg")));

      writeAndReadBack(catalog, TableIdentifier.of(Namespace.of("db"), "events"));

      assertTrue(count(localS3, "IcebergLoadCredentials") > 0, "The credentials are taken from the route of the table.");
    }
  }

}
