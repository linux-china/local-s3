package com.robothy.s3.core.asserionts;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.assertions.PreconditionAssertions;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.PreconditionFailedException;
import com.robothy.s3.core.model.internal.ObjectMetadata;
import com.robothy.s3.core.model.internal.VersionedObjectMetadata;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rules of a conditional request, as RFC 9110 section 13.2.2 defines them and Amazon S3 applies them.
 */
class PreconditionAssertionsTest {

  private static final String ETAG = "5d41402abc4b2a76b9719d911017c592";

  private static final long LAST_MODIFIED = 1_700_000_000_500L;

  private static boolean notModified(ObjectPreconditions preconditions) {
    return PreconditionAssertions.assertReadPreconditionsHold(preconditions, ETAG, LAST_MODIFIED);
  }

  @Test
  void anUnconditionalReadAnswersWithTheObject() {
    assertFalse(notModified(ObjectPreconditions.none()));
  }

  /**
   * A client that holds the object sends the entity tag it received back, which Amazon S3 quotes and
   * LocalS3 doesn't, so both spellings must match. A list of tags matches if it holds the one of the object,
   * whether the client sent it as one header or as a repeated one, which the decoder joins with a comma.
   */
  @ParameterizedTest
  @ValueSource(strings = {ETAG, "\"" + ETAG + "\"", " \"" + ETAG + "\" ", "W/\"" + ETAG + "\"",
      "\"other\", \"" + ETAG + "\"", "\"" + ETAG + "\",\"other\"", "*"})
  void ifNoneMatchThatHoldsTheEntityTagOfTheObjectReportsItUnmodified(String ifNoneMatch) {
    assertTrue(notModified(ObjectPreconditions.builder().ifNoneMatch(ifNoneMatch).build()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"\"other\"", "other", "\"other\", \"another\"", "\"\"", ","})
  void ifNoneMatchThatHoldsAnotherEntityTagAnswersWithTheObject(String ifNoneMatch) {
    assertFalse(notModified(ObjectPreconditions.builder().ifNoneMatch(ifNoneMatch).build()));
  }

  @ParameterizedTest
  @ValueSource(strings = {ETAG, "\"" + ETAG + "\"", "\"other\", \"" + ETAG + "\"", "*"})
  void ifMatchThatHoldsTheEntityTagOfTheObjectAnswersWithIt(String ifMatch) {
    assertFalse(notModified(ObjectPreconditions.builder().ifMatch(ifMatch).build()));
  }

  @Test
  void ifMatchThatHoldsAnotherEntityTagFails() {
    PreconditionFailedException thrown = assertThrows(PreconditionFailedException.class,
        () -> notModified(ObjectPreconditions.builder().ifMatch("\"other\"").build()));
    assertEquals(PreconditionAssertions.IF_MATCH, thrown.getCondition());
  }

  /**
   * A date is compared with the second that the object was stored in, because that is the precision of the
   * {@code Last-Modified} header that the client read the date off. An object stored at .500 milliseconds
   * counts as unmodified since the second it was stored in, and as modified since the one before.
   */
  @Test
  void ifModifiedSinceIsComparedBySecond() {
    assertTrue(notModified(ObjectPreconditions.builder().ifModifiedSince(LAST_MODIFIED - 500).build()));
    assertTrue(notModified(ObjectPreconditions.builder().ifModifiedSince(LAST_MODIFIED + 499).build()));
    assertFalse(notModified(ObjectPreconditions.builder().ifModifiedSince(LAST_MODIFIED - 1500).build()));
  }

  @Test
  void ifUnmodifiedSinceBeforeTheObjectWasStoredFails() {
    PreconditionFailedException thrown = assertThrows(PreconditionFailedException.class,
        () -> notModified(ObjectPreconditions.builder().ifUnmodifiedSince(LAST_MODIFIED - 1500).build()));
    assertEquals(PreconditionAssertions.IF_UNMODIFIED_SINCE, thrown.getCondition());
    // The second the object was stored in still counts as unmodified since.
    assertFalse(notModified(ObjectPreconditions.builder().ifUnmodifiedSince(LAST_MODIFIED - 500).build()));
  }

  /**
   * An entity tag condition takes precedence over the date condition it pairs with, so a date that
   * contradicts it changes nothing.
   */
  @Test
  void anEntityTagConditionMakesTheDateItPairsWithIrrelevant() {
    // If-None-Match holds the tag, so the read is not modified however old the date is.
    assertTrue(notModified(ObjectPreconditions.builder()
        .ifNoneMatch("\"" + ETAG + "\"").ifModifiedSince(LAST_MODIFIED - 5000).build()));
    // If-None-Match holds another tag, so the read answers with the object however new the date is.
    assertFalse(notModified(ObjectPreconditions.builder()
        .ifNoneMatch("\"other\"").ifModifiedSince(LAST_MODIFIED).build()));
    // If-Match holds the tag, so an If-Unmodified-Since that would fail on its own doesn't.
    assertFalse(notModified(ObjectPreconditions.builder()
        .ifMatch("\"" + ETAG + "\"").ifUnmodifiedSince(LAST_MODIFIED - 5000).build()));
  }

  /**
   * The two pairs are evaluated independently: a failed {@code If-Match} is reported even though the
   * {@code If-None-Match} of the same request would have reported the object unmodified.
   */
  @Test
  void aFailedIfMatchIsReportedBeforeIfNoneMatchIsEvaluated() {
    assertThrows(PreconditionFailedException.class, () -> notModified(ObjectPreconditions.builder()
        .ifMatch("\"other\"").ifNoneMatch("*").build()));
  }

  private static ObjectMetadata objectHolding(String etag, boolean deleted) {
    VersionedObjectMetadata version = new VersionedObjectMetadata();
    version.setEtag(etag);
    version.setDeleted(deleted);
    return new ObjectMetadata("1", version);
  }

  private static void assertWriteHolds(ObjectPreconditions preconditions, ObjectMetadata objectMetadata) {
    assertDoesNotThrow(() -> PreconditionAssertions.assertWritePreconditionsHold(preconditions, "k",
        objectMetadata));
  }

  private static <T extends Throwable> T assertWriteFails(Class<T> expected, ObjectPreconditions preconditions,
                                                          ObjectMetadata objectMetadata) {
    return assertThrows(expected, () -> PreconditionAssertions.assertWritePreconditionsHold(preconditions,
        "k", objectMetadata));
  }

  /**
   * {@code If-None-Match: *} is the condition of a put that creates an object, which Amazon S3 documents:
   * it holds while the key holds no object.
   */
  @Test
  void ifNoneMatchWildcardOnlyHoldsWhileTheKeyHoldsNoObject() {
    ObjectPreconditions ifAbsent = ObjectPreconditions.builder().ifNoneMatch("*").build();
    assertWriteHolds(ifAbsent, null);
    assertEquals(PreconditionAssertions.IF_NONE_MATCH,
        assertWriteFails(PreconditionFailedException.class, ifAbsent, objectHolding(ETAG, false)).getCondition());
    // A key whose latest version is a delete marker holds no object, like one that was never stored.
    assertWriteHolds(ifAbsent, objectHolding(null, true));
  }

  /**
   * {@code If-Match} makes a put a compare-and-swap, and reports a key that holds no object as missing
   * rather than as a failed condition, like Amazon S3 does.
   */
  @Test
  void ifMatchOnlyHoldsWhileTheKeyHoldsTheGivenObject() {
    ObjectPreconditions ifTag = ObjectPreconditions.builder().ifMatch("\"" + ETAG + "\"").build();
    assertWriteHolds(ifTag, objectHolding(ETAG, false));
    assertEquals(PreconditionAssertions.IF_MATCH,
        assertWriteFails(PreconditionFailedException.class, ifTag, objectHolding("other", false)).getCondition());
    assertWriteFails(ObjectNotExistException.class, ifTag, null);
    assertWriteFails(ObjectNotExistException.class, ifTag, objectHolding(null, true));
  }

  /**
   * A date condition of a write is ignored, like Amazon S3 does: {@code PutObject} only documents the two
   * entity tag conditions.
   */
  @Test
  void aDateConditionOfAWriteIsIgnored() {
    assertWriteHolds(ObjectPreconditions.builder().ifUnmodifiedSince(0L).ifModifiedSince(0L).build(),
        objectHolding(ETAG, false));
  }

  @Test
  void unconditionalPreconditionsAreEmpty() {
    assertTrue(ObjectPreconditions.none().isEmpty());
    assertFalse(ObjectPreconditions.builder().ifMatch("*").build().isEmpty());
    assertFalse(ObjectPreconditions.builder().ifNoneMatch("*").build().isEmpty());
    assertFalse(ObjectPreconditions.builder().ifModifiedSince(0L).build().isEmpty());
    assertFalse(ObjectPreconditions.builder().ifUnmodifiedSince(0L).build().isEmpty());
  }

}
