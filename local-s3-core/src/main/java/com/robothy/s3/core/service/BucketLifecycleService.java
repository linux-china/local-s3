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
 * Bucket lifecycle configuration service.
 *
 * <p>The configuration is <b>stored but never applied</b>: LocalS3 doesn't expire, transition or abort anything
 * because of it. Frameworks that set a lifecycle configuration when they start, e.g. to clean up a temporary
 * directory, thus work against LocalS3, while a test that waits for an object to expire doesn't.
 *
 * <p>A configuration is validated the way Amazon S3 validates the structure of one, so that a configuration that
 * Amazon S3 rejects isn't accepted: the document must be a {@code LifecycleConfiguration} of 1 to
 * {@value #MAX_RULES} {@code Rule}s, each with a {@code Status} of {@code Enabled} or {@code Disabled}, at least one
 * action, and a unique {@code ID} of at most {@value #MAX_RULE_ID_LENGTH} characters; the {@code Date} of an
 * {@code Expiration} or a {@code Transition} must be an ISO 8601 date at midnight UTC. The other contents of the
 * actions and filters aren't checked. The document is stored as it was put, and returned as is, except that a rule
 * put without an {@code ID} gets a generated one, like on Amazon S3, which tools such as Terraform rely on.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutBucketLifecycleConfiguration.html">PutBucketLifecycleConfiguration</a>
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetBucketLifecycleConfiguration.html">GetBucketLifecycleConfiguration</a>
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_DeleteBucketLifecycle.html">DeleteBucketLifecycle</a>
 */
public interface BucketLifecycleService extends LocalS3MetadataApplicable {

  /**
   * Max number of rules of a lifecycle configuration.
   */
  int MAX_RULES = 1000;

  /**
   * Max length of the {@code ID} of a rule.
   */
  int MAX_RULE_ID_LENGTH = 255;

  /**
   * The {@code x-amz-transition-default-minimum-object-size} of a configuration that is put without one, which is
   * what Amazon S3 applies to new configurations.
   */
  String DEFAULT_TRANSITION_MINIMUM_OBJECT_SIZE = "all_storage_classes_128K";

  /**
   * The values that {@code x-amz-transition-default-minimum-object-size} may have.
   */
  Set<String> TRANSITION_MINIMUM_OBJECT_SIZES = Set.of("varies_by_storage_class", DEFAULT_TRANSITION_MINIMUM_OBJECT_SIZE);

  /**
   * The elements of a rule that are actions; a rule needs at least one of them.
   */
  Set<String> RULE_ACTIONS = Set.of("Expiration", "Transition", "NoncurrentVersionExpiration",
      "NoncurrentVersionTransition", "AbortIncompleteMultipartUpload");

  /**
   * Put the lifecycle configuration of a bucket, replacing the existing one. The configuration is stored, but its
   * rules are never applied.
   *
   * @param bucketName the bucket name.
   * @param configuration the {@code LifecycleConfiguration} XML document.
   * @param transitionDefaultMinimumObjectSize the {@code x-amz-transition-default-minimum-object-size} of the
   *     configuration; {@code null} for {@value #DEFAULT_TRANSITION_MINIMUM_OBJECT_SIZE}.
   * @return the stored configuration.
   * @throws LocalS3RequestException if the configuration is malformed, or a rule has no action.
   * @throws LocalS3InvalidArgumentException if the IDs of the rules are too long or not unique, or the minimum object
   *     size is not a valid value.
   */
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

  /**
   * Get the lifecycle configuration of a bucket.
   *
   * @param bucketName the bucket name.
   * @return the lifecycle configuration; empty if the bucket has none.
   */
  default Optional<BucketLifecycleConfiguration> getBucketLifecycleConfiguration(String bucketName) {
    return withBucketReadLock(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      return BucketAssertions.assertBucketExists(localS3Metadata(), bucketName).getLifecycle();
    });
  }

  /**
   * Delete the lifecycle configuration of a bucket. Deleting the configuration of a bucket that has none succeeds.
   *
   * @param bucketName the bucket name.
   */
  default void deleteBucketLifecycle(String bucketName) {
    changeBucket(bucketName, () -> {
      BucketAssertions.assertBucketNameIsValid(bucketName);
      BucketMetadata bucketMetadata = BucketAssertions.assertBucketExists(localS3Metadata(), bucketName);
      bucketMetadata.setLifecycle(null);
    });
  }

  /**
   * Validate a configuration.
   *
   * @return whether every rule has an {@code ID}.
   */
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
    } catch (XMLStreamException e) {
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

  /**
   * Validate the rule whose start element the reader is at, and leave the reader at its end element.
   *
   * @return whether the rule has an {@code ID}.
   */
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

  /**
   * Validate the {@code Date} of the {@code Expiration} or {@code Transition} whose start element the reader is at,
   * and leave the reader at its end element. Amazon S3 takes an ISO 8601 date at midnight UTC only.
   */
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
      } catch (DateTimeParseException e) {
        throw new LocalS3InvalidArgumentException("Date", date, "Invalid date format, must be in ISO 8601 format.");
      }
      if (millis % Duration.ofDays(1).toMillis() != 0) {
        throw new LocalS3InvalidArgumentException("Date", date, "'Date' must be at midnight GMT");
      }
    }
  }

  /**
   * Move the reader to the next start or end element, skipping text, comments and processing instructions.
   *
   * @return {@linkplain XMLStreamConstants#START_ELEMENT}, {@linkplain XMLStreamConstants#END_ELEMENT}, or
   *     {@linkplain XMLStreamConstants#END_DOCUMENT} if the document ends.
   * @throws LocalS3RequestException if the document has a document type, or text outside of the leaf elements.
   */
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

  /**
   * Skip the element whose start element the reader is at, with everything it contains.
   */
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
