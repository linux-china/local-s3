package com.robothy.netty.router;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.netty.http.RouterHttpResponse;

public interface ExceptionHandler<T extends Throwable> {

  void handle(T e, RouterHttpRequest request, RouterHttpResponse response);

}
