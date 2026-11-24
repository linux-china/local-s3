package com.robothy.s3.core.model.internal;

import static org.junit.jupiter.api.Assertions.*;
import com.robothy.s3.core.exception.InvalidObjectKeyException;
import com.robothy.s3.core.model.BucketLifecycleConfiguration;
import com.robothy.s3.core.util.JsonUtils;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.datatypes.Grant;
import com.robothy.s3.datatypes.Grantee;
import com.robothy.s3.datatypes.Owner;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import org.junit.jupiter.api.Test;

class BucketMetadataTest {

  @Test
  void addObjectMetadata() {
    BucketMetadata bucketMetadata = new BucketMetadata();
    assertThrows(InvalidObjectKeyException.class, () -> bucketMetadata.putObjectMetadata("", new ObjectMetadata()));
    bucketMetadata.putObjectMetadata(" ", new ObjectMetadata());
    assertTrue(bucketMetadata.getObjectMetadata(" ").isPresent());

    ObjectMetadata objectMetadata = new ObjectMetadata();
    bucketMetadata.putObjectMetadata("abc.txt", objectMetadata);
    Optional<ObjectMetadata> optionalObjectMetadata = bucketMetadata.getObjectMetadata("abc.txt");
    assertTrue(optionalObjectMetadata.isPresent());
    assertTrue(bucketMetadata.getObjectMap().containsKey("abc.txt"));

    optionalObjectMetadata = bucketMetadata.getObjectMetadata("not exists");
    assertTrue(optionalObjectMetadata.isEmpty());
  }

  @Test
  void serialize() {

    BucketMetadata bucketMetadata = new BucketMetadata();
    bucketMetadata.setBucketName("bucket");
    bucketMetadata.setCreationDate(System.currentTimeMillis());
    bucketMetadata.setTagging(List.of(Map.of("A", "a"), Map.of("B", "b")));

    Grantee grantee = new Grantee();
    grantee.setType("AA");
    grantee.setUri("http://localhost");
    Grant grant = new Grant();
    grant.setGrantee(grantee);
    grant.setPermission("Read");
    AccessControlPolicy acl = AccessControlPolicy.builder()
        .owner(new Owner("Alice", "101"))
        .grants(List.of(grant))
        .build();
    bucketMetadata.setAcl(acl);
    bucketMetadata.setPolicy("Policy JSON text");

    VersionedObjectMetadata versionedObj1 = new VersionedObjectMetadata();
    versionedObj1.setFileId(111L);
    versionedObj1.setContentType("application/json");
    versionedObj1.setCreationDate(2L);
    ObjectMetadata obj1 = new ObjectMetadata("12", versionedObj1);
    ObjectMetadata obj2 = new ObjectMetadata("11", versionedObj1);
    bucketMetadata.putObjectMetadata("a.txt", obj1);
    bucketMetadata.putObjectMetadata("b.json", obj2);

    UploadMetadata uploadMetadata = new UploadMetadata();
    uploadMetadata.setCreateDate(1000);
    UploadPartMetadata part1 = new UploadPartMetadata();
    UploadPartMetadata part2 = new UploadPartMetadata();
    uploadMetadata.getParts().put(1, part1);
    uploadMetadata.getParts().put(2, part2);
    part2.setEtag("123");
    part2.setLastModified(102);
    part2.setFileId(1L);
    part2.setSize(10);
    NavigableMap<String, UploadMetadata> upload = new ConcurrentSkipListMap<>();
    upload.put("upload-id", uploadMetadata);
    bucketMetadata.getUploads().put("a.txt", upload);

    bucketMetadata.setReplication("Replication Configuration");
    bucketMetadata.setEncryption("Encryption");
    bucketMetadata.setNotification("<NotificationConfiguration/>");
    bucketMetadata.setLifecycle(new BucketLifecycleConfiguration("<LifecycleConfiguration/>", "varies_by_storage_class"));

    String json = JsonUtils.toJson(bucketMetadata);
    BucketMetadata deserialized = JsonUtils.fromJson(json, BucketMetadata.class);
    // The metadata carries no equals of its own, so the round trip is asserted on the document it
    // serializes to, which is what the file system mode stores anyway.
    assertEquals(json, JsonUtils.toJson(deserialized));
  }


  @Test
  void undoRestoresWhatTheRecordedChangeChanged() {
    BucketMetadata bucket = new BucketMetadata();
    bucket.setBucketName("bucket");
    bucket.setPolicy("before");
    bucket.getStoredConfigurations().put("website", "<before/>");
    bucket.putObjectMetadata("kept", new ObjectMetadata());
    UploadMetadata upload = new UploadMetadata();
    upload.getParts().put(1, new UploadPartMetadata());
    bucket.getUploads().put("uploaded", new ConcurrentSkipListMap<>(Map.of("u1", upload)));

    BucketMetadata.Undo undo = bucket.recordUndo();
    assertThrows(IllegalStateException.class, bucket::recordUndo);
    bucket.setPolicy("after");
    bucket.getStoredConfigurations().put("website", "<after/>");
    bucket.putObjectMetadata("added", new ObjectMetadata());
    bucket.removeObjectMetadata("kept");
    bucket.markUploadsChanged("uploaded");
    upload.getParts().put(2, new UploadPartMetadata());
    bucket.getUploads().get("uploaded").remove("u1");
    bucket.markUploadsChanged("new");
    bucket.getUploads().put("new", new ConcurrentSkipListMap<>());
    undo.undo();

    assertEquals("before", bucket.getPolicy().orElseThrow());
    assertEquals("<before/>", bucket.getStoredConfigurations().get("website"));
    assertTrue(bucket.getObjectMetadataRef("kept").isPresent());
    assertFalse(bucket.getObjectMetadataRef("added").isPresent());
    assertSame(upload, bucket.getUploads().get("uploaded").get("u1"));
    assertEquals(List.of(1), List.copyOf(upload.getParts().keySet()));
    assertFalse(bucket.getUploads().containsKey("new"));
    assertThrows(IllegalStateException.class, undo::forget, "A recording ends once.");

    BucketMetadata.Undo kept = bucket.recordUndo();
    bucket.setPolicy("after");
    kept.forget();
    assertEquals("after", bucket.getPolicy().orElseThrow());
  }

  @Test
  void onlyTheThreadThatRecordsAChangeIsRecorded() throws Exception {
    BucketMetadata bucket = new BucketMetadata();
    bucket.setBucketName("bucket");
    BucketMetadata.Undo undo = bucket.recordUndo();
    Thread other = new Thread(() -> bucket.putObjectMetadata("other", new ObjectMetadata()));
    other.start();
    other.join();
    undo.undo();
    assertTrue(bucket.getObjectMetadataRef("other").isPresent());
  }
}