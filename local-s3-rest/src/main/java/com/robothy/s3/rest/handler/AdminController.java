package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.exception.BucketNotExistException;
import com.robothy.s3.core.model.answers.LifecycleActionAns;
import com.robothy.s3.rest.LocalS3Config;
import com.robothy.s3.rest.admin.LocalS3Admin;
import com.robothy.s3.rest.service.ServiceFactory;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.ObjectMapper;

/**
 * Answers the administration endpoints of a LocalS3 service ({@code /_admin/stats}, {@code requests}, {@code reset},
 * {@code lifecycle} and {@code snippets}) with JSON; see {@code docs/deployment.md#admin-endpoints}. Like the S3
 * operations, and unlike the health check, they must be signed if the service requires credentials.
 */
class AdminController {

  static final String STATS_PATH = "/_admin/stats";

  static final String REQUESTS_PATH = "/_admin/requests";

  static final String RESET_PATH = "/_admin/reset";

  static final String LIFECYCLE_PATH = "/_admin/lifecycle";

  static final String SNIPPETS_PATH = "/_admin/snippets";

  static final String STATS_OPERATION = "AdminStats";

  static final String REQUESTS_OPERATION = "AdminRecentRequests";

  static final String RESET_OPERATION = "AdminReset";

  static final String LIFECYCLE_OPERATION = "AdminApplyLifecycle";

  static final String SNIPPETS_OPERATION = "AdminConnectionSnippets";

  static final String SNIPPET_OPERATION = "AdminConnectionSnippet";

  static final Set<String> OPERATIONS = Set.of(STATS_OPERATION, REQUESTS_OPERATION, RESET_OPERATION,
      LIFECYCLE_OPERATION, SNIPPETS_OPERATION, SNIPPET_OPERATION);

  private final LocalS3Admin admin;

  private final ObjectMapper objectMapper;

  private final ConnectionSnippets snippets;

  AdminController(ServiceFactory serviceFactory) {
    this.admin = serviceFactory.getInstance(LocalS3Admin.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
    this.snippets = new ConnectionSnippets(serviceFactory.containsInstance(LocalS3Config.class)
        ? serviceFactory.getInstance(LocalS3Config.class) : null);
  }

  void stats(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    json(response, HttpResponseStatus.OK, admin.statistics());
  }

  void requests(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    int limit = Integer.MAX_VALUE;
    String value = request.parameter("limit").orElse(null);
    if (value != null) {
      try {
        limit = Integer.parseInt(value);
      } catch (NumberFormatException _) {
        limit = -1;
      }
      if (limit < 0) {
        json(response, HttpResponseStatus.BAD_REQUEST,
            Map.of("error", "The limit must be a non-negative integer: " + value));
        return;
      }
    }
    json(response, HttpResponseStatus.OK, Map.of("requests", admin.recentRequests(limit)));
  }

  void reset(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    try {
      admin.reset();
    } catch (UnsupportedOperationException e) {
      json(response, HttpResponseStatus.CONFLICT, Map.of("error", e.getMessage()));
      return;
    }
    json(response, HttpResponseStatus.OK, Map.of("status", "RESET"));
  }

  void lifecycle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String nowParameter = request.parameter("now").orElse(null);
    String daysParameter = request.parameter("days").orElse(null);
    Instant now;
    try {
      if (nowParameter != null && daysParameter != null) {
        throw new IllegalArgumentException("Give either now or days, not both.");
      }
      if (nowParameter != null) {
        now = Instant.parse(nowParameter);
      } else if (daysParameter != null) {
        now = Instant.now().plus(Duration.ofDays(Long.parseLong(daysParameter)));
      } else {
        now = Instant.now();
      }
    } catch (DateTimeException | ArithmeticException e) {
      json(response, HttpResponseStatus.BAD_REQUEST, Map.of("error",
          "now must be an ISO 8601 instant, e.g. 2030-01-01T00:00:00Z, and days a number of days."));
      return;
    } catch (IllegalArgumentException e) {
      json(response, HttpResponseStatus.BAD_REQUEST, Map.of("error", e instanceof NumberFormatException
          ? "days must be a number of days: " + daysParameter : e.getMessage()));
      return;
    }

    List<LifecycleActionAns> actions;
    try {
      actions = admin.applyLifecycle(request.parameter("bucket").orElse(null), now);
    } catch (BucketNotExistException _) {
      json(response, HttpResponseStatus.NOT_FOUND, Map.of("error", "The bucket doesn't exist."));
      return;
    }
    json(response, HttpResponseStatus.OK, Map.of("now", now.toString(), "actions", actions));
  }

  void snippets(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    json(response, HttpResponseStatus.OK, snippets.all(request, request.parameter("bucket").orElse(null),
        request.parameter("key").orElse(null)));
  }

  /**
   * One snippet, as text rather than JSON, so that it is pasted, or piped into its client, as it is.
   *
   * @param id the name of the snippet, e.g. {@code duckdb}.
   * @return the handler of {@code GET /_admin/snippets/<id>}.
   */
  RouterHttpRequestHandler snippet(String id) {
    return (request, response) -> {
      ConnectionSnippets.Snippet snippet = snippets.one(id, request, request.parameter("bucket").orElse(null),
          request.parameter("key").orElse(null)).orElseThrow();
      response.status(HttpResponseStatus.OK)
          .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), "text/plain; charset=utf-8")
          .write(snippet.content());
    };
  }

  private void json(RouterHttpResponse response, HttpResponseStatus status, Object body) throws Exception {
    response.status(status)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON)
        .write(objectMapper.writeValueAsString(body));
  }

}
