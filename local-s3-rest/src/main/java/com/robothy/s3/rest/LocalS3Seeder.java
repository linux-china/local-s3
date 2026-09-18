package com.robothy.s3.rest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Puts the initial buckets and objects of a service into it: the fixtures that a test or a local run starts with,
 * which {@linkplain LocalS3Builder#seeder(LocalS3Seeder) the builder} takes.
 *
 * <p>A seeder is applied when the service starts, after the {@linkplain LocalS3Builder#buckets(String...) default
 * buckets} are created and before the server accepts requests, and again after {@linkplain LocalS3#reset()}, so the
 * fixtures are there for every test method of a class that shares one service. It writes through the services of the
 * {@linkplain LocalS3#getS3Manager() manager}, not over HTTP, so it needs no client and no credentials.
 *
 * <pre>{@code
 * LocalS3 localS3 = LocalS3.builder()
 *     .seeder(fixtures -> fixtures.object("uploads", "hello.txt", "Hello!".getBytes(UTF_8)))
 *     .build();
 * }</pre>
 *
 * <p>An object replaces the one that the key already holds, e.g. the one loaded from the data path of a
 * {@code PERSISTENCE} service, so a run always starts from the fixtures.
 */
@FunctionalInterface
public interface LocalS3Seeder {

  /**
   * Put the initial buckets and objects into the service.
   *
   * @param fixtures writes the buckets and objects.
   * @throws IOException if the fixtures can't be read, which fails the start or the reset of the service.
   */
  void seed(Fixtures fixtures) throws IOException;

  /**
   * The buckets and objects that a {@linkplain LocalS3Seeder} writes into a service. The bucket of an object is
   * created if it doesn't exist yet, so a seeder doesn't have to create it first.
   */
  interface Fixtures {

    /**
     * Create a bucket, unless it exists already.
     *
     * @param bucketName the bucket name, which must follow the naming rules of Amazon S3.
     * @throws com.robothy.s3.core.exception.InvalidBucketNameException if the name is invalid.
     */
    void bucket(String bucketName);

    /**
     * Put an object with the content of a byte array.
     *
     * @param bucketName the bucket name; the bucket is created if it doesn't exist.
     * @param key the object key.
     * @param content the content of the object.
     */
    void object(String bucketName, String key, byte[] content);

    /**
     * Put an object with the content of a file. The content type is guessed from the file name.
     *
     * @param bucketName the bucket name; the bucket is created if it doesn't exist.
     * @param key the object key.
     * @param file the file that holds the content; it is read, not taken over.
     * @throws IOException if the file can't be read.
     */
    void object(String bucketName, String key, Path file) throws IOException;

    /**
     * Put an object with the content of a stream, which is read to the end and closed.
     *
     * @param bucketName the bucket name; the bucket is created if it doesn't exist.
     * @param key the object key.
     * @param content the content of the object; read to the end and closed, whether the put succeeds or not.
     * @param size the number of bytes that the stream holds.
     * @param contentType the content type to store, e.g. {@code text/plain}; {@code null} for none, which serves the
     *     object as {@code application/octet-stream}.
     * @throws IOException if the stream can't be read.
     */
    void object(String bucketName, String key, InputStream content, long size, @Nullable String contentType)
        throws IOException;

  }

}
