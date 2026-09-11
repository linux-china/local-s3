package com.robothy.s3.rest.handler;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.s3.core.service.BucketService;
import com.robothy.s3.rest.listener.BucketEvent;
import com.robothy.s3.rest.listener.S3EventDispatcher;
import com.robothy.s3.rest.service.ServiceFactory;

public abstract class BucketHttpRequestHandler implements HttpRequestHandler {

    protected BucketService bucketService;
    protected S3EventDispatcher eventDispatcher;
    protected XmlMapper xmlMapper;

    public BucketHttpRequestHandler(ServiceFactory serviceFactory) {
        this.bucketService = serviceFactory.getInstance(BucketService.class);
        this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
        if (serviceFactory.containsInstance(S3EventDispatcher.class)) {
            this.eventDispatcher = serviceFactory.getInstance(S3EventDispatcher.class);
        }
    }

    protected void fireBucketEvent(BucketEvent bucketEvent) {
        if (eventDispatcher != null) {
            eventDispatcher.dispatch(bucketEvent);
        }
    }
}
