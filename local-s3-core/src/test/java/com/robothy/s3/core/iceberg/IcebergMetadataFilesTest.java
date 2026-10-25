package com.robothy.s3.core.iceberg;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import com.robothy.s3.datatypes.response.VersionItem;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class IcebergMetadataFilesTest {

  /**
   * A purge on a versioned warehouse must delete the versions rather than hide them behind delete markers, and must
   * leave whatever lies outside the location of the table.
   */
  @ParameterizedTest
  @ValueSource(strings = {"unversioned", "enabled", "suspended"})
  void purge_deletes_every_version_under_the_location(String versioning) {
    LocalS3Manager s3Manager = LocalS3Manager.createInMemoryS3Manager();
    BucketService bucketService = s3Manager.bucketService();
    ObjectService objectService = s3Manager.objectService();
    bucketService.createBucket("warehouse");
    if (!"unversioned".equals(versioning)) {
      bucketService.setVersioningEnabled("warehouse", true);
    }
    IcebergMetadataFiles files = new IcebergMetadataFiles(bucketService, objectService);

    // More versions than one page of a purge lists, so that the purge has to follow the markers.
    for (int i = 0; i < 600; i++) {
      files.write("s3://warehouse/db/orders/data/" + i + ".json", "{}");
      files.write("s3://warehouse/db/orders/data/" + i + ".json", "{\"v\":2}");
    }
    objectService.deleteObject("warehouse", "db/orders/data/0.json");
    files.write("s3://warehouse/db/orders_archive/metadata.json", "{}");
    if ("suspended".equals(versioning)) {
      bucketService.setVersioningEnabled("warehouse", false);
      files.write("s3://warehouse/db/orders/data/1.json", "{\"v\":3}");
    }

    files.purge("s3://warehouse/db/orders");

    assertEquals(List.of(), versions(objectService, "db/orders/"));
    assertEquals(List.of("db/orders_archive/metadata.json"),
        versions(objectService, "db/").stream().map(VersionItem::key).toList());
  }

  private static List<VersionItem> versions(ObjectService objectService, String prefix) {
    return objectService.listObjectVersions("warehouse", null, null, 10_000, prefix, null).getVersions();
  }

}
