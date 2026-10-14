package com.robothy.s3.rest.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.s3.rest.netty.OperationHandler;
import com.robothy.s3.rest.service.ServiceFactory;
import io.netty.handler.codec.http.HttpMethod;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import software.amazon.awssdk.core.SdkField;
import software.amazon.awssdk.core.SdkPojo;
import software.amazon.awssdk.core.protocol.MarshallLocation;
import software.amazon.awssdk.core.traits.RequiredTrait;
import software.amazon.awssdk.protocols.core.OperationInfo;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Request;

/**
 * Sends the request of every operation of the S3 client of the AWS SDK for Java, as its HTTP binding describes it, to
 * the router, and checks that the route of that very operation answers it: one that LocalS3 implements, or one that
 * answers {@code 501}, see {@code docs/apis.md#not-routed-at-all}.
 *
 * <p>It complements {@linkplain SupportedApiDocumentationTest}, which checks the routes against the documentation:
 * an operation that a new SDK adds, e.g. {@code PutObjectAnnotation}, would otherwise reach the operation that its
 * method and path name, here {@code PutObject}, and overwrite the object, without any list knowing of it.
 */
class LocalS3SdkOperationsTest {

  private static final String MARSHALLERS = "software.amazon.awssdk.services.s3.transform.";

  /**
   * Headers that the binding doesn't declare: {@code ListDirectoryBuckets} is a {@code GET /}, like
   * {@code ListBuckets}, which only the {@code s3express} signing name of the SDK tells apart.
   */
  private static final Map<String, Map<String, String>> SIGNING_HEADERS = Map.of(
      "ListDirectoryBuckets", Map.of("Authorization", "AWS4-HMAC-SHA256 "
          + "Credential=AKIAIOSFODNN7EXAMPLE/20260101/us-east-1/s3express/aws4_request, "
          + "SignedHeaders=host, Signature=0"));

  private static LocalS3Router router;

  @BeforeAll
  static void createRouter() {
    router = (LocalS3Router) LocalS3RouterFactory.create(
        Mockito.mock(ServiceFactory.class, Mockito.RETURNS_MOCKS), null, null);
  }

  /**
   * The bindings of the operations, by operation name. The sync client lacks the operations of event streams, e.g.
   * {@code SelectObjectContent}, which the async one has.
   */
  static Map<String, OperationInfo> bindings() throws ReflectiveOperationException {
    Map<String, OperationInfo> bindings = new TreeMap<>();
    for (Method method : Stream.concat(Arrays.stream(S3Client.class.getMethods()),
        Arrays.stream(S3AsyncClient.class.getMethods())).toList()) {
      for (Class<?> type : method.getParameterTypes()) {
        if (S3Request.class.isAssignableFrom(type) && !Modifier.isAbstract(type.getModifiers())) {
          Field binding = Class.forName(MARSHALLERS + type.getSimpleName() + "Marshaller")
              .getDeclaredField("SDK_OPERATION_BINDING");
          binding.setAccessible(true);
          String operation = type.getSimpleName().substring(0, type.getSimpleName().length() - "Request".length());
          bindings.put(operation, (OperationInfo) binding.get(null));
        }
      }
    }
    return bindings;
  }

  static Stream<Arguments> operations() throws ReflectiveOperationException {
    return bindings().entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
  }

  /**
   * Guards the reading of the bindings: if the SDK stops naming its marshallers as it does, nothing would be checked.
   */
  @Test
  void theOperationsOfTheSdkAreFound() throws ReflectiveOperationException {
    Map<String, OperationInfo> bindings = bindings();
    assertTrue(bindings.size() > 100, "Found only " + bindings.size() + " operations.");
    assertTrue(bindings.containsKey("SelectObjectContent"), bindings.keySet().toString());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("operations")
  void everyOperationOfTheSdkReachesItsOwnRoute(String operation, OperationInfo binding)
      throws ReflectiveOperationException {
    RouterHttpRequest request = request(operation, binding);
    OperationHandler handler = (OperationHandler) router.match(request);
    assertEquals(operation, handler.operation(), binding.httpMethod() + " " + request.getUri()
        + " reaches another route; implement the operation, or route it to a NotImplementedOperation.");
  }

  /**
   * The smallest request of an operation: its path and query, with {@code bucket} and {@code key} for the labels,
   * and the query parameters and headers that it requires.
   */
  private static RouterHttpRequest request(String operation, OperationInfo binding)
      throws ReflectiveOperationException {
    String requestUri = binding.requestUri() == null ? "" : binding.requestUri();
    int queryStart = requestUri.indexOf('?');
    String pathPart = queryStart < 0 ? requestUri : requestUri.substring(0, queryStart);
    String path;
    if (pathPart.isEmpty()) { // The bucket, which the SDK moves from the path to the host or the path itself.
      path = "/bucket";
    } else if (pathPart.startsWith("/{Key+}")) {
      path = "/bucket/key";
    } else {
      path = pathPart;
    }

    Map<String, List<String>> params = new HashMap<>();
    if (queryStart >= 0) {
      for (String parameter : requestUri.substring(queryStart + 1).split("&")) {
        int equals = parameter.indexOf('=');
        params.put(equals < 0 ? parameter : parameter.substring(0, equals),
            List.of(equals < 0 ? "" : parameter.substring(equals + 1)));
      }
    }
    Map<String, String> headers = new HashMap<>(SIGNING_HEADERS.getOrDefault(operation, Map.of()));
    headers.put("Host", "localhost");
    Class<?> requestType = Class.forName("software.amazon.awssdk.services.s3.model." + operation + "Request");
    SdkPojo empty = (SdkPojo) ((software.amazon.awssdk.utils.builder.SdkBuilder<?, ?>)
        requestType.getMethod("builder").invoke(null)).build();
    for (SdkField<?> field : empty.sdkFields()) {
      if (!field.containsTrait(RequiredTrait.class)) {
        continue;
      }
      if (field.location() == MarshallLocation.QUERY_PARAM) {
        params.put(field.locationName(), List.of("1"));
      } else if (field.location() == MarshallLocation.HEADER) {
        headers.put(field.locationName(), "1");
      }
    }

    String query = String.join("&", params.keySet().stream().sorted().toList());
    return RouterHttpRequest.builder()
        .method(HttpMethod.valueOf(binding.httpMethod().name()))
        .path(path)
        .uri(query.isEmpty() ? path : path + "?" + query)
        .params(params)
        .headers(headers)
        .build();
  }

}
