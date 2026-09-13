package com.robothy.s3.jupiter;

import com.robothy.s3.jupiter.extensions.LocalS3EndpointResolver;
import com.robothy.s3.jupiter.extensions.LocalS3Extension;
import com.robothy.s3.jupiter.extensions.LocalS3VectorsClientResolver;
import com.robothy.s3.jupiter.extensions.S3ClientResolver;
import com.robothy.s3.jupiter.supplier.DataPathSupplier;
import com.robothy.s3.rest.bootstrap.LocalS3Mode;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3vectors.S3VectorsClient;

/**
 * {@code @LocalS3} is a Junit5/Jupiter extension that runs a mocked AmazonS3 service
 * for annotated test classes or test methods. To hit the mocked service, you can add
 * the following parameter types in test methods.
 *
 * <ul>
 *   <li>{@linkplain S3Client}</li>
 *   <li>{@linkplain S3VectorsClient}</li>
 *   <li>{@linkplain LocalS3Endpoint}</li>
 * </ul>
 *
 * <p>Below example injects an {@code AmazonS3} instance to the parameter:
 *
 * <pre>{@code
 *  @LocalS3
 *  class AppTest {
 *    @Test
 *    void test(AmazonS3 s3) {
 *      s3.createBucket("my-bucket");
 *    }
 *  }
 * }</pre>
 *
 *  Or resolve a {@linkplain LocalS3Endpoint}.
 *
 * <pre>{@code
 *  class AppTest {
 *    @Test
 *    @LocalS3
 *    void test1(LocalS3Endpoint endpoint) {
 *      AmazonS3 client = AmazonS3ClientBuilder.standard()
 *        .enablePathStyleAccess()
 *        .withEndpointConfiguration(endpoint.toAmazonS3EndpointConfiguration())
 *        .build();
 *      assertDoesNotThrow(() -> client.createBucket("my-bucket"));
 *    }
 *  }
 * }</pre>
 *
 * <p>Signature verification is off unless {@linkplain #accessKey()} and {@linkplain #secretKey()} are set,
 * which makes the service reject a request that isn't signed with them and gives the injected clients those
 * credentials:
 *
 * <pre>{@code
 *  @LocalS3(accessKey = "an-access-key", secretKey = "a-secret-key")
 *  class AppTest {
 *    @Test
 *    void test(S3Client s3) {
 *      // Signed with the credentials above, so it is accepted.
 *      s3.createBucket(request -> request.bucket("my-bucket"));
 *    }
 *  }
 * }</pre>
 *
 * <p> If {@code @LocalS3} is on a test class, the Junit5 extension will create a shared
 * service for all test methods in the class and shut it down in the "after all" callback.
 * If {@code @LocalS3} is on a test method, the extension creates an exclusive service
 * for the method and shut down the service in the "after each" callback.
 *
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@ExtendWith(LocalS3Extension.class)
@ExtendWith(S3ClientResolver.class)
@ExtendWith(LocalS3VectorsClientResolver.class)
@ExtendWith(LocalS3EndpointResolver.class)
public @interface LocalS3 {

  /**
   * Set the TCP port that LocalS3 is listen to.
   *
   * @return the TCP port that LocalS3 is listen to.
   */
  int port() default -1;

  /**
   * Set LocalS3 running mode.
   *
   * @return the running mode.
   */
  LocalS3Mode mode() default LocalS3Mode.IN_MEMORY;

  /**
   * Set the data path of LocalS3 service. If LocalS3 runs in {@code PERSISTENCE} mode,
   * then all data is fetch from and stores in the specified path. If LocalS3 runs in {@code IN_MEMORY}
   * mode, then LocalS3 loads initial data from the specified path.
   *
   * @return the data path of LocalS3.
   */
  String dataPath() default "";

  /**
   * Set the data path supplier class for LocalS3 service. The class implements
   * the {@linkplain DataPathSupplier} interface and must have a no-args constructor.
   * This option is used fot the scenario that the data path is generated dynamically.
   *
   * @return a data path supplier class.
   */
  Class<? extends DataPathSupplier> dataPathSupplier() default DataPathSupplier.class;

  /**
   * Set if enable the initial data cache. This option only available when running LocalS3
   * in {@code IN_MEMORY} mode with initial data. If the cache enabled, LocalS3 will cache
   * the accessed data of the path; when start LocalS3 in other tests with the same {@code dataPath},
   * the cached data will be fetched.
   *
   * @return if initial data cache enabled.
   */
  boolean initialDataCacheEnabled() default true;

  /**
   * Set whether the names of new buckets must follow the naming rules of Amazon S3 general purpose
   * buckets, so that tests don't pass with bucket names that Amazon S3 rejects.
   *
   * @return if strict bucket name validation is enabled.
   */
  boolean strictBucketNames() default false;

  /**
   * Set whether every part of a multipart upload but the last one must be at least 5 MiB, the minimum part
   * size of Amazon S3, so that tests don't pass with a part layout that Amazon S3 rejects. The default is
   * {@code false}, which accepts parts of any size.
   *
   * @return if strict part size validation is enabled.
   */
  boolean strictPartSizes() default false;

  /**
   * Set whether the object of a completed multipart upload gets the entity tag that Amazon S3 gives an object
   * uploaded in parts: the MD5 digest of the concatenated MD5 digests of its parts, followed by {@code -} and
   * the number of parts, e.g. {@code 3858f62230ac3c915f300c664312c11f-9}. The {@code -<parts>} suffix is what
   * a client reads the part layout of an object off, so code that tells an object uploaded in parts from one
   * uploaded at once takes the same branch as against Amazon S3. The default is {@code true}; {@code false}
   * gives the object the MD5 digest of its whole content, which is what LocalS3 gave it before 2.5.
   *
   * @return if composite multipart entity tags are enabled.
   */
  boolean compositeMultipartEtags() default true;

  /**
   * Set base domains of virtual-hosted-style requests besides {@code localhost}, e.g. {@code s3.local}, so that
   * a request to the host {@code my-bucket.s3.local} accesses the bucket {@code my-bucket}.
   *
   * @return additional virtual-host domains.
   */
  String[] virtualHostDomains() default {};

  /**
   * Set the access key ID that the service accepts, which turns on AWS Signature Version 4 verification:
   * a request that isn't signed with these credentials is rejected the way Amazon S3 rejects it, so a test
   * can assert that its code signs its requests. The injected {@linkplain S3Client} and
   * {@linkplain S3VectorsClient} are given the same credentials, so the tests that don't assert anything
   * about signing keep reading as they did.
   *
   * <p>Must be set together with {@linkplain #secretKey()}. The default is the empty string, which leaves
   * verification off: every request is then accepted, signed or not.
   *
   * @return the access key ID that the service accepts; empty to verify no signature.
   */
  String accessKey() default "";

  /**
   * Set the secret access key that request signatures are verified with. Must be set together with
   * {@linkplain #accessKey()}.
   *
   * @return the secret access key that signatures are verified with; empty to verify no signature.
   */
  String secretKey() default "";
}
