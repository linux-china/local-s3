package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * Handle <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CreateSession.html">CreateSession</a> of S3
 * Express One Zone; see {@code docs/semantics.md#s3-express-one-zone-directory-buckets}.
 *
 * <p>The session is stateless, issued and verified by the {@linkplain SessionCredentialIssuer} of the STS endpoint. The
 * bucket of the session needn't exist, because with an endpoint override the AWS SDK for Java creates a session even for
 * {@code CreateBucket}, which Amazon S3 answers through its control endpoint without one.
 */
class CreateSessionController implements RouterHttpRequestHandler {

  static final Duration SESSION_DURATION = Duration.ofMinutes(5);

  private static final Set<String> SESSION_MODES = Set.of("ReadWrite", "ReadOnly");

  private final SessionCredentialIssuer issuer;

  CreateSessionController(SessionCredentialIssuer issuer) {
    this.issuer = Objects.requireNonNull(issuer);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    RequestAssertions.assertBucketNameProvided(request);
    request.header(AmzHeaderNames.X_AMZ_CREATE_SESSION_MODE)
        .filter(mode -> !SESSION_MODES.contains(mode))
        .ifPresent(mode -> {
          throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_CREATE_SESSION_MODE, mode,
              "The session mode must be ReadWrite or ReadOnly.");
        });

    String arn = "arn:aws:iam::" + StsController.ACCOUNT + ":root";
    SessionCredentialIssuer.SessionCredentials credentials =
        issuer.issue(SESSION_DURATION, arn, StsController.ACCOUNT);
    // Base64 and base64url, which need no escaping in XML.
    String xml = "<CreateSessionResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
        + "<Credentials>"
        + "<SessionToken>" + credentials.sessionToken() + "</SessionToken>"
        + "<SecretAccessKey>" + credentials.secretAccessKey() + "</SecretAccessKey>"
        + "<AccessKeyId>" + credentials.accessKeyId() + "</AccessKeyId>"
        + "<Expiration>" + credentials.session().expiration() + "</Expiration>"
        + "</Credentials>"
        + "</CreateSessionResult>";

    // The encryption that the objects written in the session get, which LocalS3 accepts without encrypting them.
    String encryption = request.header(AmzHeaderNames.X_AMZ_SERVER_SIDE_ENCRYPTION).orElse("AES256");
    response.status(HttpResponseStatus.OK)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
        .putHeader(AmzHeaderNames.X_AMZ_SERVER_SIDE_ENCRYPTION, encryption)
        .write(xml);
    request.header(AmzHeaderNames.X_AMZ_SSE_KMS_KEY_ID)
        .ifPresent(keyId -> response.putHeader(AmzHeaderNames.X_AMZ_SSE_KMS_KEY_ID, keyId));
    request.header(AmzHeaderNames.X_AMZ_SSE_CONTEXT)
        .ifPresent(context -> response.putHeader(AmzHeaderNames.X_AMZ_SSE_CONTEXT, context));
    request.header(AmzHeaderNames.X_AMZ_SSE_BUCKET_KEY_ENABLED)
        .ifPresent(enabled -> response.putHeader(AmzHeaderNames.X_AMZ_SSE_BUCKET_KEY_ENABLED, enabled));
    ResponseUtils.addCommonHeaders(response);
  }

}
