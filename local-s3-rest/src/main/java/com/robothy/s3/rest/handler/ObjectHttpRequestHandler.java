package com.robothy.s3.rest.handler;

import com.robothy.netty.http.HttpRequestHandler;
import com.robothy.s3.core.service.ObjectService;
import com.robothy.s3.rest.service.ServiceFactory;
import tools.jackson.dataformat.xml.XmlMapper;

public abstract class ObjectHttpRequestHandler implements HttpRequestHandler {
    protected ObjectService objectService;
    protected XmlMapper xmlMapper;

    ObjectHttpRequestHandler(ServiceFactory serviceFactory) {
        this.objectService = serviceFactory.getInstance(ObjectService.class);
        this.xmlMapper = serviceFactory.getInstance(XmlMapper.class);
    }
}
