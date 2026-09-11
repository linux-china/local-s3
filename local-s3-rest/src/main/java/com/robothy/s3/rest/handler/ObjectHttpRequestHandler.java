package com.robothy.s3.rest.handler;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.rest.listener.ObjectEvent;
import com.robothy.s3.rest.listener.S3EventDispatcher;
import com.robothy.s3.rest.service.ServiceFactory;

public abstract class ObjectHttpRequestHandler implements HttpRequestHandler {
    protected ObjectService objectService;
    protected XmlMapper xmlMapper;
    protected S3EventDispatcher eventDispatcher;

    ObjectHttpRequestHandler(ServiceFactory serviceFactory) {
        this.objectService = serviceFactory.getInstance(ObjectService.class);
        this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
        if (serviceFactory.containsInstance(S3EventDispatcher.class)) {
            this.eventDispatcher = serviceFactory.getInstance(S3EventDispatcher.class);
        }
    }

    public void fireObjectEvent(ObjectEvent objectEvent) {
        if (eventDispatcher != null) {
            eventDispatcher.dispatch(objectEvent);
        }
    }
}