package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.model.answers.ListObjectVersionsAns;
import com.robothy.s3.core.service.ListObjectVersionsService;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.core.util.S3ObjectUtils;
import com.robothy.s3.datatypes.response.VersionItem;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.model.response.CommonPrefix;
import com.robothy.s3.rest.model.response.ListVersionsResult;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;
import java.util.List;
import java.util.function.UnaryOperator;
import tools.jackson.dataformat.xml.XmlMapper;

class ListObjectVersionsController implements RouterHttpRequestHandler {

  private final ListObjectVersionsService listObjectVersionsService;

  private final XmlMapper xmlMapper;

  ListObjectVersionsController(ServiceFactory serviceFactory) {
    this.listObjectVersionsService = serviceFactory.getInstance(ObjectService.class);
    this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
  }

  @Override
  public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
    String bucketName = RequestAssertions.assertBucketNameProvided(request);
    String delimiter = RequestAssertions.assertDelimiterIsValid(request).orElse(null);
    String encodingType = RequestAssertions.assertEncodingTypeIsValid(request).orElse(null);
    String keyMarker = request.parameter("key-marker").orElse(null);
    int maxKeys = RequestAssertions.assertMaxKeysIsValid(request);
    String prefix = request.parameter("prefix").orElse(null);
    String versionIdMarker = request.parameter("version-id-marker").orElse(null);

    ListObjectVersionsAns ans =
        listObjectVersionsService.listObjectVersions(bucketName, delimiter, keyMarker, maxKeys, prefix, versionIdMarker);

    // With encoding-type=url, Amazon S3 encodes the keys, the prefixes, the delimiter and the key markers, which is
    // what a client decodes; the version IDs are never encoded.
    UnaryOperator<String> encode = "url".equalsIgnoreCase(encodingType)
        ? S3ObjectUtils::urlEncodeEscapeSlash : UnaryOperator.identity();
    List<VersionItem> versions = ans.getVersions().stream()
        .map(versionItem -> versionItem.withKey(encode.apply(versionItem.key())))
        .toList();

    ListVersionsResult result = ListVersionsResult.builder()
        .isTruncated(ans.getNextKeyMarker().isPresent())
        .keyMarker(encode.apply(keyMarker))
        .versionIdMarker(versionIdMarker)
        .nextKeyMarker(encode.apply(ans.getNextKeyMarker().orElse(null)))
        .nextVersionIdMarker(ans.getNextVersionIdMarker().orElse(null))
        .versions(versions)
        .name(bucketName)
        .prefix(encode.apply(prefix))
        .delimiter(encode.apply(delimiter))
        .maxKeys(maxKeys)
        .commonPrefixes(ans.getCommonPrefixes().stream().map(encode).map(CommonPrefix::new)
            .toList())
        .encodingType(encodingType)
        .build();

    response.status(HttpResponseStatus.OK)
        .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
        .write(xmlMapper.writeValueAsString(result));
    ResponseUtils.addServerHeader(response);
    ResponseUtils.addDateHeader(response);
    ResponseUtils.addAmzRequestId(response);
  }

}
