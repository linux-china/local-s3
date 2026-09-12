package com.robothy.s3.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.core.exception.ObjectNotExistException;
import com.robothy.s3.core.exception.PreconditionFailedException;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.model.request.ObjectPreconditions;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.service.manager.LocalS3Manager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Conditional writes, i.e. a {@code PutObject} carrying {@code If-None-Match} or {@code If-Match}. The
 * point of them is that they are atomic, which is what a commit protocol that builds a lock or an
 * optimistic update on them relies on: the condition is evaluated in the same locked section that the
 * object is stored in, so exactly one of the requests that race for a key can win.
 */
class ConditionalPutObjectServiceTest extends LocalS3ServiceTestBase {

  private static final String BUCKET = "conditional-bucket";

  private static final String KEY = "commit.json";

  private static final int RACERS = 16;

  private static PutObjectOptions put(String content, ObjectPreconditions preconditions) {
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    return PutObjectOptions.builder()
        .contentType("application/json")
        .size(bytes.length)
        .content(new ByteArrayInputStream(bytes))
        .preconditions(preconditions)
        .build();
  }

  private static String content(ObjectService objectService, String key) throws IOException {
    GetObjectAns ans = objectService.getObject(BUCKET, key, GetObjectOptions.builder().build());
    try (InputStream content = ans.getContent()) {
      return new String(content.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  private static String etag(ObjectService objectService, String key) {
    return objectService.headObject(BUCKET, key, GetObjectOptions.builder().build()).getEtag();
  }

  /**
   * Run the same call on {@linkplain #RACERS} threads that are released at once, and report how many of
   * them returned rather than failed a precondition.
   */
  private static int countWinners(Callable<?> attempt) throws Exception {
    CyclicBarrier start = new CyclicBarrier(RACERS);
    AtomicInteger winners = new AtomicInteger();
    ExecutorService executor = Executors.newFixedThreadPool(RACERS);
    try {
      List<Future<Object>> attempts = IntStream.range(0, RACERS).mapToObj(i -> executor.submit(() -> {
        start.await();
        try {
          attempt.call();
          winners.incrementAndGet();
        } catch (PreconditionFailedException e) {
          // Another thread got there first, which is the expected outcome for all but one of them.
        }
        return null;
      })).toList();

      for (Future<Object> finished : attempts) {
        finished.get(30, TimeUnit.SECONDS);
      }
    } finally {
      executor.shutdownNow();
    }
    return winners.get();
  }

  /**
   * {@code If-None-Match: *} is the "create if absent" that a distributed lock is built on: whatever the
   * interleaving, exactly one of the requests that race for a key stores its object and the others are
   * rejected, and the winner's content is the one that the key ends up holding.
   */
  @MethodSource("localS3Managers")
  @ParameterizedTest
  void onlyOneOfTheRacingCreatesOfAKeyWins(LocalS3Manager manager) throws Exception {
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);
    ObjectPreconditions ifAbsent = ObjectPreconditions.builder().ifNoneMatch("*").build();

    AtomicInteger nextAttempt = new AtomicInteger();
    assertEquals(1, countWinners(() -> objectService.putObject(BUCKET, KEY,
        put("attempt-" + nextAttempt.getAndIncrement(), ifAbsent))));

    // The key holds the object of the one attempt that won, whichever of them it was.
    assertTrue(content(objectService, KEY).startsWith("attempt-"));
  }

  /**
   * {@code If-Match} is the compare-and-swap that an optimistic update is built on: of the requests that
   * race to replace the same object, only the one that commits first matches it, and the others have to
   * read it again.
   */
  @MethodSource("localS3Managers")
  @ParameterizedTest
  void onlyOneOfTheRacingSwapsOfAnObjectWins(LocalS3Manager manager) throws Exception {
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);
    objectService.putObject(BUCKET, KEY, put("version-0", ObjectPreconditions.none()));
    ObjectPreconditions ifUnchanged = ObjectPreconditions.builder()
        .ifMatch(etag(objectService, KEY)).build();

    AtomicInteger nextAttempt = new AtomicInteger();
    assertEquals(1, countWinners(() -> objectService.putObject(BUCKET, KEY,
        put("version-1-by-" + nextAttempt.getAndIncrement(), ifUnchanged))));

    assertTrue(content(objectService, KEY).startsWith("version-1-by-"));
  }

  /**
   * The next swap of an object has to use the entity tag of the version that the winner stored; the one of
   * the version it replaced is stale and doesn't match any more.
   */
  @MethodSource("localS3Managers")
  @ParameterizedTest
  void aSwapMatchesTheCurrentObjectOnly(LocalS3Manager manager) throws Exception {
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);
    objectService.putObject(BUCKET, KEY, put("version-0", ObjectPreconditions.none()));
    String firstEtag = etag(objectService, KEY);

    objectService.putObject(BUCKET, KEY,
        put("version-1", ObjectPreconditions.builder().ifMatch(firstEtag).build()));
    String secondEtag = etag(objectService, KEY);

    assertThrows(PreconditionFailedException.class, () -> objectService.putObject(BUCKET, KEY,
        put("version-2", ObjectPreconditions.builder().ifMatch(firstEtag).build())));
    assertEquals("version-1", content(objectService, KEY));

    objectService.putObject(BUCKET, KEY,
        put("version-2", ObjectPreconditions.builder().ifMatch(secondEtag).build()));
    assertEquals("version-2", content(objectService, KEY));
  }

  /**
   * A swap of a key that holds no object is reported as a missing object, which is what Amazon S3 answers;
   * a create of one is rejected once the key holds one.
   */
  @MethodSource("localS3Managers")
  @ParameterizedTest
  void aConditionOnAnAbsentObjectIsReportedLikeAmazonS3Does(LocalS3Manager manager) {
    ObjectService objectService = manager.objectService();
    manager.bucketService().createBucket(BUCKET);

    assertThrows(ObjectNotExistException.class, () -> objectService.putObject(BUCKET, "absent.json",
        put("x", ObjectPreconditions.builder().ifMatch("\"whatever\"").build())));
    // The rejected put stored nothing, so the key still holds no object.
    assertThrows(ObjectNotExistException.class, () -> objectService.headObject(BUCKET, "absent.json",
        GetObjectOptions.builder().build()));
  }

  /**
   * The content of a rejected conditional put is stored before the bucket is locked, i.e. before the
   * condition is evaluated, and has to be discarded when the condition doesn't hold. Otherwise every
   * rejected attempt of a commit loop would leak an object into the storage.
   */
  @Test
  void aRejectedConditionalPutDiscardsTheContentItStored() throws Exception {
    // A file system storage, so that the objects it holds can be counted.
    Path dataPath = Files.createTempDirectory("conditional-put");
    dataPath.toFile().deleteOnExit();
    LocalS3Manager fileSystemManager = LocalS3Manager.createFileSystemS3Manager(dataPath);
    ObjectService objectService = fileSystemManager.objectService();
    fileSystemManager.bucketService().createBucket(BUCKET);
    objectService.putObject(BUCKET, KEY, put("version-0", ObjectPreconditions.none()));

    Path storage = dataPath.resolve(LocalS3Manager.STORAGE_DIRECTORY);
    long storedBefore = storedObjects(storage);

    for (int i = 0; i < 5; i++) {
      assertThrows(PreconditionFailedException.class, () -> objectService.putObject(BUCKET, KEY,
          put("rejected", ObjectPreconditions.builder().ifNoneMatch("*").build())));
    }

    assertEquals(storedBefore, storedObjects(storage));
    assertEquals("version-0", content(objectService, KEY));
  }

  private static long storedObjects(Path storage) throws IOException {
    try (var files = Files.list(storage)) {
      return files.count();
    }
  }

}
