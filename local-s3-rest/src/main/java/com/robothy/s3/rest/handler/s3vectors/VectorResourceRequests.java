package com.robothy.s3.rest.handler.s3vectors;

import com.robothy.netty.http.HttpRequest;
import com.robothy.s3.core.model.internal.s3vectors.VectorResourceIdentifier;

/**
 * Reads the resource that a request of an S3 Vectors tagging operation addresses.
 */
public final class VectorResourceRequests {

  /**
   * The request parameter that the router sets to the decoded ARN of the path {@code /tags/{resourceArn}}.
   */
  public static final String RESOURCE_ARN_PARAMETER = "resourceArn";

  private VectorResourceRequests() {
  }

  static VectorResourceIdentifier resource(HttpRequest request) {
    return VectorResourceIdentifier.fromArn(request.parameter(RESOURCE_ARN_PARAMETER).orElse(null));
  }

}
