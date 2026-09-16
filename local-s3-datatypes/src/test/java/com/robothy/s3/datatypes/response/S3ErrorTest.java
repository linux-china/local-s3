package com.robothy.s3.datatypes.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.dataformat.xml.XmlMapper;

class S3ErrorTest {

  @Test
  public void test() throws JacksonException {
    XmlMapper xmlMapper = XmlMapper.builder().enable(StreamReadFeature.IGNORE_UNDEFINED).build();

    S3Error error = S3Error.builder()
        .code("InternalServerError")
        .message("Internal Server Error")
        .requestId("123")
        .argumentName("Name")
        .argumentValue("Robothy")
        .bucketName("my-bucket")
        .versionId("123")
        .key("a.txt")
        .build();

    String xml = xmlMapper.writeValueAsString(error);
    S3Error deserialized = xmlMapper.readValue(xml, S3Error.class);
    assertEquals(error, deserialized);
  }

}