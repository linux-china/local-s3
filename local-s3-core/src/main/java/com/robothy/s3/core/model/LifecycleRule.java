package com.robothy.s3.core.model;

import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * A rule of a lifecycle configuration, as far as LocalS3 applies it when it is
 * {@linkplain com.robothy.s3.core.service.LifecycleExecutionService asked to}: the objects it selects, and the actions
 * that expire objects, noncurrent versions and delete markers, or abort multipart uploads. Transitions are read, but
 * LocalS3 has only one storage class, so they change nothing.
 *
 * @param id the ID of the rule; {@code null} if it has none.
 * @param enabled whether the {@code Status} of the rule is {@code Enabled}.
 * @param filter the objects the rule applies to.
 * @param expirationDays the {@code Expiration/Days}; {@code null} for none.
 * @param expirationDate the {@code Expiration/Date} in epoch milliseconds; {@code null} for none.
 * @param expiredObjectDeleteMarker whether {@code Expiration/ExpiredObjectDeleteMarker} is {@code true}.
 * @param noncurrentDays the {@code NoncurrentVersionExpiration/NoncurrentDays}; {@code null} for none.
 * @param newerNoncurrentVersions the {@code NoncurrentVersionExpiration/NewerNoncurrentVersions}; {@code null} for none.
 * @param abortIncompleteMultipartUploadDays the {@code AbortIncompleteMultipartUpload/DaysAfterInitiation};
 *     {@code null} for none.
 */
public record LifecycleRule(String id, boolean enabled, Filter filter, Integer expirationDays, Long expirationDate,
                            boolean expiredObjectDeleteMarker, Integer noncurrentDays, Integer newerNoncurrentVersions,
                            Integer abortIncompleteMultipartUploadDays) {

  private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

  /**
   * The objects that a rule applies to: those whose key starts with the prefix, that have all the tags, and whose size
   * is within the bounds.
   *
   * @param prefix the key prefix; empty for every key.
   * @param tags the tags an object must have.
   * @param objectSizeGreaterThan the size an object must exceed; {@code null} for no lower bound.
   * @param objectSizeLessThan the size an object must stay below; {@code null} for no upper bound.
   */
  public record Filter(String prefix, Map<String, String> tags, Long objectSizeGreaterThan, Long objectSizeLessThan) {

    /**
     * Whether an object key is selected by the prefix of the filter.
     *
     * @param key the object key.
     * @return {@code true} if the key starts with the prefix.
     */
    public boolean matchesKey(String key) {
      return key.startsWith(prefix);
    }

    /**
     * Whether an object version is selected by the filter.
     *
     * @param key the object key.
     * @param tagging the tags of the version; {@code null} if it has none.
     * @param size the size of the version.
     * @return {@code true} if the version is selected.
     */
    public boolean matches(String key, String[][] tagging, long size) {
      if (!matchesKey(key)) {
        return false;
      }
      if (objectSizeGreaterThan != null && size <= objectSizeGreaterThan) {
        return false;
      }
      if (objectSizeLessThan != null && size >= objectSizeLessThan) {
        return false;
      }
      for (Map.Entry<String, String> tag : tags.entrySet()) {
        if (!hasTag(tagging, tag.getKey(), tag.getValue())) {
          return false;
        }
      }
      return true;
    }

    private static boolean hasTag(String[][] tagging, String key, String value) {
      if (tagging == null) {
        return false;
      }
      for (String[] tag : tagging) {
        if (tag.length == 2 && key.equals(tag[0]) && value.equals(tag[1])) {
          return true;
        }
      }
      return false;
    }
  }

  /**
   * When an action that applies a number of days after a time is due: the time plus the days, rounded up to the next
   * midnight UTC, which is how Amazon S3 computes it.
   *
   * @param since the epoch milliseconds the days count from, e.g. the creation of an object.
   * @param days the number of days.
   * @return the epoch milliseconds the action is due at.
   */
  public static long dueAt(long since, int days) {
    long at = since + days * DAY_MILLIS;
    return Math.ceilDiv(at, DAY_MILLIS) * DAY_MILLIS;
  }

  /**
   * Read the rules of a lifecycle configuration, which was validated when it was put.
   *
   * @param configuration the {@code LifecycleConfiguration} XML document.
   * @return the rules, in the order of the document.
   * @throws IllegalArgumentException if the document can't be read.
   */
  public static List<LifecycleRule> parse(String configuration) {
    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    try {
      XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(configuration));
      try {
        List<LifecycleRule> rules = new ArrayList<>();
        nextElement(reader);
        while (nextElement(reader) == XMLStreamConstants.START_ELEMENT) {
          rules.add(readRule(reader));
        }
        return rules;
      } finally {
        reader.close();
      }
    } catch (XMLStreamException | RuntimeException e) {
      throw new IllegalArgumentException("Failed to read the lifecycle configuration.", e);
    }
  }

  private static LifecycleRule readRule(XMLStreamReader reader) throws XMLStreamException {
    String id = null;
    boolean enabled = false;
    String legacyPrefix = null;
    Filter filter = null;
    Integer expirationDays = null;
    Long expirationDate = null;
    boolean expiredObjectDeleteMarker = false;
    Integer noncurrentDays = null;
    Integer newerNoncurrentVersions = null;
    Integer abortDays = null;
    while (nextElement(reader) == XMLStreamConstants.START_ELEMENT) {
      switch (reader.getLocalName()) {
        case "ID" -> id = reader.getElementText().trim();
        case "Status" -> enabled = "Enabled".equals(reader.getElementText().trim());
        case "Prefix" -> legacyPrefix = reader.getElementText();
        case "Filter" -> filter = readFilter(reader);
        case "Expiration" -> {
          while (nextElement(reader) == XMLStreamConstants.START_ELEMENT) {
            String text = reader.getElementText().trim();
            switch (reader.getLocalName()) {
              case "Days" -> expirationDays = Integer.valueOf(text);
              case "Date" -> expirationDate = parseDate(text);
              case "ExpiredObjectDeleteMarker" -> expiredObjectDeleteMarker = Boolean.parseBoolean(text);
              default -> {
                // Nothing else expires an object.
              }
            }
          }
        }
        case "NoncurrentVersionExpiration" -> {
          while (nextElement(reader) == XMLStreamConstants.START_ELEMENT) {
            String text = reader.getElementText().trim();
            switch (reader.getLocalName()) {
              case "NoncurrentDays" -> noncurrentDays = Integer.valueOf(text);
              case "NewerNoncurrentVersions" -> newerNoncurrentVersions = Integer.valueOf(text);
              default -> {
                // Nothing else expires a noncurrent version.
              }
            }
          }
        }
        case "AbortIncompleteMultipartUpload" -> {
          while (nextElement(reader) == XMLStreamConstants.START_ELEMENT) {
            String text = reader.getElementText().trim();
            if ("DaysAfterInitiation".equals(reader.getLocalName())) {
              abortDays = Integer.valueOf(text);
            }
          }
        }
        default -> skipElement(reader);
      }
    }
    if (filter == null) {
      filter = new Filter(legacyPrefix == null ? "" : legacyPrefix, Map.of(), null, null);
    }
    return new LifecycleRule(id, enabled, filter, expirationDays, expirationDate, expiredObjectDeleteMarker,
        noncurrentDays, newerNoncurrentVersions, abortDays);
  }

  private static Filter readFilter(XMLStreamReader reader) throws XMLStreamException {
    String[] prefix = {""};
    Map<String, String> tags = new LinkedHashMap<>();
    Long[] sizes = new Long[2];
    while (nextElement(reader) == XMLStreamConstants.START_ELEMENT) {
      if ("And".equals(reader.getLocalName())) {
        while (nextElement(reader) == XMLStreamConstants.START_ELEMENT) {
          readFilterElement(reader, prefix, tags, sizes);
        }
      } else {
        readFilterElement(reader, prefix, tags, sizes);
      }
    }
    return new Filter(prefix[0], Map.copyOf(tags), sizes[0], sizes[1]);
  }

  private static void readFilterElement(XMLStreamReader reader, String[] prefix, Map<String, String> tags,
                                        Long[] sizes) throws XMLStreamException {
    switch (reader.getLocalName()) {
      case "Prefix" -> prefix[0] = reader.getElementText();
      case "ObjectSizeGreaterThan" -> sizes[0] = Long.valueOf(reader.getElementText().trim());
      case "ObjectSizeLessThan" -> sizes[1] = Long.valueOf(reader.getElementText().trim());
      case "Tag" -> {
        String key = null;
        String value = "";
        while (nextElement(reader) == XMLStreamConstants.START_ELEMENT) {
          if ("Key".equals(reader.getLocalName())) {
            key = reader.getElementText();
          } else if ("Value".equals(reader.getLocalName())) {
            value = reader.getElementText();
          } else {
            skipElement(reader);
          }
        }
        if (key != null) {
          tags.put(key, value);
        }
      }
      default -> skipElement(reader);
    }
  }

  /**
   * Parse the {@code Date} of an expiration, an ISO 8601 date at midnight UTC, e.g. {@code 2030-01-01T00:00:00Z}, or a
   * date without a time.
   *
   * @return the date in epoch milliseconds.
   * @throws DateTimeParseException if the text is none of them.
   */
  public static long parseDate(String text) {
    try {
      return OffsetDateTime.parse(text).toInstant().toEpochMilli();
    } catch (DateTimeParseException e) {
      try {
        return Instant.parse(text).toEpochMilli();
      } catch (DateTimeParseException ignored) {
        return LocalDate.parse(text).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
      }
    }
  }

  private static int nextElement(XMLStreamReader reader) throws XMLStreamException {
    while (reader.hasNext()) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT || event == XMLStreamConstants.END_ELEMENT) {
        return event;
      }
    }
    return XMLStreamConstants.END_DOCUMENT;
  }

  private static void skipElement(XMLStreamReader reader) throws XMLStreamException {
    int depth = 1;
    while (depth > 0) {
      int event = reader.next();
      if (event == XMLStreamConstants.START_ELEMENT) {
        depth++;
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        depth--;
      }
    }
  }

}
