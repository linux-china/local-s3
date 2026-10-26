package com.robothy.s3.core.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.storage.LocalS3Store;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.h2.mvstore.MVMap;
import org.junit.jupiter.api.Test;

/**
 * The compare-and-set removal a rename relies on, and the namespace checks that must be atomic with the write they
 * guard, which are awkward to race through the service itself.
 */
class IcebergCatalogStoreTest {

  private static final IcebergIdentifier ORDERS = IcebergIdentifier.of(List.of("db"), "orders");

  @Test
  void a_table_that_was_committed_to_after_it_was_read_is_not_removed() {
    try (LocalS3Store localS3Store = LocalS3Store.inMemory()) {
      IcebergCatalogStore store = storeWithNamespace(localS3Store);
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
  void a_table_stored_in_another_field_order_can_still_be_committed_to() {
    try (LocalS3Store localS3Store = LocalS3Store.inMemory()) {
      IcebergCatalogStore store = IcebergCatalogStore.create(localS3Store, "test/namespaces", "test/tables");
      store.putNamespaceIfAbsent(new IcebergNamespaceRecord(ORDERS.namespace(), Map.of()));
      IcebergTableRecord created = record("s3://warehouse/db/orders/metadata/00000.metadata.json");
      assertTrue(store.putTableIfAbsent(created));
      MVMap<String, String> raw = localS3Store.store().openMap("test/tables");
      raw.put(created.key(), "{\"version\":0,\"previousMetadataLocation\":null,"
          + "\"metadataLocation\":\"s3://warehouse/db/orders/metadata/00000.metadata.json\",\"view\":false,"
          + "\"name\":\"orders\",\"namespace\":[\"db\"]}");

      IcebergTableRecord read = store.getTable(ORDERS);
      assertEquals(created, read);
      IcebergTableRecord committed = read.committed("s3://warehouse/db/orders/metadata/00001.metadata.json");
      assertTrue(store.replaceTable(read, committed));
      assertEquals(committed, store.getTable(ORDERS));
      assertFalse(store.replaceTable(read, read.committed("s3://warehouse/db/orders/metadata/00002.metadata.json")));
    }
  }

  @Test
  void a_table_created_again_under_the_name_is_not_removed() {
    try (LocalS3Store localS3Store = LocalS3Store.inMemory()) {
      IcebergCatalogStore store = storeWithNamespace(localS3Store);
      IcebergTableRecord read = record("s3://warehouse/db/orders-1/metadata/00000.metadata.json");
      assertTrue(store.putTableIfAbsent(read));
      store.removeTable(ORDERS);
      IcebergTableRecord recreated = record("s3://warehouse/db/orders-2/metadata/00000.metadata.json");
      assertTrue(store.putTableIfAbsent(recreated));

      assertFalse(store.removeTable(read));
      assertEquals(recreated, store.getTable(ORDERS));
    }
  }

  @Test
  void a_table_is_not_created_in_a_namespace_that_does_not_exist() {
    try (LocalS3Store localS3Store = LocalS3Store.inMemory()) {
      IcebergCatalogStore store = IcebergCatalogStore.create(localS3Store);
      IcebergCatalogException e = assertThrows(IcebergCatalogException.class,
          () -> store.putTableIfAbsent(record("s3://warehouse/db/orders/metadata/00000.metadata.json")));
      assertEquals(404, e.status());
      assertEquals("NoSuchNamespaceException", e.type());
      assertNull(store.getTable(ORDERS));
    }
  }

  @Test
  void a_namespace_that_holds_a_table_is_not_removed() {
    try (LocalS3Store localS3Store = LocalS3Store.inMemory()) {
      IcebergCatalogStore store = storeWithNamespace(localS3Store);
      assertTrue(store.putTableIfAbsent(record("s3://warehouse/db/orders/metadata/00000.metadata.json")));
      IcebergCatalogException e = assertThrows(IcebergCatalogException.class,
          () -> store.removeEmptyNamespace(ORDERS.namespace()));
      assertEquals("NamespaceNotEmptyException", e.type());

      store.removeTable(ORDERS);
      store.removeEmptyNamespace(ORDERS.namespace());
      assertNull(store.getNamespace("db"));
      assertThrows(IcebergCatalogException.class, () -> store.removeEmptyNamespace(ORDERS.namespace()));
    }
  }

  @Test
  void an_update_of_a_dropped_namespace_does_not_bring_it_back() {
    try (LocalS3Store localS3Store = LocalS3Store.inMemory()) {
      IcebergCatalogStore store = storeWithNamespace(localS3Store);
      IcebergNamespaceRecord read = store.getNamespace("db");
      store.removeEmptyNamespace(ORDERS.namespace());

      assertFalse(store.replaceNamespace(read, read.withProperties(Map.of("owner", "me"))));
      assertNull(store.getNamespace("db"));
    }
  }

  @Test
  void an_update_of_a_namespace_updated_after_it_was_read_is_refused() {
    try (LocalS3Store localS3Store = LocalS3Store.inMemory()) {
      IcebergCatalogStore store = storeWithNamespace(localS3Store);
      IcebergNamespaceRecord read = store.getNamespace("db");
      IcebergNamespaceRecord first = read.withProperties(Map.of("owner", "me"));
      assertTrue(store.replaceNamespace(read, first));

      assertFalse(store.replaceNamespace(read, read.withProperties(Map.of("comment", "lost"))),
          "An update built on the stale record would drop the owner the first one set.");
      assertEquals(first, store.getNamespace("db"));
    }
  }

  @Test
  void a_table_stored_in_another_field_order_can_still_be_removed() {
    try (LocalS3Store localS3Store = LocalS3Store.inMemory()) {
      IcebergCatalogStore store = IcebergCatalogStore.create(localS3Store, "test/namespaces", "test/tables");
      store.putNamespaceIfAbsent(new IcebergNamespaceRecord(ORDERS.namespace(), Map.of()));
      IcebergTableRecord created = record("s3://warehouse/db/orders/metadata/00000.metadata.json");
      assertTrue(store.putTableIfAbsent(created));
      MVMap<String, String> raw = localS3Store.store().openMap("test/tables");
      raw.put(created.key(), "{\"version\":0,\"previousMetadataLocation\":null,"
          + "\"metadataLocation\":\"s3://warehouse/db/orders/metadata/00000.metadata.json\",\"view\":false,"
          + "\"name\":\"orders\",\"namespace\":[\"db\"]}");

      assertTrue(store.removeTable(store.getTable(ORDERS)));
      assertNull(store.getTable(ORDERS));
    }
  }

  @Test
  void a_table_created_while_its_namespace_is_dropped_never_outlives_it() throws Exception {
    try (LocalS3Store localS3Store = LocalS3Store.inMemory();
         ExecutorService executor = Executors.newFixedThreadPool(2)) {
      IcebergCatalogStore store = IcebergCatalogStore.create(localS3Store);
      for (int round = 0; round < 500; round++) {
        store.putNamespaceIfAbsent(new IcebergNamespaceRecord(ORDERS.namespace(), Map.of()));
        CountDownLatch start = new CountDownLatch(1);
        Future<?> create = executor.submit(() -> {
          start.await();
          try {
            store.putTableIfAbsent(record("s3://warehouse/db/orders/metadata/00000.metadata.json"));
          } catch (IcebergCatalogException _) {
            // The drop won.
          }
          return null;
        });
        Future<?> drop = executor.submit(() -> {
          start.await();
          try {
            store.removeEmptyNamespace(ORDERS.namespace());
          } catch (IcebergCatalogException _) {
            // The create won.
          }
          return null;
        });
        start.countDown();
        create.get();
        drop.get();

        boolean namespaceExists = store.getNamespace("db") != null;
        boolean tableExists = store.getTable(ORDERS) != null;
        assertTrue(namespaceExists || !tableExists, "Round " + round + " left a table in a dropped namespace.");
        store.clear();
      }
    }
  }

  @Test
  void nothing_is_created_in_a_closed_catalog() {
    try (LocalS3Store localS3Store = LocalS3Store.inMemory()) {
      IcebergCatalogStore store = storeWithNamespace(localS3Store);
      assertFalse(store.closeIfEmpty(), "A catalog that holds a namespace is not closed.");

      store.removeEmptyNamespace(ORDERS.namespace());
      assertTrue(store.closeIfEmpty());
      assertThrows(IcebergCatalogException.class,
          () -> store.putNamespaceIfAbsent(new IcebergNamespaceRecord(ORDERS.namespace(), Map.of())));
      assertThrows(IcebergCatalogException.class,
          () -> store.putTableIfAbsent(new IcebergTableRecord(List.of(), "orders", false,
              "s3://warehouse/orders/metadata/00000.metadata.json", null, 0)));
      assertEquals(0, store.size());
    }
  }

  private static IcebergCatalogStore storeWithNamespace(LocalS3Store localS3Store) {
    IcebergCatalogStore store = IcebergCatalogStore.create(localS3Store);
    store.putNamespaceIfAbsent(new IcebergNamespaceRecord(ORDERS.namespace(), Map.of()));
    return store;
  }

  private static IcebergTableRecord record(String metadataLocation) {
    return new IcebergTableRecord(ORDERS.namespace(), ORDERS.name(), false, metadataLocation, null, 0);
  }

}
