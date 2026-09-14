package com.robothy.s3.rest.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.rest.admin.LocalS3Admin;
import com.robothy.s3.rest.service.ServiceFactory;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.Map;
import java.util.Set;

/**
 * Answers the administration endpoints of a LocalS3 service with JSON, for local development and tests:
 * <ul>
 *   <li>{@code GET /_admin/stats}: the statistics of the data and of the requests, see
 *   {@linkplain com.robothy.s3.rest.admin.ServiceStatistics};</li>
 *   <li>{@code GET /_admin/requests?limit=n}: the last requests, the most recent first;</li>
 *   <li>{@code POST /_admin/reset}: replace the data of an {@code IN_MEMORY} service with the data it started with,
 *   which is much quicker than restarting it between tests; a {@code PERSISTENCE} service answers
 *   {@code 409 Conflict}.</li>
 * </ul>
 * Like the operations of Amazon S3, and unlike the health check, the endpoints must be signed if the service requires
 * credentials.
 */
class AdminController {

  static final String STATS_PATH = "/_admin/stats";

  static final String REQUESTS_PATH = "/_admin/requests";

  static final String RESET_PATH = "/_admin/reset";

  static final String STATS_OPERATION = "AdminStats";

  static final String REQUESTS_OPERATION = "AdminRecentRequests";

  static final String RESET_OPERATION = "AdminReset";

  static final Set<String> OPERATIONS = Set.of(STATS_OPERATION, REQUESTS_OPERATION, RESET_OPERATION);

  private final LocalS3Admin admin;

  private final ObjectMapper objectMapper;

  AdminController(ServiceFactory serviceFactory) {
    this.admin = serviceFactory.getInstance(LocalS3Admin.class);
    this.objectMapper = serviceFactory.getInstance(ObjectMapper.class);
  }

  void stats(HttpRequest request, HttpResponse response) throws Exception {
    json(response, HttpResponseStatus.OK, admin.statistics());
  }

  void requests(HttpRequest request, HttpResponse response) throws Exception {
    int limit = Integer.MAX_VALUE;
    String value = request.parameter("limit").orElse(null);
    if (value != null) {
      try {
        limit = Integer.parseInt(value);
      } catch (NumberFormatException e) {
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

  void reset(HttpRequest request, HttpResponse response) throws Exception {
    try {
      admin.reset();
    } catch (UnsupportedOperationException e) {
      json(response, HttpResponseStatus.CONFLICT, Map.of("error", e.getMessage()));
      return;
    }
    json(response, HttpResponseStatus.OK, Map.of("status", "RESET"));
  }

  private void json(HttpResponse response, HttpResponseStatus status, Object body) throws Exception {
    response.status(status)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_JSON)
        .write(objectMapper.writeValueAsString(body));
  }

}
