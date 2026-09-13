package com.robothy.s3.core.model.internal;

import static org.junit.jupiter.api.Assertions.*;
import com.robothy.s3.core.util.IdUtils;
import com.robothy.s3.core.util.JsonUtils;
import org.junit.jupiter.api.Test;

class ObjectMetadataTest {

  @Test
  void getLatest() {
    String versionId = IdUtils.defaultGenerator().nextStrId();
    VersionedObjectMetadata versionedObjectMetadata = new VersionedObjectMetadata();
    ObjectMetadata objectMetadata = new ObjectMetadata(versionId, versionedObjectMetadata);
    assertSame(versionedObjectMetadata, objectMetadata.getLatest());
    assertEquals(versionId, objectMetadata.getLatestVersion());

    String latestVersionId = IdUtils.defaultGenerator().nextStrId();
    VersionedObjectMetadata latestVersionedObjectMetadata = new VersionedObjectMetadata();
    objectMetadata.putVersionedObjectMetadata(latestVersionId, latestVersionedObjectMetadata);
    assertSame(latestVersionedObjectMetadata, objectMetadata.getLatest());
    assertEquals(latestVersionId, objectMetadata.getLatestVersion());
  }

  /**
   * Version IDs are compared as numbers; as text, the shorter "999..." would sort above "1000...".
   */
  @Test
  void ordersVersionsWithDifferentLengthsByAge() {
    VersionedObjectMetadata older = new VersionedObjectMetadata();
    ObjectMetadata objectMetadata = new ObjectMetadata("999999999999999999", older);
    VersionedObjectMetadata newer = new VersionedObjectMetadata();
    objectMetadata.putVersionedObjectMetadata("1000000000000000000", newer);

    assertSame(newer, objectMetadata.getLatest());
    assertEquals("1000000000000000000", objectMetadata.getLatestVersion());
  }

  /**
   * IDs that an overflowed generator produced are negative, and older than every positive one.
   */
  @Test
  void ordersNegativeVersionsAsTheOldest() {
    VersionedObjectMetadata overflowed = new VersionedObjectMetadata();
    ObjectMetadata objectMetadata = new ObjectMetadata("-9223372036854775807", overflowed);
    VersionedObjectMetadata newer = new VersionedObjectMetadata();
    objectMetadata.putVersionedObjectMetadata("1000000000000000000", newer);

    assertSame(newer, objectMetadata.getLatest());
    assertEquals("-9223372036854775807", objectMetadata.getVersionedObjectMap().lastKey());
  }

  @Test
  void keepsTheVersionOrderAfterDeserialization() {
    ObjectMetadata objectMetadata = new ObjectMetadata("999999999999999999", new VersionedObjectMetadata());
    objectMetadata.putVersionedObjectMetadata("1000000000000000000", new VersionedObjectMetadata());

    ObjectMetadata deserialized = JsonUtils.fromJson(JsonUtils.toJson(objectMetadata), ObjectMetadata.class);
    assertEquals("1000000000000000000", deserialized.getLatestVersion());
  }

  @Test
  void serialize() {
    String versionId = IdUtils.defaultGenerator().nextStrId();
    VersionedObjectMetadata versionedObjectMetadata = new VersionedObjectMetadata();
    ObjectMetadata objectMetadata = new ObjectMetadata(versionId, versionedObjectMetadata);
    String json = JsonUtils.toJson(objectMetadata);
    ObjectMetadata serialized = JsonUtils.fromJson(json, ObjectMetadata.class);
    // Asserted on the serialized document; the metadata carries no equals of its own.
    assertEquals(json, JsonUtils.toJson(serialized));
  }

}