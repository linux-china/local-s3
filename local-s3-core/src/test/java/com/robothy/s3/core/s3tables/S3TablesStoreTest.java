package com.robothy.s3.core.s3tables;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.robothy.s3.core.storage.LocalS3Store;
import com.robothy.s3.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.h2.mvstore.MVMap;
import org.junit.jupiter.api.Test;

/**
 * The compare-and-set of the table attributes, against stored JSON that this version of LocalS3 did not write.
 */
class S3TablesStoreTest {

  private static final String BUCKET = "analytics";

  @Test
  void a_table_stored_in_another_field_order_can_still_be_committed_to() {
    try (LocalS3Store localS3Store = LocalS3Store.inMemory()) {
      S3TablesStore store = S3TablesStore.create(localS3Store);
      store.putTable(BUCKET, TableAttributes.created("id-1", "db", "orders", "111122223333",
          "s3://warehouse/orders", "s3://warehouse/orders/metadata/00000.metadata.json", Map.of()));
      MVMap<String, String> raw = localS3Store.store().openMap(S3TablesStore.TABLES_MAP);
      String key = raw.firstKey();
      String rewritten = reversed(raw.get(key));
      raw.put(key, rewritten);

      TableAttributes read = store.getTable(BUCKET, "db", "orders");
      assertNotEquals(JsonUtils.toJson(read), rewritten);
      TableAttributes committed = read.committed("s3://warehouse/orders/metadata/00001.metadata.json");
      assertTrue(store.replaceTable(BUCKET, read, committed));
      assertEquals(committed, store.getTable(BUCKET, "db", "orders"));
      assertEquals(committed, store.getTableById(BUCKET, "id-1"));

      assertFalse(store.replaceTable(BUCKET, read, read.committed(null)),
          "A commit built from the attributes before the last commit must lose.");
      assertEquals(committed, store.getTable(BUCKET, "db", "orders"));
    }
  }

  @Test
  void a_table_bucket_is_replaced_only_if_it_is_still_the_one_read() {
    try (LocalS3Store localS3Store = LocalS3Store.inMemory()) {
      S3TablesStore store = S3TablesStore.create(localS3Store);
      TableBucketRecord created = new TableBucketRecord(BUCKET, "id-1", "111122223333", 0L,
          TableBucketRecord.TYPE_CUSTOMER, "s3://analytics", Map.of());
      assertTrue(store.putBucketIfAbsent(created));

      TableBucketRecord read = store.getBucket(BUCKET);
      TableBucketRecord withPolicy = read.with(TableBucketRecord.POLICY, "{}");
      assertTrue(store.replaceBucket(read, withPolicy));
      assertFalse(store.replaceBucket(read, read.with(TableBucketRecord.ENCRYPTION, "{}")),
          "A change built from the table bucket before the last change must lose.");
      assertEquals(withPolicy, store.getBucket(BUCKET));

      assertTrue(store.removeBucket(BUCKET));
      assertFalse(store.replaceBucket(withPolicy, withPolicy.with(TableBucketRecord.ENCRYPTION, "{}")));
      assertEquals(null, store.getBucket(BUCKET));
    }
  }

  @SuppressWarnings("unchecked")
  private static String reversed(String json) {
    Map<String, Object> fields = JsonUtils.fromJson(json, LinkedHashMap.class);
    List<Map.Entry<String, Object>> entries = new ArrayList<>(fields.entrySet());
    Map<String, Object> reversed = new LinkedHashMap<>();
    for (int i = entries.size() - 1; i >= 0; i--) {
      reversed.put(entries.get(i).getKey(), entries.get(i).getValue());
    }
    return JsonUtils.toJson(reversed);
  }

}
