package com.robothy.netty.http;

public interface RouterHttpRequestHandler {

  void handle(RouterHttpRequest request, RouterHttpResponse response) throws Exception;

}
