package com.robothy.s3.jupiter.extensions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.s3.jupiter.LocalS3;
import java.lang.annotation.Annotation;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

/**
 * The credentials that {@code @LocalS3} configures, which decide both whether the service verifies request
 * signatures and what the injected clients sign their requests with. The two must agree: a service that
 * verifies signatures would reject every request of a client that doesn't sign them.
 */
class SignatureCredentialsTest {

  @Test
  void anAnnotationWithoutCredentialsVerifiesNothing() {
    assertFalse(LocalS3Extension.verifiesSignatures(annotation("", "")));
    assertInstanceOf(AnonymousCredentialsProvider.class,
        AbstractLocalS3ParameterResolver.credentialsProvider(annotation("", "")));
  }

  @Test
  void anAnnotationWithBothCredentialsVerifiesSignatures() {
    LocalS3 s3Config = annotation("an-access-key", "a-secret-key");

    assertTrue(LocalS3Extension.verifiesSignatures(s3Config));
    AwsCredentialsProvider credentials = AbstractLocalS3ParameterResolver.credentialsProvider(s3Config);
    assertInstanceOf(StaticCredentialsProvider.class, credentials);
    assertEquals("an-access-key", credentials.resolveCredentials().accessKeyId());
    assertEquals("a-secret-key", credentials.resolveCredentials().secretAccessKey());
  }

  /**
   * One of the two on its own would leave verification off, so a test that means to turn it on would pass
   * while asserting nothing about signing. It is rejected instead.
   */
  @Test
  void oneCredentialOnItsOwnIsRejected() {
    IllegalArgumentException withoutSecret = assertThrows(IllegalArgumentException.class,
        () -> LocalS3Extension.verifiesSignatures(annotation("an-access-key", "")));
    assertTrue(withoutSecret.getMessage().contains("secretKey"), withoutSecret.getMessage());

    IllegalArgumentException withoutAccessKey = assertThrows(IllegalArgumentException.class,
        () -> LocalS3Extension.verifiesSignatures(annotation("", "a-secret-key")));
    assertTrue(withoutAccessKey.getMessage().contains("accessKey"), withoutAccessKey.getMessage());
  }

  /**
   * A client of a service that was never given credentials is anonymous, which is what the resolver falls
   * back to when it couldn't read the annotation at all.
   */
  @Test
  void noAnnotationLeavesTheClientAnonymous() {
    assertInstanceOf(AnonymousCredentialsProvider.class,
        AbstractLocalS3ParameterResolver.credentialsProvider(null));
  }

  /**
   * An {@linkplain LocalS3} carrying the given credentials and the defaults of every other attribute.
   */
  private static LocalS3 annotation(String accessKey, String secretKey) {
    @LocalS3
    class Defaults {
    }

    LocalS3 defaults = Defaults.class.getAnnotation(LocalS3.class);
    return new LocalS3() {

      @Override
      public Class<? extends Annotation> annotationType() {
        return LocalS3.class;
      }

      @Override
      public int port() {
        return defaults.port();
      }

      @Override
      public com.robothy.s3.rest.bootstrap.LocalS3Mode mode() {
        return defaults.mode();
      }

      @Override
      public String dataPath() {
        return defaults.dataPath();
      }

      @Override
      public Class<? extends com.robothy.s3.jupiter.supplier.DataPathSupplier> dataPathSupplier() {
        return defaults.dataPathSupplier();
      }

      @Override
      public boolean initialDataCacheEnabled() {
        return defaults.initialDataCacheEnabled();
      }

      @Override
      public boolean strictBucketNames() {
        return defaults.strictBucketNames();
      }

      @Override
      public boolean strictPartSizes() {
        return defaults.strictPartSizes();
      }

      @Override
      public boolean compositeMultipartEtags() {
        return defaults.compositeMultipartEtags();
      }

      @Override
      public String[] virtualHostDomains() {
        return defaults.virtualHostDomains();
      }

      @Override
      public String accessKey() {
        return accessKey;
      }

      @Override
      public String secretKey() {
        return secretKey;
      }
    };
  }

}
