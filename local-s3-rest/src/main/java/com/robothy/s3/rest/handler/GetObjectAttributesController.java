package com.robothy.s3.rest.handler;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.answers.GetObjectAns;
import com.robothy.s3.core.model.internal.ObjectPartMetadata;
import com.robothy.s3.core.model.request.GetObjectOptions;
import com.robothy.s3.core.service.GetObjectService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.datatypes.enums.StorageClass;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import com.robothy.s3.rest.model.response.GetObjectAttributesResult;
import com.robothy.s3.rest.service.ServiceFactory;
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

/**
 * Handle request of <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectAttributes.html">GetObjectAttributes</a>.
 */
class GetObjectAttributesController implements HttpRequestHandler {

  /**
   * The largest page of parts that {@code ObjectParts} is answered with, which is also the page that a
   * request asking for no particular size gets. Amazon S3 uses the same limit for {@code ListParts}.
   */
  private static final int MAX_PARTS_LIMIT = 1000;

  private final GetObjectService objectService;

  private final XmlMapper xmlMapper;

  GetObjectAttributesController(ServiceFactory serviceFactory) {
    this.objectService = serviceFactory.getInstance(ObjectService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(HttpRequest request, HttpResponse response) throws Exception {
    String bucket = RequestAssertions.assertBucketNameProvided(request);
    String key = RequestAssertions.assertObjectKeyProvided(request);
    Set<ObjectAttribute> attributes = parseAttributes(request);

    GetObjectOptions options = GetObjectOptions.builder()
        .versionId(request.parameter("versionId").orElse(null))
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

  /**
   * The answer of the request, which carries only the attributes that it asked for: Amazon S3 leaves an
   * attribute that the {@code x-amz-object-attributes} header didn't name out of the answer entirely, so
   * that a client can tell what it asked for apart from what it didn't.
   *
   * <p>{@code Checksum} is never answered. LocalS3 stores no checksum of an object, which is what the
   * attribute is answered from, and Amazon S3 leaves the element out as well for an object that was stored
   * without one.
   *
   * @param request the request, which carries the paging of {@code ObjectParts}.
   * @param attributes the attributes that the request asked for.
   * @param object the object that was read.
   * @return the answer to write.
   */
  private GetObjectAttributesResult result(HttpRequest request, Set<ObjectAttribute> attributes,
                                           GetObjectAns object) {
    GetObjectAttributesResult.GetObjectAttributesResultBuilder result = GetObjectAttributesResult.builder();
    if (attributes.contains(ObjectAttribute.ETAG)) {
      result.etag(ResponseUtils.quoteEtag(object.getEtag()));
    }
    if (attributes.contains(ObjectAttribute.OBJECT_SIZE)) {
      result.objectSize(object.getSize());
    }
    if (attributes.contains(ObjectAttribute.STORAGE_CLASS)) {
      result.storageClass(StorageClass.STANDARD);
    }
    if (attributes.contains(ObjectAttribute.OBJECT_PARTS)) {
      result.objectParts(objectParts(request, object));
    }
    return result.build();
  }

  /**
   * A page of the parts that the object was uploaded in, paged by the {@code x-amz-max-parts} and
   * {@code x-amz-part-number-marker} headers the way {@code ListParts} is paged by its parameters.
   *
   * @param request the request, which carries the paging.
   * @param object the object that was read.
   * @return the page of parts; {@code null} if the object wasn't uploaded in parts, which leaves the
   *     element out of the answer.
   */
  private GetObjectAttributesResult.ObjectParts objectParts(HttpRequest request, GetObjectAns object) {
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
          .build());
    }

    // The parts are held in the order they were concatenated in, which is ascending part number.
    int lastPartNumber = parts.get(parts.size() - 1).getPartNumber();
    int lastOnPage = page.isEmpty() ? 0 : page.get(page.size() - 1).getPartNumber();
    return GetObjectAttributesResult.ObjectParts.builder()
        .partsCount(parts.size())
        .partNumberMarker(partNumberMarker)
        .nextPartNumberMarker(lastOnPage)
        .maxParts(maxParts)
        .truncated(!page.isEmpty() && lastOnPage < lastPartNumber)
        .parts(page)
        .build();
  }

  /**
   * The value of a header that carries a number.
   *
   * @param request the request.
   * @param headerName the name of the header.
   * @param defaultValue the value to answer with if the request doesn't carry the header.
   * @return the value of the header, or {@code defaultValue}.
   * @throws LocalS3InvalidArgumentException if the header doesn't carry a number, or carries a negative one.
   */
  private int intHeaderOrDefault(HttpRequest request, String headerName, int defaultValue) {
    String value = request.header(headerName).orElse(null);
    if (Objects.isNull(value)) {
      return defaultValue;
    }

    int parsed;
    try {
      parsed = Integer.parseInt(value.trim());
    } catch (NumberFormatException exception) {
      throw new LocalS3InvalidArgumentException(headerName, value, "Value must be a number.");
    }
    if (parsed < 0) {
      throw new LocalS3InvalidArgumentException(headerName, value, "Value must not be negative.");
    }
    return parsed;
  }

  private Set<ObjectAttribute> parseAttributes(HttpRequest request) {
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
    } catch (IllegalArgumentException exception) {
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
