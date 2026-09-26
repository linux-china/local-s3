package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.s3.rest.service.ServiceFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Keeps the API lists of {@code docs/apis.md} in step with the routes that {@linkplain LocalS3RouterFactory} builds.
 *
 * <p>Users decide whether LocalS3 is enough for their tests by reading those lists, so a list that drifts
 * from the router misleads them: an operation that is missing from "Supported" looks unavailable, and one
 * that is missing from "Known unimplemented" looks available until a test fails with a 501. Both have
 * happened, e.g. {@code UploadPartCopy} was implemented without being listed.
 *
 * <p>The routes are read from a router that the factory builds, by the operations they are registered for. If
 * the router ever stops reporting its routes, this test fails on the counts below rather than passing silently.
 */
class SupportedApiDocumentationTest {

  private static final Path APIS = Path.of("../docs/apis.md");

  private static final Path README = Path.of("../README.md");

  private static final Path COMPARISON = Path.of("../docs/comparison.md");

  /**
   * The paragraph of {@code docs/comparison.md} that names the operations answering {@code 501}, up to the next blank
   * line.
   */
  private static final Pattern COMPARISON_NOT_IMPLEMENTED =
      Pattern.compile("\\*\\*Some operations answer `501`\\.\\*\\*(.*?)\\n\\n", Pattern.DOTALL);

  /**
   * An operation name in code, e.g. {@code `SelectObjectContent`}.
   */
  private static final Pattern OPERATION_IN_CODE = Pattern.compile("`([A-Z][A-Za-z]+)`");

  /**
   * The count of S3 operations that the features of the README claim, e.g. {@code **95+ S3 operations**}.
   */
  private static final Pattern README_OPERATION_COUNT = Pattern.compile("\\*\\*(\\d+)\\+ S3 operations\\*\\*");

  /**
   * Routes that aren't Amazon S3 operations, so the documentation describes them in prose instead of listing
   * them by name: the health check of the container, and the CORS preflight of a bucket and an object.
   */
  private static final Set<String> ROUTES_NOT_LISTED_BY_NAME =
      Set.of("HealthCheck", "HeadHealthCheck", "BucketCorsPreflight", "ObjectCorsPreflight");

  /**
   * An entry of a list of the documentation, e.g. {@code + PutObject}. An entry that carries a description
   * rather than only a name, e.g. the CORS preflight one, is not a name and is left out.
   */
  private static final Pattern DOCUMENTED_OPERATION = Pattern.compile("^\\+ (\\w+)$", Pattern.MULTILINE);

  private static Set<String> implementedS3Routes;

  private static Set<String> implementedVectorRoutes;

  private static Set<String> notImplementedRoutes;

  @BeforeAll
  static void readRoutes() {
    implementedS3Routes = new TreeSet<>();
    implementedVectorRoutes = new TreeSet<>();
    notImplementedRoutes = new TreeSet<>();

    LocalS3Router router = (LocalS3Router) LocalS3RouterFactory.create(
        Mockito.mock(ServiceFactory.class, Mockito.RETURNS_MOCKS), null, null);
    router.routesByOperation().forEach((operation, route) -> {
      HttpRequestHandler handler = route.getHandler();
      if (handler instanceof NotImplementedOperationController) {
        notImplementedRoutes.add(operation);
      } else if (handler.getClass().getPackageName().endsWith(".handler.s3vectors")) {
        implementedVectorRoutes.add(operation);
      } else if (!ROUTES_NOT_LISTED_BY_NAME.contains(operation)) {
        implementedS3Routes.add(operation);
      }
    });
  }

  /**
   * The operations that a {@code ## } section of the documentation lists by name, up to the next such section.
   */
  private static Set<String> documented(String sectionTitle) throws IOException {
    assertTrue(Files.exists(APIS), APIS.toAbsolutePath() + " doesn't exist.");
    String apis = Files.readString(APIS).replace("\r\n", "\n");

    String heading = "\n## " + sectionTitle + "\n";
    int start = apis.indexOf(heading);
    assertTrue(start >= 0, APIS + " has no '" + sectionTitle + "' section.");
    int end = apis.indexOf("\n## ", start + heading.length());
    end = end < 0 ? apis.length() : end;

    Set<String> operations = new LinkedHashSet<>();
    Matcher entries = DOCUMENTED_OPERATION.matcher(apis.substring(start + heading.length(), end));
    while (entries.find()) {
      operations.add(entries.group(1));
    }
    return operations;
  }

  /**
   * Guards the reading of the routes above: if the router stops reporting them, the sets are empty and every
   * other assertion would pass for the wrong reason.
   */
  @Test
  void theRoutesOfTheFactoryAreFound() {
    assertTrue(implementedS3Routes.size() > 40,
        "Found only " + implementedS3Routes.size() + " implemented S3 routes.");
    assertTrue(notImplementedRoutes.size() >= 3,
        "Found only " + notImplementedRoutes.size() + " unimplemented routes.");
    assertTrue(implementedVectorRoutes.size() > 10,
        "Found only " + implementedVectorRoutes.size() + " S3 Vectors routes.");
  }

  @Test
  void theDocumentationListsEveryImplementedOperation() throws IOException {
    assertEquals(implementedS3Routes, new TreeSet<>(documented("Supported Amazon S3 APIs")),
        "The 'Supported Amazon S3 APIs' list of docs/apis.md and the implemented routes differ.");
  }

  @Test
  void theDocumentationListsEveryOperationThatAnswersNotImplemented() throws IOException {
    assertEquals(notImplementedRoutes, new TreeSet<>(documented("Known unimplemented Amazon S3 APIs")),
        "The 'Known unimplemented Amazon S3 APIs' list of docs/apis.md and the routes that answer "
            + "501 NotImplemented differ.");
  }

  @Test
  void theDocumentationListsEveryImplementedVectorOperation() throws IOException {
    assertEquals(implementedVectorRoutes, new TreeSet<>(documented("Supported Amazon S3 Vectors APIs")),
        "The 'Supported Amazon S3 Vectors APIs' list of docs/apis.md and the implemented routes differ.");
  }

  /**
   * An operation is either supported or not; listing one in both sections would tell a user both things.
   */
  @Test
  void noOperationIsListedAsBothSupportedAndUnimplemented() throws IOException {
    Set<String> supported = new TreeSet<>(documented("Supported Amazon S3 APIs"));
    supported.addAll(documented("Supported Amazon S3 Vectors APIs"));
    supported.retainAll(documented("Known unimplemented Amazon S3 APIs"));
    assertEquals(Set.of(), supported, "docs/apis.md lists these operations as both supported and "
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

  /**
   * {@code docs/comparison.md} names the operations that answer {@code 501} for readers who compare LocalS3 with other
   * projects; it drifted from the router once, naming operations that had been implemented since.
   */
  @Test
  void theComparisonNamesTheOperationsThatAnswerNotImplemented() throws IOException {
    assertTrue(Files.exists(COMPARISON), COMPARISON.toAbsolutePath() + " doesn't exist.");
    Matcher paragraph = COMPARISON_NOT_IMPLEMENTED.matcher(Files.readString(COMPARISON).replace("\r\n", "\n"));
    assertTrue(paragraph.find(), COMPARISON + " has no 'Some operations answer `501`.' paragraph.");

    Set<String> named = new TreeSet<>();
    Matcher operations = OPERATION_IN_CODE.matcher(paragraph.group(1));
    while (operations.find()) {
      named.add(operations.group(1));
    }
    assertEquals(notImplementedRoutes, named,
        "The operations that docs/comparison.md says answer 501 and the routes that answer 501 differ.");
  }

  /**
   * The README claims a number of S3 operations, which must not exceed the operations implemented, nor fall far behind
   * them.
   */
  @Test
  void theReadmeClaimsTheNumberOfImplementedOperations() throws IOException {
    assertTrue(Files.exists(README), README.toAbsolutePath() + " doesn't exist.");
    Matcher claim = README_OPERATION_COUNT.matcher(Files.readString(README));
    assertTrue(claim.find(), README + " claims no '**N+ S3 operations**'.");
    int claimed = Integer.parseInt(claim.group(1));
    int implemented = documented("Supported Amazon S3 APIs").size();
    assertTrue(claimed <= implemented && implemented - claimed < 10,
        "README.md claims " + claimed + "+ S3 operations, and docs/apis.md lists " + implemented + ".");
  }

}
