package com.robothy.netty.http;

@FunctionalInterface
public interface RouterHttpRequestHandler {

  void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception;

}
