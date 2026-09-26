package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.QueryStringDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A stateless <a href="https://docs.aws.amazon.com/STS/latest/APIReference/welcome.html">AWS STS</a> endpoint, like the
 * one of MinIO, so that the clients of a lakehouse, e.g. an Iceberg REST catalog such as Apache Polaris, Lakekeeper,
 * Gravitino or Unity Catalog, can vend scoped temporary credentials of LocalS3 with {@code AssumeRole}, and DuckDB,
 * PyIceberg or Spark can access LocalS3 with them. The credentials are issued and verified by
 * {@linkplain SessionCredentialIssuer}.
 *
 * <p>The actions are {@code AssumeRole}, {@code GetSessionToken} and {@code GetCallerIdentity}, requested with the
 * query protocol of STS: a {@code POST} to {@code /} whose form-urlencoded body carries the {@code Action} and its
 * parameters, signed for the {@code sts} service with the credentials of LocalS3, or with temporary credentials of
 * LocalS3 for role chaining. No S3 request looks like it: a browser upload posts {@code multipart/form-data}, so an STS
 * request is told apart by {@linkplain #isStsRequest} before the bucket of the request is.
 *
 * <p>LocalS3 has no IAM: the role and the session policies of a request are validated like STS validates them, but
 * don't limit what the credentials may do, which is everything that the credentials of LocalS3 may do.
 */
final class StsController implements HttpRequestHandler {

  static final String NAMESPACE = "https://sts.amazonaws.com/doc/2011-06-15/";

  /**
   * The account of LocalS3, which the ARNs of its identities name when a role ARN doesn't name one.
   */
  static final String ACCOUNT = "000000000000";

  /**
   * The operation that the statistics of the requests record an STS request of an unknown action as.
   */
  static final String UNKNOWN_ACTION_OPERATION = "StsUnknownAction";

  private static final Duration ASSUME_ROLE_DEFAULT_DURATION = Duration.ofHours(1);

  private static final Duration ASSUME_ROLE_MAX_DURATION = Duration.ofHours(12);

  /**
   * The longest session of a role that is assumed with temporary credentials.
   */
  private static final Duration ROLE_CHAINING_MAX_DURATION = Duration.ofHours(1);

  private static final Duration GET_SESSION_TOKEN_DEFAULT_DURATION = Duration.ofHours(12);

  private static final Duration GET_SESSION_TOKEN_MAX_DURATION = Duration.ofHours(36);

  private static final Pattern ROLE_SESSION_NAME = Pattern.compile("[\\w+=,.@-]{2,64}");

  private static final Pattern PRINTABLE = Pattern.compile("[\\u0020-\\u007E]+");

  private static final int MAX_BODY_LENGTH = 64 * 1024;

  private static final List<String> ACTIONS = List.of("AssumeRole", "GetSessionToken", "GetCallerIdentity");

  private final SessionCredentialIssuer issuer;

  StsController(SessionCredentialIssuer issuer) {
    this.issuer = Objects.requireNonNull(issuer);
  }

  /**
   * Whether a request is an STS request: a {@code POST} to {@code /} with a form-urlencoded body, whatever its host.
   */
  static boolean isStsRequest(HttpRequest request) {
    return HttpMethod.POST.equals(request.getMethod())
        && "/".equals(request.getPath())
        && request.header(HttpHeaderNames.CONTENT_TYPE.toString())
            .map(contentType -> contentType.trim().toLowerCase(Locale.ROOT)
                .startsWith("application/x-www-form-urlencoded"))
            .orElse(false);
  }

  /**
   * The operation that an STS request is recorded as: its action, e.g. {@code AssumeRole}.
   */
  static String operation(HttpRequest request) {
    try {
      String action = parameters(request).get("Action");
      return ACTIONS.contains(action) ? action : UNKNOWN_ACTION_OPERATION;
    } catch (StsException e) {
      return UNKNOWN_ACTION_OPERATION;
    }
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) {
    String requestId = ResponseUtils.nextRequestId();
    try {
      Map<String, String> parameters = parameters(request);
      String action = parameters.get("Action");
      if (action == null) {
        throw new StsException("MissingAction", 400, "Missing Action");
      }
      String result = switch (action) {
        case "AssumeRole" -> assumeRole(request, parameters);
        case "GetSessionToken" -> getSessionToken(request, parameters);
        case "GetCallerIdentity" -> getCallerIdentity(request);
        default -> throw new StsException("InvalidAction", 400,
            "Could not find operation " + action + " for version 2011-06-15");
      };
      writeXml(response, HttpResponseStatus.OK, requestId, "<" + action + "Response xmlns=\"" + NAMESPACE + "\">"
          + "<" + action + "Result>" + result + "</" + action + "Result>"
          + "<ResponseMetadata><RequestId>" + requestId + "</RequestId></ResponseMetadata>"
          + "</" + action + "Response>");
    } catch (StsException e) {
      writeError(response, requestId, e.code, e.status, e.getMessage());
    }
  }

  private String assumeRole(HttpRequest request, Map<String, String> parameters) {
    String roleArn = required(parameters, "RoleArn");
    if (roleArn.length() < 20 || roleArn.length() > 2048 || !PRINTABLE.matcher(roleArn).matches()) {
      throw validationError("Value '" + roleArn + "' at 'roleArn' failed to satisfy constraint: "
          + "Member must have length greater than or equal to 20");
    }
    String roleSessionName = required(parameters, "RoleSessionName");
    if (!ROLE_SESSION_NAME.matcher(roleSessionName).matches()) {
      throw validationError("Value '" + roleSessionName + "' at 'roleSessionName' failed to satisfy constraint: "
          + "Member must satisfy regular expression pattern: [\\w+=,.@-]*");
    }
    String policy = optional(parameters, "Policy").map(StsController::sessionPolicy).orElse(null);

    Caller caller = caller(request);
    Duration duration = duration(parameters, ASSUME_ROLE_DEFAULT_DURATION, ASSUME_ROLE_MAX_DURATION);
    if (caller.temporary() && duration.compareTo(ROLE_CHAINING_MAX_DURATION) > 0) {
      throw validationError("The requested DurationSeconds exceeds the 1 hour session limit for roles assumed by "
          + "role chaining.");
    }

    Role role = Role.parse(roleArn);
    String assumedRoleArn = "arn:" + role.partition() + ":sts::" + role.account() + ":assumed-role/" + role.name()
        + "/" + roleSessionName;
    String assumedRoleId = SessionCredentialIssuer.uniqueId("AROA", roleArn) + ":" + roleSessionName;
    SessionCredentialIssuer.SessionCredentials credentials =
        issuer.issue(duration, assumedRoleArn, assumedRoleId, policy);
    return credentialsXml(credentials)
        + "<AssumedRoleUser><AssumedRoleId>" + escape(assumedRoleId) + "</AssumedRoleId>"
        + "<Arn>" + escape(assumedRoleArn) + "</Arn></AssumedRoleUser>"
        + optional(parameters, "SourceIdentity").map(id -> "<SourceIdentity>" + escape(id) + "</SourceIdentity>")
            .orElse("");
  }

  private String getSessionToken(HttpRequest request, Map<String, String> parameters) {
    Caller caller = caller(request);
    if (caller.temporary()) {
      throw new StsException("AccessDenied", 403, "Cannot call GetSessionToken with session credentials");
    }
    Duration duration = duration(parameters, GET_SESSION_TOKEN_DEFAULT_DURATION, GET_SESSION_TOKEN_MAX_DURATION);
    return credentialsXml(issuer.issue(duration, caller.arn(), caller.userId()));
  }

  private String getCallerIdentity(HttpRequest request) {
    Caller caller = caller(request);
    return "<Arn>" + escape(caller.arn()) + "</Arn>"
        + "<UserId>" + escape(caller.userId()) + "</UserId>"
        + "<Account>" + escape(caller.account()) + "</Account>";
  }

  /**
   * The identity that a request is signed as: the session of its temporary credentials, or else the root of the
   * account of LocalS3. The router verified the signature of the request already, if LocalS3 verifies signatures.
   */
  private Caller caller(HttpRequest request) {
    String sessionToken = request.header(AmzHeaderNames.X_AMZ_SECURITY_TOKEN)
        .or(() -> Optional.ofNullable(request.getParams().get("X-Amz-Security-Token"))
            .flatMap(values -> values.stream().findFirst()))
        .orElse(null);
    SessionCredentialIssuer.Session session = issuer.decode(sessionToken);
    if (session == null) {
      return new Caller(false, "arn:aws:iam::" + ACCOUNT + ":root", ACCOUNT, ACCOUNT);
    }
    String[] arn = session.arn().split(":", 6);
    return new Caller(true, session.arn(), session.userId(), arn.length == 6 ? arn[4] : ACCOUNT);
  }

  /**
   * The session policy of an {@code AssumeRole}, which the credentials carry and are limited by, see
   * {@linkplain SessionPolicy}.
   *
   * @return the compact JSON of the policy.
   */
  private static String sessionPolicy(String policy) {
    if (policy.length() > 2048) {
      throw new StsException("PackedPolicyTooLarge", 400, "Packed size of consolidated policies exceeds 100%.");
    }
    try {
      SessionPolicy.parse(policy);
    } catch (IllegalArgumentException e) {
      throw new StsException("MalformedPolicyDocument", 400, e.getMessage());
    }
    return SessionPolicy.compact(policy);
  }

  private static Duration duration(Map<String, String> parameters, Duration defaultDuration, Duration maxDuration) {
    Optional<String> value = optional(parameters, "DurationSeconds");
    if (value.isEmpty()) {
      return defaultDuration;
    }
    long seconds;
    try {
      seconds = Long.parseLong(value.get().trim());
    } catch (NumberFormatException e) {
      throw validationError("Value '" + value.get() + "' at 'durationSeconds' failed to satisfy constraint: "
          + "Member must be an integer");
    }
    if (seconds < SessionCredentialIssuer.MIN_DURATION.toSeconds()) {
      throw validationError("Value '" + seconds + "' at 'durationSeconds' failed to satisfy constraint: "
          + "Member must have value greater than or equal to " + SessionCredentialIssuer.MIN_DURATION.toSeconds());
    }
    if (seconds > maxDuration.toSeconds()) {
      throw validationError("Value '" + seconds + "' at 'durationSeconds' failed to satisfy constraint: "
          + "Member must have value less than or equal to " + maxDuration.toSeconds());
    }
    return Duration.ofSeconds(seconds);
  }

  private static String credentialsXml(SessionCredentialIssuer.SessionCredentials credentials) {
    return "<Credentials>"
        + "<AccessKeyId>" + credentials.accessKeyId() + "</AccessKeyId>"
        + "<SecretAccessKey>" + escape(credentials.secretAccessKey()) + "</SecretAccessKey>"
        + "<SessionToken>" + escape(credentials.sessionToken()) + "</SessionToken>"
        + "<Expiration>" + credentials.session().expiration() + "</Expiration>"
        + "</Credentials>";
  }

  /**
   * The parameters of an STS request: the ones of its query, and the ones of its form-urlencoded body, which take
   * precedence.
   */
  private static Map<String, String> parameters(HttpRequest request) {
    Map<String, String> parameters = new HashMap<>();
    request.getParams().forEach((name, values) -> {
      if (!values.isEmpty()) {
        parameters.put(name.toString(), values.get(0));
      }
    });
    String body;
    // Read by index, so that the body is left unread for the controller when the router reads the action.
    try (PayloadBytes bytes = PayloadBytes.of(request.getBody())) {
      if (bytes.length() > MAX_BODY_LENGTH) {
        throw validationError("The request body is too large.");
      }
      body = bytes.toString(0, (int) bytes.length(), StandardCharsets.UTF_8);
    }
    new QueryStringDecoder(body, StandardCharsets.UTF_8, false)
        .parameters()
        .forEach((name, values) -> {
          if (!values.isEmpty()) {
            parameters.put(name, values.get(0));
          }
        });
    return parameters;
  }

  private static String required(Map<String, String> parameters, String name) {
    return optional(parameters, name).orElseThrow(() -> validationError("1 validation error detected: Value null at '"
        + Character.toLowerCase(name.charAt(0)) + name.substring(1)
        + "' failed to satisfy constraint: Member must not be null"));
  }

  private static Optional<String> optional(Map<String, String> parameters, String name) {
    return Optional.ofNullable(parameters.get(name)).filter(value -> !value.isEmpty());
  }

  /**
   * Answer an STS request whose signature is rejected with the error of STS that the rejection corresponds to, in the
   * format of STS, which the STS clients of the AWS SDKs read the error from rather than the one of Amazon S3.
   */
  static void writeAuthenticationFailure(HttpResponse response, AwsSignatureV4Verifier.VerificationResult result) {
    S3ErrorCode errorCode = result.errorCode();
    String requestId = ResponseUtils.nextRequestId();
    switch (errorCode) {
      case InvalidAccessKeyId, InvalidToken -> writeError(response, requestId, "InvalidClientTokenId", 403,
          "The security token included in the request is invalid.");
      case ExpiredToken -> writeError(response, requestId, "ExpiredToken", 400,
          "The security token included in the request is expired");
      case AccessDenied -> writeError(response, requestId, "MissingAuthenticationToken", 403,
          "Request is missing Authentication Token");
      case AuthorizationHeaderMalformed -> writeError(response, requestId, "IncompleteSignature", 400,
          result.message());
      default -> writeError(response, requestId, errorCode.code(), errorCode.httpStatus(), result.message());
    }
  }

  private static void writeError(HttpResponse response, String requestId, String code, int status, String message) {
    writeXml(response, HttpResponseStatus.valueOf(status), requestId, "<ErrorResponse xmlns=\"" + NAMESPACE + "\">"
        + "<Error><Type>Sender</Type><Code>" + escape(code) + "</Code>"
        + "<Message>" + escape(Objects.toString(message, "")) + "</Message></Error>"
        + "<RequestId>" + requestId + "</RequestId>"
        + "</ErrorResponse>");
  }

  private static void writeXml(HttpResponse response, HttpResponseStatus status, String requestId, String xml) {
    response.status(status)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), "text/xml")
        .putHeader("x-amzn-RequestId", requestId)
        .write(xml);
    ResponseUtils.addAmzRequestId(response, requestId);
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
  }

  private static String escape(String value) {
    StringBuilder escaped = new StringBuilder(value.length());
    for (char c : value.toCharArray()) {
      switch (c) {
        case '<' -> escaped.append("&lt;");
        case '>' -> escaped.append("&gt;");
        case '&' -> escaped.append("&amp;");
        case '"' -> escaped.append("&quot;");
        case '\'' -> escaped.append("&apos;");
        default -> escaped.append(c);
      }
    }
    return escaped.toString();
  }

  private static StsException validationError(String message) {
    return new StsException("ValidationError", 400, message);
  }

  /**
   * The identity that a request is signed as.
   *
   * @param temporary whether the request is signed with temporary credentials.
   */
  private record Caller(boolean temporary, String arn, String userId, String account) {
  }

  /**
   * The role of a role ARN, e.g. {@code arn:aws:iam::123456789012:role/path/name}. Like MinIO, any other ARN is accepted
   * as well, as a role of the account of LocalS3 named by the last segment of the ARN.
   */
  private record Role(String partition, String account, String name) {

    static Role parse(String roleArn) {
      String[] parts = roleArn.split(":", 6);
      String resource = parts[parts.length - 1];
      String name = resource.substring(resource.lastIndexOf('/') + 1);
      if (name.isEmpty()) {
        name = "role";
      }
      if (parts.length == 6 && "arn".equals(parts[0])) {
        return new Role(parts[1].isEmpty() ? "aws" : parts[1], parts[4].isEmpty() ? ACCOUNT : parts[4], name);
      }
      return new Role("aws", ACCOUNT, name);
    }
  }

  private static final class StsException extends RuntimeException {

    private final String code;

    private final int status;

    StsException(String code, int status, String message) {
      super(message, null, false, false);
      this.code = code;
      this.status = status;
    }
  }
}
