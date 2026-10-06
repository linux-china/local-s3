package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpRequestHandler;
import com.robothy.netty.http.RouterHttpResponse;
import com.robothy.s3.core.model.answers.ListObjectsV2Ans;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.rest.assertions.RequestAssertions;
import com.robothy.s3.rest.model.response.CommonPrefix;
import com.robothy.s3.rest.model.response.ListBucketV2Result;
import com.robothy.s3.rest.service.ServiceFactory;
import com.robothy.s3.rest.utils.ResponseUtils;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpResponseStatus;

import tools.jackson.dataformat.xml.XmlMapper;

public class ListObjectsV2Controller implements RouterHttpRequestHandler {

    private final ObjectService objectService;

    private final XmlMapper xmlMapper;

    public ListObjectsV2Controller(ServiceFactory serviceFactory) {
        this.objectService = serviceFactory.getInstance(ObjectService.class);
        this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
    }

    @Override
    public void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception {
        String bucket = RequestAssertions.assertBucketNameProvided(request);
        String delimiter = RequestAssertions.assertDelimiterIsValid(request).orElse(null);
        String encodingType = RequestAssertions.assertEncodingTypeIsValid(request).orElse(null);
        int maxKeys = RequestAssertions.assertMaxKeysIsValid(request);
        String prefix = request.parameter("prefix").orElse(null);
        String continuationToken = request.parameter("continuation-token").orElse(null);
        String startAfter = request.parameter("start-after").orElse(null);
        boolean fetchOwner = request.parameter("fetch-owner").map(Boolean::valueOf).orElse(false);
        ListObjectsV2Ans listObjectsV2Ans = this.objectService.listObjectsV2(bucket, continuationToken, delimiter, encodingType, fetchOwner, maxKeys, prefix, startAfter);

        listObjectsV2Ans.setObjects(OptionalObjectAttributes.apply(request, listObjectsV2Ans.getObjects()));

        ListBucketV2Result listBucketV2Result = ListBucketV2Result.builder()
            .isTruncated(listObjectsV2Ans.isTruncated())
            .contents(listObjectsV2Ans.getObjects())
            .name(bucket)
            .prefix(listObjectsV2Ans.getPrefix())
            .delimiter(listObjectsV2Ans.getDelimiter())
            .maxKeys(listObjectsV2Ans.getMaxKeys())
            .commonPrefixes(listObjectsV2Ans.getCommonPrefixes().stream().map(CommonPrefix::new).toList())
            .encodingType(listObjectsV2Ans.getEncodingType())
            .keyCount(listObjectsV2Ans.getKeyCount())
            .continuationToken(continuationToken)
            .nextContinuationToken(listObjectsV2Ans.getNextContinuationToken().orElse(null))
            .startAfter(listObjectsV2Ans.getStartAfter())
            .build();

        String xml = xmlMapper.writeValueAsString(listBucketV2Result);
        // The Content-Length is set by LocalS3HttpMessageHandler from the bytes of the body. The length of the
        // XML would be the number of its characters, which a key that isn't ASCII makes differ from the number
        // of bytes it is encoded to.
        response.status(HttpResponseStatus.OK)
            .putHeader(HttpHeaderNames.CONTENT_TYPE.toString(), HttpHeaderValues.APPLICATION_XML)
            .write(xml);
        ResponseUtils.addCommonHeaders(response);
    }


}
