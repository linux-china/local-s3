package com.robothy.s3.spring.boot;

import com.robothy.s3.rest.LocalS3Seeder;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

/**
 * Puts the directory tree of a classpath location into the service, mapping {@code <bucket>/<key>} onto the buckets
 * and objects it starts with: {@code s3-fixtures/uploads/images/logo.png} becomes the object {@code images/logo.png}
 * of the bucket {@code uploads}, whose bucket is created if the tree is the only place that names it.
 *
 * <p>Configured with {@code local-s3.seed.classpath}, and applied when the service starts and after each reset, so
 * every test method of a class that shares one service finds the fixtures. The location is resolved with
 * {@code classpath*:}, so the fixtures of a jar on the classpath, e.g. a module of test fixtures shared by several
 * applications, are seeded as well, and a directory of the tree that holds no files creates no bucket.
 *
 * <p>A file directly in the location names no bucket, so it is skipped, which leaves room for a {@code README} or a
 * {@code .gitkeep} next to the buckets.
 */
public class ClasspathLocalS3Seeder implements LocalS3Seeder {

  private static final Logger log = LoggerFactory.getLogger(ClasspathLocalS3Seeder.class);

  private final String location;

  private final ResourcePatternResolver resolver;

  /**
   * Seed the tree of a classpath location, resolved with the default resolver of the class loader of this class.
   *
   * @param location the classpath location, e.g. {@code s3-fixtures} or {@code s3-fixtures/}.
   */
  public ClasspathLocalS3Seeder(String location) {
    this(location, new PathMatchingResourcePatternResolver());
  }

  /**
   * Seed the tree of a classpath location.
   *
   * @param location the classpath location, e.g. {@code s3-fixtures} or {@code s3-fixtures/}.
   * @param resolver resolves the location, e.g. the application context, whose class loader is the one of the
   *     application.
   */
  public ClasspathLocalS3Seeder(String location, ResourcePatternResolver resolver) {
    this.location = normalize(location);
    this.resolver = resolver;
  }

  /**
   * The classpath location that this seeder reads, without a leading slash and with a trailing one.
   *
   * @return the location, e.g. {@code s3-fixtures/}.
   */
  public String getLocation() {
    return location;
  }

  @Override
  public void seed(Fixtures fixtures) throws IOException {
    // The roots of the location, one per classpath entry that holds it, to take the keys relative to.
    List<String> roots = new ArrayList<>();
    for (Resource root : resolver.getResources(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + location)) {
      String url = root.getURL().toString();
      roots.add(url.endsWith("/") ? url : url + "/");
    }
    if (roots.isEmpty()) {
      log.warn("No classpath location {} to seed LocalS3 from; local-s3.seed.classpath names a directory of the "
          + "classpath, e.g. src/test/resources/{}.", location, location);
      return;
    }

    Resource[] resources = resolver.getResources(ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + location + "**");
    // Seeded in the order of their keys, so that the objects of a key that several classpath entries hold are put in
    // a stable order, and the log of a seeded tree reads like the tree.
    List<Resource> files = new ArrayList<>(Arrays.asList(resources));
    files.sort(Comparator.comparing(resource -> url(resource, "")));
    for (Resource resource : files) {
      // A directory of a classpath entry is resolved as well, and isn't readable.
      if (!resource.isReadable()) {
        continue;
      }
      String path = relativePath(resource, roots);
      if (path == null) {
        continue;
      }
      int slash = path.indexOf('/');
      if (slash <= 0 || slash == path.length() - 1) {
        log.warn("Skipped the fixture {}{} of LocalS3: it names no bucket, which the first segment of the path of a "
            + "fixture is, e.g. {}my-bucket/{}.", location, path, location, path);
        continue;
      }
      String bucketName = path.substring(0, slash);
      String key = path.substring(slash + 1);
      try (InputStream content = resource.getInputStream()) {
        fixtures.object(bucketName, key, content, resource.contentLength(),
            URLConnection.guessContentTypeFromName(key));
      }
      log.debug("Seeded s3://{}/{} from {}{}.", bucketName, key, location, path);
    }
  }

  /**
   * The path of a resource relative to the root of the location it was resolved from, decoded, e.g.
   * {@code uploads/images/logo.png}.
   *
   * @param resource the resolved resource.
   * @param roots the URLs of the roots of the location, each ending with a slash.
   * @return the relative path; {@code null} if the resource is under none of the roots, which shouldn't happen.
   */
  private String relativePath(Resource resource, List<String> roots) throws IOException {
    String url = resource.getURL().toString();
    for (String root : roots) {
      if (url.startsWith(root)) {
        return decode(url.substring(root.length()));
      }
    }
    log.warn("Skipped the fixture {} of LocalS3: it isn't under any of the resolved locations {}.", url, roots);
    return null;
  }

  /**
   * Decode the percent-escapes that a URL of a resource holds, e.g. {@code %20} of a file whose name has a space. A
   * plus sign is escaped first, so that it isn't decoded to a space: it is a literal character of a path, unlike in
   * the query of a URL, which is what {@linkplain URLDecoder} decodes.
   */
  private static String decode(String path) {
    return URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8);
  }

  private static String url(Resource resource, String fallback) {
    try {
      return resource.getURL().toString();
    } catch (IOException e) {
      return fallback;
    }
  }

  /**
   * The location without a leading slash and with a trailing one, which the {@code classpath*:} patterns are built
   * from, e.g. {@code s3-fixtures/} for {@code /s3-fixtures}.
   */
  private static String normalize(String location) {
    String normalized = location.strip();
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    if (normalized.isEmpty() || normalized.equals("/")) {
      // The root of the classpath would seed every resource of the application, including its classes.
      throw new IllegalArgumentException(
          "local-s3.seed.classpath must name a directory of the classpath, e.g. s3-fixtures, not the classpath root.");
    }
    if (!normalized.endsWith("/")) {
      normalized = normalized + "/";
    }
    return normalized;
  }

}
