package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.exception.LocalS3RequestException;
import com.robothy.s3.core.exception.S3ErrorCode;
import com.robothy.s3.core.model.answers.PutObjectAns;
import com.robothy.s3.core.model.internal.CustomerEncryption;
import com.robothy.s3.core.model.request.PutObjectOptions;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.datatypes.Tagging;
import com.robothy.s3.datatypes.response.PostResponse;
import com.robothy.s3.rest.LocalS3Config;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.netty.ConnectionSchemes;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.CustomerEncryptionHeaders;
import com.robothy.s3.rest.utils.MultipartFormData;
import com.robothy.s3.rest.utils.ResponseUtils;
import com.robothy.s3.rest.utils.ServerSideEncryptionHeaders;
import com.robothy.s3.rest.utils.SystemMetadataHeaders;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/RESTObjectPOST.html">POST Object</a>, the upload
 * of a file by an HTML form that a browser posts to a bucket.
 *
 * <p>The form carries what a {@code PutObject} request carries in its headers: the {@code key}, which may contain
 * {@code ${filename}}, the {@code Content-Type}, the system-defined metadata, {@code x-amz-meta-*},
 * {@code tagging} and the {@code x-amz-server-side-encryption*} fields, SSE-C ones included. If the service requires signed requests, the form must carry a {@code policy} and its signature,
 * see {@linkplain AwsSignatureV4Verifier#verifyPostPolicy}; a form that carries a policy has it checked either way,
 * see {@linkplain PostPolicy}, so that a form can be debugged against a service that doesn't require signatures.
 *
 * <p>The response is a redirect to {@code success_action_redirect} if the form names one, and otherwise has the
 * {@code success_action_status} of the form, {@code 204 No Content} by default, or {@code 201 Created} with a
 * {@code PostResponse} document.
 */
class PostObjectController implements HttpRequestHandler {

  /**
   * The operation that the change of an object stored by a form is named after.
   */
  static final String OPERATION = "PostObject";

  private static final String FILENAME_VARIABLE = "${filename}";

  private final ObjectService objectService;

  private final XmlMapper xmlMapper;

  /**
   * Verifies the signature of the policy; {@code null} if the service doesn't require signed requests.
   */
  private final AwsSignatureV4Verifier signatureVerifier;

  private final Clock clock;

  /**
   * The scheme of the URLs that the service is reached at when the request doesn't say: {@code https} if it serves
   * TLS. A port that answers both HTTP and HTTPS is told by the request, see {@linkplain ConnectionSchemes}.
   */
  private final String defaultScheme;

  PostObjectController(ServiceFactory serviceFactory, AwsSignatureV4Verifier signatureVerifier) {
    this(serviceFactory, signatureVerifier, Clock.systemUTC());
  }

  PostObjectController(ServiceFactory serviceFactory, AwsSignatureV4Verifier signatureVerifier, Clock clock) {
    this.objectService = serviceFactory.getInstance(ObjectService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
    this.signatureVerifier = signatureVerifier;
    this.clock = Objects.requireNonNull(clock);
    this.defaultScheme = serviceFactory.containsInstance(LocalS3Config.class)
        && serviceFactory.getInstance(LocalS3Config.class).tlsEnabled()
        ? ConnectionSchemes.HTTPS : ConnectionSchemes.HTTP;
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    MultipartFormData form = MultipartFormData.parse(request.header(HttpHeaderNames.CONTENT_TYPE).orElse(null),
        request.getBody());
    ByteBuf file = form.file().content();

    Optional<String> policy = form.field("policy").filter(value -> !value.isBlank());
    if (signatureVerifier != null) {
      if (policy.isEmpty()) {
        throw new LocalS3RequestException(S3ErrorCode.AccessDenied,
            "Bucket POST must contain a field named 'policy' and its signature.");
      }
      AwsSignatureV4Verifier.VerificationResult result = signatureVerifier.verifyPostPolicy(policy.get(), form::field);
      if (!result.authenticated()) {
        throw new LocalS3RequestException(result.errorCode(), result.message());
      }
    }
    // The key with ${filename} replaced by the name of the file, which is also what the policy is checked against,
    // like Amazon S3 does.
    Optional<String> formKey = form.field("key")
        .map(value -> value.replace(FILENAME_VARIABLE, form.file().filename()));
    if (policy.isPresent()) {
      List<String> fieldNames = new ArrayList<>();
      form.fields().forEach(field -> fieldNames.add(field.name()));
      PostPolicy.parse(policy.get()).check(clock.instant(), bucketName, fieldNames,
          name -> "key".equalsIgnoreCase(name) ? formKey : form.field(name), file.readableBytes());
    }

    String key = formKey
        .filter(value -> !value.isEmpty())
        .orElseThrow(() -> new LocalS3InvalidArgumentException("key", "",
            "Bucket POST must contain a field named 'key'.  If it is specified, please check the order of the "
                + "fields."));

    Function<String, String> values = name -> form.field(name).orElse(null);
    CustomerEncryption customerEncryption = CustomerEncryptionHeaders.fromValues(values);
    PutObjectOptions options = PutObjectOptions.builder()
        .operation(OPERATION)
        .contentType(form.field("Content-Type").orElse(null))
        .systemMetadata(SystemMetadataHeaders.fromValues(values))
        .size(file.readableBytes())
        .content(new ByteBufInputStream(file.duplicate()))
        .tagging(tagging(form))
        .userMetadata(userMetadata(form))
        .customerEncryption(customerEncryption)
        .serverSideEncryption(ServerSideEncryptionHeaders.fromValues(values, customerEncryption))
        .build();
    PutObjectAns ans = objectService.putObject(bucketName, key, options);

    String etag = ResponseUtils.quoteEtag(ans.getEtag());
    ResponseUtils.addCommonHeaders(response);
    ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_VERSION_ID, ans.getVersionId());
    ResponseUtils.addETag(response, ans.getEtag());
    CustomerEncryptionHeaders.addHeaders(response, customerEncryption);
    ServerSideEncryptionHeaders.addHeaders(response, ans.getServerSideEncryption(), true);
    Optional<URI> redirect = form.field("success_action_redirect").or(() -> form.field("redirect"))
        .flatMap(PostObjectController::redirectUri);
    if (redirect.isPresent()) {
      response.status(HttpResponseStatus.SEE_OTHER)
          .putHeader(HttpHeaderNames.LOCATION.toString(), redirectLocation(redirect.get(), bucketName, key, etag));
      return;
    }

    String location = objectLocation(request, bucketName, key);
    response.putHeader(HttpHeaderNames.LOCATION.toString(), location);

    switch (form.field("success_action_status").orElse("")) {
      case "200" -> response.status(HttpResponseStatus.OK);
      case "201" -> response.status(HttpResponseStatus.CREATED)
          .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
          .write(xmlMapper.writeValueAsString(PostResponse.builder()
              .location(location)
              .bucket(bucketName)
              .key(key)
              .etag(etag)
              .build()));
      default -> response.status(HttpResponseStatus.NO_CONTENT);
    }
  }

  /**
   * The tags of the {@code tagging} field, a {@code Tagging} document.
   */
  private String[][] tagging(MultipartFormData form) {
    Optional<String> tagging = form.field("tagging").filter(value -> !value.isBlank());
    if (tagging.isEmpty()) {
      return null;
    }
    try {
      return xmlMapper.readValue(tagging.get(), Tagging.class).toArrays();
    } catch (JacksonException e) {
      throw new LocalS3RequestException(S3ErrorCode.MalformedXML);
    }
  }

  private static Map<String, String> userMetadata(MultipartFormData form) {
    Map<String, String> userMetadata = new HashMap<>();
    form.fields().forEach(field -> {
      String name = field.name().toLowerCase(Locale.ROOT);
      if (name.startsWith(AmzHeaderNames.X_AMZ_META_PREFIX)) {
        userMetadata.put(RequestAssertions.assertUserMetadataHeaderIsValid(name), field.value());
      }
    });
    return userMetadata;
  }

  /**
   * The URL of the stored object, addressed like the form was: by the scheme the form was posted with, at the bucket
   * of the path, or at the bucket of the host of a virtual-hosted-style request.
   */
  private String objectLocation(HttpRequest request, String bucketName, String key) {
    String host = request.header(HttpHeaderNames.HOST).orElse("localhost");
    String path = request.getPath() == null ? "/" : request.getPath();
    boolean bucketInPath = !path.replace("/", "").isEmpty();
    String scheme = ConnectionSchemes.of(request).orElse(defaultScheme);
    return scheme + "://" + host + "/" + (bucketInPath ? S3ObjectUtils.urlEncode(bucketName, false) + "/" : "") + encodeKey(key);
  }

  /**
   * The URI of {@code success_action_redirect}; empty if it isn't an absolute URI, which Amazon S3 ignores too.
   */
  private static Optional<URI> redirectUri(String value) {
    try {
      URI uri = new URI(value.trim());
      return uri.isAbsolute() && uri.getHost() != null ? Optional.of(uri) : Optional.empty();
    } catch (URISyntaxException e) {
      return Optional.empty();
    }
  }

  /**
   * The redirect URI with the {@code bucket}, {@code key} and {@code etag} of the stored object added to its query.
   */
  private static String redirectLocation(URI redirect, String bucketName, String key, String etag) {
    String value = redirect.toString();
    int fragment = value.indexOf('#');
    String base = fragment < 0 ? value : value.substring(0, fragment);
    String query = "bucket=" + encodeQuery(bucketName) + "&key=" + encodeQuery(key) + "&etag=" + encodeQuery(etag);
    return base + (redirect.getRawQuery() == null ? "?" : "&") + query + (fragment < 0 ? "" : value.substring(fragment));
  }

  private static String encodeKey(String key) {
    return S3ObjectUtils.urlEncode(key, true);
  }

  private static String encodeQuery(String value) {
    return S3ObjectUtils.urlEncode(value, false);
  }

}
