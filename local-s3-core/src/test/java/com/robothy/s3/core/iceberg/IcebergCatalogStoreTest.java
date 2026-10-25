package com.robothy.s3.core.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.storage.LocalS3Store;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The compare-and-set removal a rename relies on, which is awkward to race through the service itself.
 */
class IcebergCatalogStoreTest {

  private static final IcebergIdentifier ORDERS = IcebergIdentifier.of(List.of("db"), "orders");

  @Test
  void a_table_that_was_committed_to_after_it_was_read_is_not_removed() {
    try (LocalS3Store localS3Store = LocalS3Store.inMemory()) {
      IcebergCatalogStore store = IcebergCatalogStore.create(localS3Store);
      IcebergTableRecord read = record("s3://warehouse/db/orders/metadata/00000.metadata.json");
      assertTrue(store.putTableIfAbsent(read));
      IcebergTableRecord committed = read.committed("s3://warehouse/db/orders/metadata/00001.metadata.json");
      assertTrue(store.replaceTable(read, committed));

      assertFalse(store.removeTable(read), "The commit that landed after the read must not be removed.");
      assertEquals(committed, store.getTable(ORDERS));

      assertTrue(store.removeTable(committed));
      assertNull(store.getTable(ORDERS));
    }
  }

  @Test
  void a_table_created_again_under_the_name_is_not_removed() {
    try (LocalS3Store localS3Store = LocalS3Store.inMemory()) {
      IcebergCatalogStore store = IcebergCatalogStore.create(localS3Store);
      IcebergTableRecord read = record("s3://warehouse/db/orders-1/metadata/00000.metadata.json");
      assertTrue(store.putTableIfAbsent(read));
      store.removeTable(ORDERS);
      IcebergTableRecord recreated = record("s3://warehouse/db/orders-2/metadata/00000.metadata.json");
      assertTrue(store.putTableIfAbsent(recreated));

      assertFalse(store.removeTable(read));
      assertEquals(recreated, store.getTable(ORDERS));
    }
  }

  private static IcebergTableRecord record(String metadataLocation) {
    return new IcebergTableRecord(ORDERS.namespace(), ORDERS.name(), false, metadataLocation, null, 0);
  }

}
