package com.robothy.s3.docker;

/**
 * The LocalS3 Docker image that the image tests run. The {@code dockerImageTest} and {@code nativeImageTest}
 * Gradle tasks build the image, and pass its tag in the {@code local-s3.image.tag} system property.
 */
final class ImageUnderTest {

  /**
   * JUnit tag of the tests that run a LocalS3 Docker image. The regular {@code test} task skips them.
   */
  static final String JUNIT_TAG = "docker-image";

  /**
   * Tag of the LocalS3 Docker image to test.
   */
  static final String TAG = System.getProperty("local-s3.image.tag", "latest");

  private ImageUnderTest() {
  }

}
