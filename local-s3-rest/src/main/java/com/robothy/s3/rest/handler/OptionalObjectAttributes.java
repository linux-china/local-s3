package com.robothy.s3.rest.handler;

import com.robothy.netty.http.RouterHttpRequest;
import com.robothy.s3.core.exception.LocalS3InvalidArgumentException;
import com.robothy.s3.datatypes.response.S3Object;
import com.robothy.s3.rest.constants.AmzHeaderNames;
import java.util.List;

/**
 * The {@code x-amz-optional-object-attributes} header of {@code ListObjects} and {@code ListObjectsV2}, which asks for
 * attributes that a listing leaves out otherwise. {@code RestoreStatus} is the only one Amazon S3 defines.
 */
final class OptionalObjectAttributes {

  static final String RESTORE_STATUS = "RestoreStatus";

  private OptionalObjectAttributes() {
  }

  /**
   * Leave the {@code RestoreStatus} out of the objects of a listing, unless the request asks for it.
   *
   * @param request the request of the listing.
   * @param objects the objects of the listing, whose restore status the service filled in.
   * @return the objects to answer.
   * @throws LocalS3InvalidArgumentException if the header names an attribute other than {@code RestoreStatus}.
   */
  static List<S3Object> apply(RouterHttpRequest request, List<S3Object> objects) {
    if (restoreStatusRequested(request)) {
      return objects;
    }
    return objects.stream().map(object -> object.withRestoreStatus(null)).toList();
  }

  static boolean restoreStatusRequested(RouterHttpRequest request) {
    String header = request.header(AmzHeaderNames.X_AMZ_OPTIONAL_OBJECT_ATTRIBUTES).orElse(null);
    if (header == null || header.isBlank()) {
      return false;
    }
    boolean requested = false;
    for (String attribute : header.split(",")) {
      String name = attribute.trim();
      if (!RESTORE_STATUS.equals(name)) {
        throw new LocalS3InvalidArgumentException(AmzHeaderNames.X_AMZ_OPTIONAL_OBJECT_ATTRIBUTES, header,
            "Invalid attribute name specified.");
      }
      requested = true;
    }
    return requested;
  }

}
