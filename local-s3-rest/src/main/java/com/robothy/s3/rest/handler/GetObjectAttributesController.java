package com.robothy.s3.rest.handler;

import com.robothy.s3.rest.model.response.ChecksumElements;
import com.robothy.s3.core.model.internal.ObjectChecksum;
import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.internal.ObjectPartMetadata;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.service.GetObjectService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.model.internal.SystemMetadata;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.model.response.GetObjectAttributesResult;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.CustomerEncryptionHeaders;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * See {@code docs/semantics.md#object-attributes}.
 */
class GetObjectAttributesController implements RouterHttpRequestHandler {

  private static final int MAX_PARTS_LIMIT = 1000;

  private final GetObjectService objectService;

  private final XmlMapper xmlMapper;

  GetObjectAttributesController(ServiceFactory serviceFactory) {
    this.objectService = serviceFactory.getInstance(ObjectService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucket = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    Set<ObjectAttribute> attributes = parseAttributes(request);

    GetObjectOptions options = GetObjectOptions.builder()
        .versionId(request.parameter("versionId").orElse(null))
        .customerEncryption(CustomerEncryptionHeaders.fromRequest(request))
        .build();
    GetObjectAns object = objectService.headObject(bucket, key, options);

    response.putHeader(HttpHeaderNames.LAST_MODIFIED.toString(),
        ResponseUtils.toRfc1123DateTime(object.getLastModified()));
    ResponseUtils.putHeaderIfPresent(response, AmzHeaderNames.X_AMZ_VERSION_ID, object.getVersionId());

    if (object.isDeleteMarker()) {
      response.status(HttpResponseStatus.METHOD_NOT_ALLOWED)
          .putHeader(AmzHeaderNames.X_AMZ_DELETE_MARKER, true);
    } else {
      response.status(HttpResponseStatus.OK)
          .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
          .write(xmlMapper.writeValueAsString(result(request, attributes, object)));
    }

    ResponseUtils.addDateHeader(response);
    ResponseUtils.addAmzRequestId(response);
    ResponseUtils.addServerHeader(response);
  }

  private GetObjectAttributesResult result(RouterHttpRequest request, Set<ObjectAttribute> attributes,
                                           GetObjectAns object) {
    GetObjectAttributesResult.GetObjectAttributesResultBuilder result = GetObjectAttributesResult.builder();
    if (attributes.contains(ObjectAttribute.ETAG)) {
      // Unlike every other answer of Amazon S3, this one carries the entity tag without its quotes.
      result.etag(object.getEtag());
    }
    if (attributes.contains(ObjectAttribute.OBJECT_SIZE)) {
      result.objectSize(object.getSize());
    }
    if (attributes.contains(ObjectAttribute.CHECKSUM)) {
      result.checksum(ChecksumElements.of(object.getChecksum()));
    }
    if (attributes.contains(ObjectAttribute.STORAGE_CLASS)) {
      result.storageClass(SystemMetadata.storageClassOf(object.getSystemMetadata()));
    }
    if (attributes.contains(ObjectAttribute.OBJECT_PARTS)) {
      result.objectParts(objectParts(request, object));
    }
    return result.build();
  }

  /** {@code null} if the object wasn't uploaded in parts, which leaves the element out of the answer. */
  private GetObjectAttributesResult.ObjectParts objectParts(RouterHttpRequest request, GetObjectAns object) {
    List<ObjectPartMetadata> parts = object.getParts();
    if (Objects.isNull(parts) || parts.isEmpty()) {
      return null;
    }

    int partNumberMarker = intHeaderOrDefault(request, AmzHeaderNames.X_AMZ_PART_NUMBER_MARKER, 0);
    int maxParts = Math.min(intHeaderOrDefault(request, AmzHeaderNames.X_AMZ_MAX_PARTS, MAX_PARTS_LIMIT),
        MAX_PARTS_LIMIT);

    List<GetObjectAttributesResult.Part> page = new ArrayList<>();
    for (ObjectPartMetadata part : parts) {
      if (part.getPartNumber() <= partNumberMarker) {
        continue;
      }
      if (page.size() == maxParts) {
        break;
      }
      page.add(GetObjectAttributesResult.Part.builder()
          .partNumber(part.getPartNumber())
          .size(part.getSize())
          .checksum(partChecksum(object, part))
          .build());
    }

    // The parts are held in the order they were concatenated in, which is ascending part number.
    int lastPartNumber = parts.getLast().getPartNumber();
    int lastOnPage = page.isEmpty() ? 0 : page.getLast().getPartNumber();
    return GetObjectAttributesResult.ObjectParts.builder()
        .partsCount(parts.size())
        .partNumberMarker(partNumberMarker)
        .nextPartNumberMarker(lastOnPage)
        .maxParts(maxParts)
        .truncated(!page.isEmpty() && lastOnPage < lastPartNumber)
        .parts(page)
        .build();
  }

  private static ChecksumElements partChecksum(GetObjectAns object, ObjectPartMetadata part) {
    ObjectChecksum objectChecksum = object.getChecksum();
    ObjectChecksum partChecksum = part.getChecksum();
    if (Objects.isNull(objectChecksum) || Objects.isNull(partChecksum)
        || objectChecksum.getAlgorithm() != partChecksum.getAlgorithm()) {
      return null;
    }
    return ChecksumElements.valueOf(partChecksum);
  }

  private int intHeaderOrDefault(RouterHttpRequest request, String headerName, int defaultValue) {
    String value = request.header(headerName).orElse(null);
    if (Objects.isNull(value)) {
      return defaultValue;
    }

    int parsed;
    try {
      parsed = Integer.parseInt(value.trim());
    } catch (NumberFormatException _) {
      throw new LocalS3InvalidArgumentException(headerName, value, "Value must be a number.");
    }
    if (parsed < 0) {
      throw new LocalS3InvalidArgumentException(headerName, value, "Value must not be negative.");
    }
    return parsed;
  }

  private Set<ObjectAttribute> parseAttributes(RouterHttpRequest request) {
    String headerValue = request.header(AmzHeaderNames.X_AMZ_OBJECT_ATTRIBUTES)
        .orElseThrow(() -> invalidAttributes(null));
    try {
      Set<ObjectAttribute> attributes = Arrays.stream(headerValue.split(","))
          .map(String::trim)
          .filter(value -> !value.isEmpty())
          .map(ObjectAttribute::fromHeaderValue)
          .collect(Collectors.toCollection(() -> EnumSet.noneOf(ObjectAttribute.class)));
      if (attributes.isEmpty()) {
        throw invalidAttributes(headerValue);
      }
      return attributes;
    } catch (IllegalArgumentException _) {
      throw invalidAttributes(headerValue);
    }
  }

  private LocalS3InvalidArgumentException invalidAttributes(String value) {
    return new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_OBJECT_ATTRIBUTES, value,
        "Invalid object attributes specified in request.");
  }

  private enum ObjectAttribute {
    ETAG,
    CHECKSUM,
    OBJECT_PARTS,
    STORAGE_CLASS,
    OBJECT_SIZE;

    private static ObjectAttribute fromHeaderValue(String value) {
      return ObjectAttribute.valueOf(value
          .replaceAll("([a-z])([A-Z])", "$1_$2")
          .toUpperCase(Locale.ROOT));
    }
  }
}
