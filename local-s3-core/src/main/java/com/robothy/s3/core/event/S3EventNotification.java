package com.robothy.s3.core.event;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Objects;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Maps {@linkplain S3Change}s to the
 * <a href="https://docs.aws.amazon.com/AmazonS3/latest/userguide/notification-content-structure.html">event
 * notifications</a> that Amazon S3 sends, version 2.1, so that a destination, e.g. a webhook, only has to send the JSON,
 * and a test may compare it to what a consumer of Amazon S3 expects.
 *
 * <p>The fields that LocalS3 doesn't know take fixed values: the principals are {@value #PRINCIPAL_ID}, the source IP
 * address is {@value #SOURCE_IP_ADDRESS}, the request ID is the sequencer of the change, and the region of an object is
 * {@value #DEFAULT_REGION}, as a change of an object doesn't carry the region of its bucket.
 */
public final class S3EventNotification {

  public static final String EVENT_VERSION = "2.1";

  public static final String PRINCIPAL_ID = "LocalS3";

  public static final String SOURCE_IP_ADDRESS = "127.0.0.1";

  public static final String DEFAULT_REGION = "us-east-1";

  private static final JsonMapper JSON_MAPPER = JsonMapper.builderWithJackson2Defaults().build();

  /**
   * {@code eventTime} has milliseconds, e.g. {@code 2026-09-25T08:30:00.123Z}, as Amazon S3 writes it.
   */
  private static final DateTimeFormatter EVENT_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
      .withZone(ZoneOffset.UTC);

  private S3EventNotification() {
  }

  /**
   * Whether Amazon S3 notifies of a change, i.e. whether it maps to a record.
   *
   * @param change the change.
   * @return {@code true} if it has an {@linkplain S3Change#s3EventName() event name}.
   */
  public static boolean isNotifiable(S3Change change) {
    return change.s3EventName() != null;
  }

  /**
   * The record of a change, an element of the {@code Records} of a notification:
   *
   * <pre>{@code
   * {
   *   "eventVersion": "2.1", "eventSource": "aws:s3", "awsRegion": "us-east-1",
   *   "eventTime": "2026-09-25T08:30:00.123Z", "eventName": "ObjectCreated:Put",
   *   "userIdentity": {"principalId": "LocalS3"},
   *   "requestParameters": {"sourceIPAddress": "127.0.0.1"},
   *   "responseElements": {"x-amz-request-id": "...", "x-amz-id-2": "..."},
   *   "s3": {
   *     "s3SchemaVersion": "1.0", "configurationId": "...",
   *     "bucket": {"name": "bucket", "ownerIdentity": {"principalId": "LocalS3"}, "arn": "arn:aws:s3:::bucket"},
   *     "object": {"key": "a+b.txt", "size": 5, "eTag": "...", "versionId": "...", "sequencer": "..."}
   *   }
   * }
   * }</pre>
   *
   * <p>As in Amazon S3, the {@code eventName} has no {@code s3:} prefix, the key is URL-encoded, with spaces as
   * {@code +}, and {@code size}, {@code eTag} and {@code versionId} are left out when the change has none, e.g.
   * {@code size} and {@code eTag} of a deletion.
   *
   * @param change the change.
   * @param configurationId the ID of the notification configuration the record is sent for; {@code null} leaves it out.
   * @return the record.
   * @throws IllegalStateException if Amazon S3 doesn't notify of such a change, see {@linkplain #isNotifiable}.
   */
  public static ObjectNode toRecord(S3Change change, String configurationId) {
    Objects.requireNonNull(change, "change");
    String eventName = change.s3EventName();
    if (eventName == null) {
      throw new IllegalStateException("Amazon S3 doesn't notify of " + change.type() + ".");
    }

    ObjectNode record = JSON_MAPPER.createObjectNode();
    record.put("eventVersion", EVENT_VERSION);
    record.put("eventSource", "aws:s3");
    record.put("awsRegion", Objects.requireNonNullElse(change.bucketRegion(), DEFAULT_REGION));
    record.put("eventTime", EVENT_TIME.format(change.eventTime()));
    record.put("eventName", eventName.substring("s3:".length()));
    record.putObject("userIdentity").put("principalId", PRINCIPAL_ID);
    record.putObject("requestParameters").put("sourceIPAddress", SOURCE_IP_ADDRESS);
    ObjectNode responseElements = record.putObject("responseElements");
    responseElements.put("x-amz-request-id", change.sequencer());
    responseElements.put("x-amz-id-2", change.sequencer());

    ObjectNode s3 = record.putObject("s3");
    s3.put("s3SchemaVersion", "1.0");
    if (configurationId != null) {
      s3.put("configurationId", configurationId);
    }
    ObjectNode bucket = s3.putObject("bucket");
    bucket.put("name", change.bucketName());
    bucket.putObject("ownerIdentity").put("principalId", PRINCIPAL_ID);
    bucket.put("arn", "arn:aws:s3:::" + change.bucketName());

    ObjectNode object = s3.putObject("object");
    object.put("key", encodeKey(change.key()));
    if (change.size() != null) {
      object.put("size", change.size());
    }
    if (change.etag() != null) {
      object.put("eTag", change.etag());
    }
    if (change.versionId() != null) {
      object.put("versionId", change.versionId());
    }
    object.put("sequencer", change.sequencer());
    return record;
  }

  /**
   * The notification of changes, {@code {"Records":[...]}}, with a {@linkplain #toRecord record} for each change that
   * Amazon S3 notifies of, in their order; the others are skipped.
   *
   * @param changes the changes.
   * @param configurationId the ID of the notification configuration; {@code null} leaves it out.
   * @return the notification.
   */
  public static ObjectNode toNotification(Collection<S3Change> changes, String configurationId) {
    ObjectNode notification = JSON_MAPPER.createObjectNode();
    ArrayNode records = notification.putArray("Records");
    for (S3Change change : changes) {
      if (isNotifiable(change)) {
        records.add(toRecord(change, configurationId));
      }
    }
    return notification;
  }

  /**
   * The JSON of the {@linkplain #toNotification notification} of changes.
   *
   * @param changes the changes.
   * @param configurationId the ID of the notification configuration; {@code null} leaves it out.
   * @return the JSON.
   */
  public static String toJson(Collection<S3Change> changes, String configurationId) {
    return JSON_MAPPER.writeValueAsString(toNotification(changes, configurationId));
  }

  /**
   * Encode a key as Amazon S3 does in a notification: URL-encoded, spaces as {@code +}, and {@code /} kept.
   */
  static String encodeKey(String key) {
    return URLEncoder.encode(key, StandardCharsets.UTF_8).replace("%2F", "/");
  }

}
