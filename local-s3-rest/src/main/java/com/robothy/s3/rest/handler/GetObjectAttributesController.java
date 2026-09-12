package com.robothy.s3.rest.handler;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.netty.http.HttpRequest;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.netty.http.HttpResponse;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.core.model.answers.GetObjectAns;
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
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handle request of <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObjectAttributes.html">GetObjectAttributes</a>.
 */
class GetObjectAttributesController implements HttpRequestHandler {

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
    parseAttributes(request);

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
      GetObjectAttributesResult result = GetObjectAttributesResult.builder()
          .etag(object.getEtag())
          .objectSize(object.getSize())
          .storageClass(StorageClass.STANDARD)
          .build();
      response.status(HttpResponseStatus.OK)
          .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
          .write(xmlMapper.writeValueAsString(result));
    }

    ResponseUtils.addDateHeader(response);
    ResponseUtils.addAmzRequestId(response);
    ResponseUtils.addServerHeader(response);
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
