package com.robothy.s3.core.service;

import com.robothy.s3.core.assertions.BucketAssertions;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.BucketLifecycleConfiguration;
import com.robothy.s3.core.model.LifecycleRule;
import com.robothy.s3.core.model.internal.BucketMetadata;
import com.robothy.s3.core.util.LifecycleRuleIds;
import java.io.StringReader;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Validates the structure of a configuration the way Amazon S3 does; applied only by
 * {@linkplain LifecycleExecutionService}. See {@code docs/semantics.md#lifecycle-configuration}.
 */
public interface BucketLifecycleService extends LocalS3MetadataApplicable {

  int MAX_RULES = 1000;

  int MAX_RULE_ID_LENGTH = 255;

  String DEFAULT_TRANSITION_MINIMUM_OBJECT_SIZE = "all_storage_classes_128K";

  Set<String> TRANSITION_MINIMUM_OBJECT_SIZES = Set.of("varies_by_storage_class", DEFAULT_TRANSITION_MINIMUM_OBJECT_SIZE);

  Set<String> RULE_ACTIONS = Set.of("Expiration", "Transition", "NoncurrentVersionExpiration",
      "NoncurrentVersionTransition", "AbortIncompleteMultipartUpload");

  default BucketLifecycleConfiguration putBucketLifecycleConfiguration(String bucketName, String configuration,
                                                                       String transitionDefaultMinimumObjectSize) {
    return changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      String minimumObjectSize = transitionDefaultMinimumObjectSize == null
          ? DEFAULT_TRANSITION_MINIMUM_OBJECT_SIZE : transitionDefaultMinimumObjectSize;
      if (!TRANSITION_MINIMUM_OBJECT_SIZES.contains(minimumObjectSize)) {
        throw new LocalS3InvalidArgumentException("x-amz-transition-default-minimum-object-size", minimumObjectSize,
            "Invalid value for x-amz-transition-default-minimum-object-size.");
      }
      String stored = validateLifecycleConfiguration(configuration)
          ? configuration : LifecycleRuleIds.withGeneratedIds(configuration);
      BucketLifecycleConfiguration lifecycle = new BucketLifecycleConfiguration(stored, minimumObjectSize);
      bucketMetadata.setLifecycle(lifecycle);
      return lifecycle;
    });
  }

  default Optional<BucketLifecycleConfiguration> getBucketLifecycleConfiguration(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      return BucketAssertions.assertBucketExists(localS3Metadata(), bucketName).getLifecycle();
    });
  }

  default void deleteBucketLifecycle(String bucketName) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setLifecycle(null);
    });
  }

  /** @return whether every rule has an {@code ID}, so that the missing ones are generated. */
  private static boolean validateLifecycleConfiguration(String configuration) {
    if (configuration == null || configuration.isBlank()) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }
    XMLInputFactory factory = XMLInputFactory.newDefaultFactory();
    // A configuration has no document type, and its entities must never be resolved.
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    try {
      XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(configuration));
      try {
        return validateLifecycleConfiguration(reader);
      } finally {
        reader.close();
      }
    } catch (XMLStreamException _) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }
  }

  private static boolean validateLifecycleConfiguration(XMLStreamReader reader) throws XMLStreamException {
    if (nextElement(reader) != XMLStreamConstants.START_ELEMENT
        || !"LifecycleConfiguration".equals(reader.getLocalName())) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }
    int rules = 0;
    boolean allHaveIds = true;
    Set<String> ids = new HashSet<>();
    while (nextElement(reader) == XMLStreamConstants.START_ELEMENT) {
      if (!"Rule".equals(reader.getLocalName())) {
        throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
      }
      if (++rules > MAX_RULES) {
        throw new LocalS3RequestException(S3ErrorCode.InvalidRequest,
            "The number of lifecycle rules must not exceed the allowed limit of " + MAX_RULES + " rules.");
      }
      allHaveIds &= validateRule(reader, ids);
    }
    if (rules == 0) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }
    // Nothing may follow the root element but whitespace, comments and processing instructions.
    while (reader.hasNext()) {
      if (reader.next() == XMLStreamConstants.START_ELEMENT) {
        throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
      }
    }
    return allHaveIds;
  }

  /** Leaves the reader at the end element of the rule. */
  private static boolean validateRule(XMLStreamReader reader, Set<String> ids) throws XMLStreamException {
    String id = null;
    String status = null;
    boolean hasAction = false;
    while (nextElement(reader) == XMLStreamConstants.START_ELEMENT) {
      String element = reader.getLocalName();
      if ("ID".equals(element)) {
        id = reader.getElementText();
      } else if ("Status".equals(element)) {
        status = reader.getElementText();
      } else if ("Expiration".equals(element) || "Transition".equals(element)) {
        hasAction = true;
        validateDateOfAction(reader);
      } else {
        hasAction |= RULE_ACTIONS.contains(element);
        skipElement(reader);
      }
    }
    if (!"Enabled".equals(status) && !"Disabled".equals(status)) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }
    if (!hasAction) {
      throw new LocalS3RequestException(S3ErrorCode.InvalidRequest,
          "At least one action needs to be specified in a rule.");
    }
    if (id != null) {
      if (id.length() > MAX_RULE_ID_LENGTH) {
        throw new LocalS3InvalidArgumentException("ID", id,
            "ID length should not exceed allowed limit of " + MAX_RULE_ID_LENGTH + ".");
      }
      if (!ids.add(id)) {
        throw new LocalS3InvalidArgumentException("ID", id,
            "Rule ID must be unique. Found same ID for more than one rule.");
      }
    }
    return id != null;
  }

  /** Leaves the reader at the end element of the date. */
  private static void validateDateOfAction(XMLStreamReader reader) throws XMLStreamException {
    while (nextElement(reader) == XMLStreamConstants.START_ELEMENT) {
      if (!"Date".equals(reader.getLocalName())) {
        skipElement(reader);
        continue;
      }
      String date = reader.getElementText().trim();
      long millis;
      try {
        millis = LifecycleRule.parseDate(date);
      } catch (DateTimeParseException _) {
        throw new LocalS3InvalidArgumentException("Date", date, "Invalid date format, must be in ISO 8601 format.");
      }
      if (millis % Duration.ofDays(1).toMillis() != 0) {
        throw new LocalS3InvalidArgumentException("Date", date, "'Date' must be at midnight GMT");
      }
    }
  }

  private static int nextElement(XMLStreamReader reader) throws XMLStreamException {
    while (reader.hasNext()) {
      int event = reader.next();
      switch (event) {
        case XMLStreamConstants.START_ELEMENT, XMLStreamConstants.END_ELEMENT -> {
          return event;
        }
        case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
          if (!reader.isWhiteSpace()) {
            throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
          }
        }
        case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE ->
            throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
        default -> {
          // Whitespace, comments and processing instructions carry nothing.
        }
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
      } else if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
        throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
      }
    }
  }

}
