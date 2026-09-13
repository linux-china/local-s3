package com.robothy.s3.rest.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import com.robothy.netty.http.HttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import java.time.format.DateTimeFormatter;
import org.junit.jupiter.api.Test;

class ResponseUtilsTest {

  @Test
  void testRFC1123Date() {
    String date = "Thu, 7 Aug 2025 14:37:13 GMT";
    assertEquals("Thu, 07 Aug 2025 14:37:13 GMT",
        ResponseUtils.RFC_1123_DATE_TIME.format(DateTimeFormatter.RFC_1123_DATE_TIME.parse(date)));
  }

  @Test
  void addETagQuotesTheEntityTag() {
    HttpResponse response = mock(HttpResponse.class);

    ResponseUtils.addETag(response, "etag");

    verify(response).putHeader(HttpHeaderNames.ETAG.toString(), "\"etag\"");
  }

}