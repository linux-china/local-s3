package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Keeps the API lists of the README in step with the routes that {@linkplain LocalS3RouterFactory} builds.
 *
 * <p>Users decide whether LocalS3 is enough for their tests by reading those lists, so a list that drifts
 * from the router misleads them: an operation that is missing from "Supported" looks unavailable, and one
 * that is missing from "Known unimplemented" looks available until a test fails with a 501. Both have
 * happened, e.g. {@code UploadPartCopy} was implemented without being listed.
 *
 * <p>The routes are read from the source of the factory rather than from a built router, because the
 * router keeps its routes to itself and the name of an operation only exists in the source. If the shape
 * of a route declaration ever changes, this test stops finding routes and fails on the count below rather
 * than passing silently.
 */
class SupportedApiDocumentationTest {

  private static final Path ROUTER_SOURCE =
      Path.of("src/main/java/com/robothy/s3/rest/handler/LocalS3RouterFactory.java");

  private static final Path README = Path.of("../README.md");

  /**
   * A route whose handler names this class answers {@code 501 NotImplemented}.
   */
  private static final String NOT_IMPLEMENTED_HANDLER = "NotImplementedOperationController";

  /**
   * Routes that aren't Amazon S3 operations, so the README describes them in prose instead of listing
   * them by name: the health check of the container, and the CORS preflight of a bucket and an object.
   */
  private static final Set<String> ROUTES_NOT_LISTED_BY_NAME =
      Set.of("HealthCheck", "HeadHealthCheck", "BucketCorsPreflight", "ObjectCorsPreflight");

  private static final Pattern ROUTE = Pattern.compile(
      "Route (\\w+) = Route\\.builder\\(\\)(.*?)\\.build\\(\\);", Pattern.DOTALL);

  /**
   * An entry of a list of the README, e.g. {@code + PutObject}. An entry that carries a description
   * rather than only a name, e.g. the CORS preflight one, is not a name and is left out.
   */
  private static final Pattern DOCUMENTED_OPERATION = Pattern.compile("^\\+ (\\w+)$", Pattern.MULTILINE);

  private static Set<String> implementedS3Routes;

  private static Set<String> implementedVectorRoutes;

  private static Set<String> notImplementedRoutes;

  @BeforeAll
  static void readRoutes() throws IOException {
    assertTrue(Files.exists(ROUTER_SOURCE), "Run this test with the module directory as the working "
        + "directory; " + ROUTER_SOURCE.toAbsolutePath() + " doesn't exist.");

    implementedS3Routes = new TreeSet<>();
    implementedVectorRoutes = new TreeSet<>();
    notImplementedRoutes = new TreeSet<>();

    Matcher routes = ROUTE.matcher(withoutCommentedLines(Files.readString(ROUTER_SOURCE)));
    while (routes.find()) {
      String name = routes.group(1);
      String declaration = routes.group(2);
      if (declaration.contains(NOT_IMPLEMENTED_HANDLER)) {
        notImplementedRoutes.add(name);
      } else if (declaration.contains(".handler.s3vectors.")) {
        implementedVectorRoutes.add(name);
      } else if (!ROUTES_NOT_LISTED_BY_NAME.contains(name)) {
        implementedS3Routes.add(name);
      }
    }
  }

  /**
   * A route that is commented out isn't a route, e.g. the {@code GetBucket} of the S3 Control API.
   */
  private static String withoutCommentedLines(String source) {
    return source.lines().filter(line -> !line.strip().startsWith("//")).collect(Collectors.joining("\n"));
  }

  /**
   * The operations that a {@code <details>} section of the README lists by name.
   */
  private static Set<String> documented(String sectionTitle) throws IOException {
    assertTrue(Files.exists(README), README.toAbsolutePath() + " doesn't exist.");
    String readme = Files.readString(README);

    String summary = "<summary><b>" + sectionTitle + "</b></summary>";
    int start = readme.indexOf(summary);
    assertTrue(start >= 0, "The README has no '" + sectionTitle + "' section.");
    int end = readme.indexOf("</details>", start);
    assertTrue(end > start, "The '" + sectionTitle + "' section of the README isn't closed.");

    Set<String> operations = new LinkedHashSet<>();
    Matcher entries = DOCUMENTED_OPERATION.matcher(readme.substring(start + summary.length(), end));
    while (entries.find()) {
      operations.add(entries.group(1));
    }
    return operations;
  }

  /**
   * Guards the reading of the source above: if the factory stops declaring routes the way this test
   * expects, the sets are empty and every other assertion would pass for the wrong reason.
   */
  @Test
  void theRoutesOfTheFactoryAreFound() {
    assertTrue(implementedS3Routes.size() > 40,
        "Found only " + implementedS3Routes.size() + " implemented S3 routes; the route declarations of "
            + "LocalS3RouterFactory probably no longer match this test.");
    assertTrue(notImplementedRoutes.size() > 20,
        "Found only " + notImplementedRoutes.size() + " unimplemented routes.");
    assertTrue(implementedVectorRoutes.size() > 10,
        "Found only " + implementedVectorRoutes.size() + " S3 Vectors routes.");
  }

  @Test
  void theReadmeListsEveryImplementedOperation() throws IOException {
    assertEquals(implementedS3Routes, new TreeSet<>(documented("Supported Amazon S3 APIs")),
        "The 'Supported Amazon S3 APIs' list of the README and the implemented routes differ.");
  }

  @Test
  void theReadmeListsEveryOperationThatAnswersNotImplemented() throws IOException {
    assertEquals(notImplementedRoutes, new TreeSet<>(documented("Known unimplemented Amazon S3 APIs")),
        "The 'Known unimplemented Amazon S3 APIs' list of the README and the routes that answer "
            + "501 NotImplemented differ.");
  }

  @Test
  void theReadmeListsEveryImplementedVectorOperation() throws IOException {
    assertEquals(implementedVectorRoutes, new TreeSet<>(documented("Supported Amazon S3 Vectors APIs")),
        "The 'Supported Amazon S3 Vectors APIs' list of the README and the implemented routes differ.");
  }

  /**
   * An operation is either supported or not; listing one in both sections would tell a user both things.
   */
  @Test
  void noOperationIsListedAsBothSupportedAndUnimplemented() throws IOException {
    Set<String> supported = new TreeSet<>(documented("Supported Amazon S3 APIs"));
    supported.addAll(documented("Supported Amazon S3 Vectors APIs"));
    supported.retainAll(documented("Known unimplemented Amazon S3 APIs"));
    assertEquals(Set.of(), supported, "The README lists these operations as both supported and "
        + "unimplemented.");
  }

  /**
   * The lists are in the order that a reader scans them in, so they have to be sorted; the grouped
   * sections of the unimplemented list are each sorted on their own.
   */
  @Test
  void theListOfUnimplementedOperationsIsSorted() throws IOException {
    List<String> documented = List.copyOf(documented("Known unimplemented Amazon S3 APIs"));
    assertEquals(documented.size(), new TreeSet<>(documented).size(), "The list repeats an operation.");
  }

}
