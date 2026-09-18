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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A stateless <a href="https://docs.aws.amazon.com/kms/latest/APIReference/Welcome.html">AWS KMS</a> endpoint on the
 * port of LocalS3, so that the clients which call KMS before they talk to S3 run unchanged: the Amazon S3 Encryption
 * Client, which wraps a data key with {@code GenerateDataKey} and unwraps it with {@code Decrypt} on every object, and
 * the code that resolves a key with {@code DescribeKey} before it sends {@code x-amz-server-side-encryption:aws:kms}.
 * Like {@linkplain StsController}, it is told apart from an S3 request by its shape rather than by its host or port,
 * see {@linkplain #isKmsRequest}: KMS speaks AWS JSON 1.1, a {@code POST} to {@code /} whose {@code X-Amz-Target}
 * header names the action, signed for the {@code kms} service.
 *
 * <p><b>Nothing is kept secret.</b> A ciphertext blob is the plaintext itself in a framed, base64 encoded envelope
 * that anyone can unpack, and no key material exists: a key ID is whatever the request named. What LocalS3 does give
 * is a faithful <em>round trip</em> — {@code Decrypt} of a blob returns the plaintext that {@code Encrypt} or
 * {@code GenerateDataKey} produced, bound to the same key ID and encryption context, so a client that wraps a data
 * key, stores the blob and unwraps it later reads its object back, and one that mismatches the context or the key gets
 * the {@code InvalidCiphertextException} of KMS instead of silently decrypting. Use it for tests and local
 * development; a blob written by LocalS3 protects nothing.
 *
 * <p>The keys are not stored either, so every key ID is valid and a blob survives a restart: an ID is an alias, a key
 * ID or an ARN, and it is answered as the ARN it names, of the account {@linkplain StsController#ACCOUNT}.
 */
final class KmsController implements HttpRequestHandler {

  /**
   * The prefix that the {@code X-Amz-Target} header of a KMS request names its action with. KMS was named Trent while
   * it was built, and its wire protocol still says so.
   */
  static final String TARGET_PREFIX = "TrentService.";

  /**
   * The content type of AWS JSON 1.1, which KMS speaks.
   */
  static final String CONTENT_TYPE = "application/x-amz-json-1.1";

  /**
   * The operation that the statistics of the requests record a KMS request of an unknown action as.
   */
  static final String UNKNOWN_ACTION_OPERATION = "KmsUnknownAction";

  /**
   * The start of the envelope of a ciphertext blob, which tells a blob of LocalS3 from one of KMS, and names the
   * version of the format.
   */
  private static final byte[] MAGIC = "LocalS3KMS1".getBytes(StandardCharsets.US_ASCII);

  /**
   * The length of the digest of the key ID and the encryption context that an envelope binds a plaintext to.
   */
  private static final int BINDING_LENGTH = 16;

  /**
   * The plaintext of a data key of {@code AES_256}, and the largest plaintext that {@code Encrypt} accepts.
   */
  private static final int AES_256_LENGTH = 32;

  private static final int MAX_PLAINTEXT_LENGTH = 4096;

  /**
   * The largest data key, and the largest {@code GenerateRandom}, that KMS produces.
   */
  private static final int MAX_DATA_KEY_LENGTH = 1024;

  private static final int MAX_RANDOM_LENGTH = MAX_DATA_KEY_LENGTH;

  private static final int MAX_BODY_LENGTH = 1024 * 1024;

  /**
   * The region that the ARN of a key names when the request doesn't, which is the default region of LocalS3.
   */
  private static final String LOCAL_REGION = "us-east-1";

  /**
   * A key ID, an alias or an ARN, as long as KMS allows.
   */
  private static final Pattern KEY_ID = Pattern.compile("[\\w\\-/:%]{1,2048}");

  private static final List<String> ACTIONS = List.of("GenerateDataKey", "GenerateDataKeyWithoutPlaintext",
      "Encrypt", "Decrypt", "DescribeKey", "GenerateRandom");

  private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

  private final SecureRandom random = new SecureRandom();

  /**
   * Whether a request is a KMS request: a {@code POST} to {@code /} whose {@code X-Amz-Target} names an action of KMS,
   * whatever its host. No S3 or STS request carries that header.
   */
  static boolean isKmsRequest(HttpRequest request) {
    return HttpMethod.POST.equals(request.getMethod())
        && "/".equals(request.getPath())
        && target(request).isPresent();
  }

  /**
   * The operation that a KMS request is recorded as: its action, e.g. {@code GenerateDataKey}.
   */
  static String operation(HttpRequest request) {
    return target(request).filter(ACTIONS::contains).orElse(UNKNOWN_ACTION_OPERATION);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) {
    String requestId = ResponseUtils.nextRequestId();
    try {
      String action = target(request)
          .orElseThrow(() -> new KmsException("MissingAction", 400, "Missing X-Amz-Target"));
      ObjectNode body = body(request);
      ObjectNode result = switch (action) {
        case "GenerateDataKey" -> generateDataKey(body, true);
        case "GenerateDataKeyWithoutPlaintext" -> generateDataKey(body, false);
        case "Encrypt" -> encrypt(body);
        case "Decrypt" -> decrypt(body);
        case "DescribeKey" -> describeKey(body);
        case "GenerateRandom" -> generateRandom(body);
        default -> throw new KmsException("UnknownOperationException", 400,
            "Could not find operation " + action + " for version 2014-11-01");
      };
      writeJson(response, HttpResponseStatus.OK, requestId, result);
    } catch (KmsException e) {
      writeError(response, requestId, e.code, e.status, e.getMessage());
    }
  }

  /**
   * Wrap a fresh data key, which a client encrypts an object with. The plaintext is random, so two objects never share
   * a data key, and it is the blob that carries it back rather than any state of LocalS3.
   *
   * @param withPlaintext whether the plaintext of the data key is answered, i.e. {@code GenerateDataKey} rather than
   *     {@code GenerateDataKeyWithoutPlaintext}.
   */
  private ObjectNode generateDataKey(ObjectNode body, boolean withPlaintext) {
    String keyId = keyId(body);
    Map<String, String> context = encryptionContext(body);
    Optional<String> keySpec = string(body, "KeySpec");
    Optional<Integer> numberOfBytes = integer(body, "NumberOfBytes");
    if (keySpec.isPresent() && numberOfBytes.isPresent()) {
      throw validationError("Please specify either number of bytes or key spec.");
    }
    int length = keySpec.map(spec -> switch (spec) {
      case "AES_256" -> AES_256_LENGTH;
      case "AES_128" -> 16;
      default -> throw validationError("Value '" + spec + "' at 'keySpec' failed to satisfy constraint: "
          + "Member must satisfy enum value set: [AES_256, AES_128]");
    }).orElseGet(() -> numberOfBytes.orElse(AES_256_LENGTH));
    if (length < 1 || length > MAX_DATA_KEY_LENGTH) {
      throw validationError("Value '" + length + "' at 'numberOfBytes' failed to satisfy constraint: "
          + "Member must have value less than or equal to " + MAX_DATA_KEY_LENGTH);
    }

    byte[] plaintext = new byte[length];
    random.nextBytes(plaintext);
    ObjectNode result = JSON.createObjectNode();
    result.put("KeyId", arn(keyId));
    result.put("CiphertextBlob", base64(wrap(keyId, context, plaintext)));
    if (withPlaintext) {
      result.put("Plaintext", base64(plaintext));
    }
    return result;
  }

  private ObjectNode encrypt(ObjectNode body) {
    String keyId = keyId(body);
    Map<String, String> context = encryptionContext(body);
    byte[] plaintext = blob(body, "Plaintext");
    if (plaintext.length == 0 || plaintext.length > MAX_PLAINTEXT_LENGTH) {
      throw validationError("Value at 'plaintext' failed to satisfy constraint: Member must have length between 1 and "
          + MAX_PLAINTEXT_LENGTH);
    }
    ObjectNode result = JSON.createObjectNode();
    result.put("KeyId", arn(keyId));
    result.put("CiphertextBlob", base64(wrap(keyId, context, plaintext)));
    result.put("EncryptionAlgorithm", encryptionAlgorithm(body));
    return result;
  }

  /**
   * Unwrap a blob of LocalS3. The key ID of the request is optional, like it is for a symmetric key of KMS, and the
   * encryption context must be the one that the blob was wrapped with.
   */
  private ObjectNode decrypt(ObjectNode body) {
    byte[] blob = blob(body, "CiphertextBlob");
    Map<String, String> context = encryptionContext(body);
    Optional<String> requestedKeyId = string(body, "KeyId").map(KmsController::arn);
    Envelope envelope = unwrap(blob);
    if (requestedKeyId.isPresent() && !requestedKeyId.get().equals(arn(envelope.keyId()))) {
      throw new KmsException("IncorrectKeyException", 400,
          "The key ID in the request does not identify a CMK that can perform this operation.");
    }
    if (!MessageDigest.isEqual(envelope.binding(), binding(envelope.keyId(), context))) {
      throw new KmsException("InvalidCiphertextException", 400,
          "The ciphertext refers to a customer master key that does not exist, does not exist in the specified "
              + "region, or you are not allowed to access.");
    }
    ObjectNode result = JSON.createObjectNode();
    result.put("KeyId", arn(envelope.keyId()));
    result.put("Plaintext", base64(envelope.plaintext()));
    result.put("EncryptionAlgorithm", encryptionAlgorithm(body));
    return result;
  }

  /**
   * Describe a key, which LocalS3 answers for every key ID: there are none to look up, and a client that resolves an
   * alias before it uses it should get an answer rather than {@code NotFoundException}.
   */
  private ObjectNode describeKey(ObjectNode body) {
    String keyId = keyId(body);
    ObjectNode metadata = JSON.createObjectNode();
    metadata.put("AWSAccountId", StsController.ACCOUNT);
    metadata.put("KeyId", id(keyId));
    metadata.put("Arn", arn(keyId));
    metadata.put("Enabled", true);
    metadata.put("Description", "A key of LocalS3, which encrypts nothing.");
    metadata.put("KeyUsage", "ENCRYPT_DECRYPT");
    metadata.put("KeyState", "Enabled");
    metadata.put("Origin", "AWS_KMS");
    metadata.put("KeyManager", "CUSTOMER");
    metadata.put("KeySpec", "SYMMETRIC_DEFAULT");
    metadata.put("CustomerMasterKeySpec", "SYMMETRIC_DEFAULT");
    metadata.put("MultiRegion", false);
    metadata.putArray("EncryptionAlgorithms").add("SYMMETRIC_DEFAULT");
    ObjectNode result = JSON.createObjectNode();
    result.set("KeyMetadata", metadata);
    return result;
  }

  private ObjectNode generateRandom(ObjectNode body) {
    int length = integer(body, "NumberOfBytes").orElseThrow(() -> validationError(
        "1 validation error detected: Value null at 'numberOfBytes' failed to satisfy constraint: "
            + "Member must not be null"));
    if (length < 1 || length > MAX_RANDOM_LENGTH) {
      throw validationError("Value '" + length + "' at 'numberOfBytes' failed to satisfy constraint: "
          + "Member must have value less than or equal to " + MAX_RANDOM_LENGTH);
    }
    byte[] bytes = new byte[length];
    random.nextBytes(bytes);
    ObjectNode result = JSON.createObjectNode();
    result.put("Plaintext", base64(bytes));
    return result;
  }

  /**
   * The envelope of a plaintext: {@linkplain #MAGIC}, the digest that binds it to its key ID and encryption context,
   * the key ID, and the plaintext itself. It hides nothing, and is read back by {@linkplain #unwrap}.
   */
  private static byte[] wrap(String keyId, Map<String, String> context, byte[] plaintext) {
    byte[] key = keyId.getBytes(StandardCharsets.UTF_8);
    byte[] binding = binding(keyId, context);
    byte[] envelope = new byte[MAGIC.length + BINDING_LENGTH + 2 + key.length + plaintext.length];
    int offset = 0;
    System.arraycopy(MAGIC, 0, envelope, offset, MAGIC.length);
    offset += MAGIC.length;
    System.arraycopy(binding, 0, envelope, offset, BINDING_LENGTH);
    offset += BINDING_LENGTH;
    envelope[offset++] = (byte) (key.length >>> 8);
    envelope[offset++] = (byte) key.length;
    System.arraycopy(key, 0, envelope, offset, key.length);
    offset += key.length;
    System.arraycopy(plaintext, 0, envelope, offset, plaintext.length);
    return envelope;
  }

  private static Envelope unwrap(byte[] blob) {
    if (blob.length < MAGIC.length + BINDING_LENGTH + 2
        || !Arrays.equals(MAGIC, 0, MAGIC.length, blob, 0, MAGIC.length)) {
      throw new KmsException("InvalidCiphertextException", 400,
          "The ciphertext was not produced by the KMS endpoint of LocalS3.");
    }
    int offset = MAGIC.length;
    byte[] binding = Arrays.copyOfRange(blob, offset, offset + BINDING_LENGTH);
    offset += BINDING_LENGTH;
    int length = ((blob[offset] & 0xFF) << 8) | (blob[offset + 1] & 0xFF);
    offset += 2;
    if (offset + length > blob.length) {
      throw new KmsException("InvalidCiphertextException", 400, "The ciphertext is truncated.");
    }
    String keyId = new String(blob, offset, length, StandardCharsets.UTF_8);
    return new Envelope(keyId, binding, Arrays.copyOfRange(blob, offset + length, blob.length));
  }

  /**
   * What an envelope binds a plaintext to: the digest of its key ID and of its encryption context, whose entries are
   * digested in the order of their names, so that a {@code Decrypt} with another key or another context fails the way
   * it fails against KMS.
   */
  private static byte[] binding(String keyId, Map<String, String> context) {
    MessageDigest digest = sha256();
    digest.update(arn(keyId).getBytes(StandardCharsets.UTF_8));
    new TreeMap<>(context).forEach((name, value) -> {
      digest.update((byte) 0);
      digest.update(name.getBytes(StandardCharsets.UTF_8));
      digest.update((byte) '=');
      digest.update(value.getBytes(StandardCharsets.UTF_8));
    });
    return Arrays.copyOf(digest.digest(), BINDING_LENGTH);
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * The ARN that a key ID names: an ARN as it is, and any other ID or alias as a key of the account of LocalS3.
   */
  private static String arn(String keyId) {
    if (keyId.startsWith("arn:")) {
      return keyId;
    }
    String resource = keyId.startsWith("alias/") ? keyId : "key/" + keyId;
    return "arn:aws:kms:" + LOCAL_REGION + ":" + StsController.ACCOUNT + ":" + resource;
  }

  /**
   * The key ID of a key ID or an ARN, i.e. what {@code KeyMetadata.KeyId} answers, which is never an alias.
   */
  private static String id(String keyId) {
    String resource = keyId.startsWith("arn:") ? keyId.substring(keyId.lastIndexOf(':') + 1) : keyId;
    int slash = resource.lastIndexOf('/');
    return slash < 0 ? resource : resource.substring(slash + 1);
  }

  private static String keyId(ObjectNode body) {
    String keyId = string(body, "KeyId").orElseThrow(() -> validationError(
        "1 validation error detected: Value null at 'keyId' failed to satisfy constraint: Member must not be null"));
    if (!KEY_ID.matcher(keyId).matches()) {
      throw validationError("Value '" + keyId + "' at 'keyId' failed to satisfy constraint: "
          + "Member must satisfy regular expression pattern: ^[a-zA-Z0-9:/_-]+$");
    }
    return keyId;
  }

  private static Map<String, String> encryptionContext(ObjectNode body) {
    JsonNode node = body.get("EncryptionContext");
    if (node == null || node.isNull()) {
      return Map.of();
    }
    if (!node.isObject()) {
      throw validationError("Value at 'encryptionContext' failed to satisfy constraint: Member must be a map");
    }
    Map<String, String> context = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : node.properties()) {
      context.put(entry.getKey(), entry.getValue().asString());
    }
    return context;
  }

  /**
   * The encryption algorithm of a request, which is the symmetric one: LocalS3 has no asymmetric keys.
   */
  private static String encryptionAlgorithm(ObjectNode body) {
    return string(body, "EncryptionAlgorithm").map(algorithm -> {
      if (!"SYMMETRIC_DEFAULT".equals(algorithm)) {
        throw validationError("Value '" + algorithm + "' at 'encryptionAlgorithm' failed to satisfy constraint: "
            + "Member must satisfy enum value set: [SYMMETRIC_DEFAULT]");
      }
      return algorithm;
    }).orElse("SYMMETRIC_DEFAULT");
  }

  private static byte[] blob(ObjectNode body, String name) {
    String value = string(body, name).orElseThrow(() -> validationError("1 validation error detected: Value null at '"
        + Character.toLowerCase(name.charAt(0)) + name.substring(1)
        + "' failed to satisfy constraint: Member must not be null"));
    try {
      return Base64.getDecoder().decode(value);
    } catch (IllegalArgumentException e) {
      throw validationError("Value at '" + Character.toLowerCase(name.charAt(0)) + name.substring(1)
          + "' failed to satisfy constraint: Member must be base64 encoded");
    }
  }

  private static Optional<String> string(ObjectNode body, String name) {
    return Optional.ofNullable(body.get(name))
        .filter(node -> !node.isNull())
        .map(JsonNode::asString)
        .filter(value -> !value.isEmpty());
  }

  private static Optional<Integer> integer(ObjectNode body, String name) {
    JsonNode node = body.get(name);
    if (node == null || node.isNull()) {
      return Optional.empty();
    }
    if (!node.isIntegralNumber()) {
      throw validationError("Value at '" + Character.toLowerCase(name.charAt(0)) + name.substring(1)
          + "' failed to satisfy constraint: Member must be an integer");
    }
    return Optional.of(node.asInt());
  }

  private static String base64(byte[] bytes) {
    return Base64.getEncoder().encodeToString(bytes);
  }

  /**
   * The action of a KMS request, which its {@code X-Amz-Target} header names as
   * {@code TrentService.<action>}; empty if the request carries no such header.
   */
  private static Optional<String> target(HttpRequest request) {
    return request.header(AmzHeaderNames.X_AMZ_TARGET)
        .map(String::trim)
        .filter(target -> target.startsWith(TARGET_PREFIX))
        .map(target -> target.substring(TARGET_PREFIX.length()))
        .filter(action -> !action.isEmpty());
  }

  /**
   * The JSON body of a KMS request; an empty object for a request without one, e.g. a {@code GenerateRandom} of an
   * older SDK.
   */
  private static ObjectNode body(HttpRequest request) {
    String body;
    // Read by index, so that the body is left unread for the controller when the router reads the action.
    try (PayloadBytes bytes = PayloadBytes.of(request.getBody())) {
      if (bytes.length() > MAX_BODY_LENGTH) {
        throw validationError("The request body is too large.");
      }
      body = bytes.toString(0, (int) bytes.length(), StandardCharsets.UTF_8).trim();
    }
    if (body.isEmpty()) {
      return JSON.createObjectNode();
    }
    JsonNode node;
    try {
      node = JSON.readTree(body);
    } catch (JacksonException e) {
      throw new KmsException("SerializationException", 400, "Unable to unmarshall request.");
    }
    if (!node.isObject()) {
      throw new KmsException("SerializationException", 400, "Unable to unmarshall request.");
    }
    return (ObjectNode) node;
  }

  /**
   * Answer a KMS request whose signature is rejected with the error of KMS that the rejection corresponds to, in the
   * JSON format that the KMS clients of the AWS SDKs read an error from rather than the {@code <Error>} of Amazon S3.
   */
  static void writeAuthenticationFailure(HttpResponse response, AwsSignatureV4Verifier.VerificationResult result) {
    S3ErrorCode errorCode = result.errorCode();
    String requestId = ResponseUtils.nextRequestId();
    switch (errorCode) {
      case InvalidAccessKeyId, InvalidToken -> writeError(response, requestId, "UnrecognizedClientException", 400,
          "The security token included in the request is invalid.");
      case ExpiredToken -> writeError(response, requestId, "ExpiredTokenException", 400,
          "The security token included in the request is expired");
      case AccessDenied -> writeError(response, requestId, "MissingAuthenticationTokenException", 400,
          "Request is missing Authentication Token");
      case SignatureDoesNotMatch -> writeError(response, requestId, "InvalidSignatureException", 400, result.message());
      default -> writeError(response, requestId, "IncompleteSignature", 400, result.message());
    }
  }

  private static void writeError(HttpResponse response, String requestId, String code, int status, String message) {
    ObjectNode error = JSON.createObjectNode();
    error.put("__type", code);
    error.put("message", Objects.toString(message, ""));
    response.putHeader(AmzHeaderNames.X_AMZN_ERRORTYPE, code);
    writeJson(response, HttpResponseStatus.valueOf(status), requestId, error);
  }

  private static void writeJson(HttpResponse response, HttpResponseStatus status, String requestId, ObjectNode body) {
    response.status(status)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), CONTENT_TYPE)
        .putHeader("x-amzn-RequestId", requestId)
        .write(JSON.writeValueAsString(body));
    ResponseUtils.addAmzRequestId(response, requestId);
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addServerHeader(response);
  }

  private static KmsException validationError(String message) {
    return new KmsException("ValidationException", 400, message);
  }

  /**
   * What a ciphertext blob carries: the plaintext, the key ID it was wrapped with, and the digest that binds it to
   * that key ID and to its encryption context.
   */
  private record Envelope(String keyId, byte[] binding, byte[] plaintext) {
  }

  private static final class KmsException extends RuntimeException {

    private final String code;

    private final int status;

    KmsException(String code, int status, String message) {
      super(message, null, false, false);
      this.code = code;
      this.status = status;
    }
  }
}
