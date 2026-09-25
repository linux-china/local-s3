package com.robothy.s3.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class S3EventNotificationTest {

  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  @Test
  void mapsACreatedObjectToTheRecordOfAmazonS3() {
    S3Change change = new S3Change(S3ChangeType.OBJECT_CREATED, "CopyObject", "bucket", null, "dir/a b+c.txt", "v1",
        5L, "etag", false, null, Instant.parse("2026-09-25T08:30:00.123456Z"), "00000000000000AB");

    JsonNode json = MAPPER.readTree(change.toS3EventJson());

    assertEquals(1, json.get("Records").size());
    JsonNode record = json.get("Records").get(0);
    assertEquals("2.1", record.get("eventVersion").asString());
    assertEquals("aws:s3", record.get("eventSource").asString());
    assertEquals("us-east-1", record.get("awsRegion").asString());
    assertEquals("2026-09-25T08:30:00.123Z", record.get("eventTime").asString());
    assertEquals("ObjectCreated:Copy", record.get("eventName").asString());
    JsonNode s3 = record.get("s3");
    assertEquals("1.0", s3.get("s3SchemaVersion").asString());
    assertFalse(s3.has("configurationId"));
    assertEquals("bucket", s3.get("bucket").get("name").asString());
    assertEquals("arn:aws:s3:::bucket", s3.get("bucket").get("arn").asString());
    JsonNode object = s3.get("object");
    assertEquals("dir/a+b%2Bc.txt", object.get("key").asString());
    assertEquals(5L, object.get("size").asLong());
    assertEquals("etag", object.get("eTag").asString());
    assertEquals("v1", object.get("versionId").asString());
    assertEquals("00000000000000AB", object.get("sequencer").asString());
  }

  @Test
  void leavesOutWhatADeletionDoesNotHave() {
    S3Change change = S3Change.objectDeleted("DeleteObject", "bucket", "a.txt", null, false);

    ObjectNode record = S3EventNotification.toRecord(change, "config-1");

    assertEquals("ObjectRemoved:Delete", record.get("eventName").asString());
    assertEquals("config-1", record.get("s3").get("configurationId").asString());
    JsonNode object = record.get("s3").get("object");
    assertFalse(object.has("size"));
    assertFalse(object.has("eTag"));
    assertFalse(object.has("versionId"));
    assertEquals(change.sequencer(), object.get("sequencer").asString());
  }

  @Test
  void skipsTheChangesAmazonS3DoesNotNotifyOf() {
    S3Change created = S3Change.bucketCreated("CreateBucket", "bucket", "eu-west-1");

    assertThrows(IllegalStateException.class, created::toS3EventJson);
    S3Change put = S3Change.objectVersion(S3ChangeType.OBJECT_CREATED, "PutObject", "bucket", "a", null, 1, "e");
    JsonNode json = MAPPER.readTree(S3EventNotification.toJson(List.of(created, put), null));
    assertEquals(1, json.get("Records").size());
  }

  @Test
  void sequencersIncreaseAndAreNotCompared() {
    S3Change first = S3Change.objectDeleted("DeleteObject", "bucket", "a.txt", null, false);
    S3Change second = S3Change.objectDeleted("DeleteObject", "bucket", "a.txt", null, false);

    assertEquals(16, first.sequencer().length());
    assertTrue(first.sequencer().compareTo(second.sequencer()) < 0);
    assertEquals(first, second);
    assertEquals(first.hashCode(), second.hashCode());
    assertNotEquals(first, first.withOperation("DeleteObjects"));
    assertEquals(first.sequencer(), first.withOperation("DeleteObjects").sequencer());
  }

}
