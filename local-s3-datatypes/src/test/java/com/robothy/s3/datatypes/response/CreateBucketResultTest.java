package com.robothy.s3.datatypes.response;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;
import tools.jackson.core.StreamWriteFeature;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.dataformat.xml.XmlMapper;
import tools.jackson.dataformat.xml.XmlWriteFeature;

class CreateBucketResultTest {

  @Test
  void test() {
    XmlMapper mapper = XmlMapper.builder().enable(XmlWriteFeature.WRITE_XML_DECLARATION).build();
    ObjectWriter writer = mapper.writer();
    ObjectReader reader = mapper.reader();

    CreateBucketResult createBucketResult = CreateBucketResult.builder()
        .bucketArn("abc")
        .build();

    String serialized = writer.with(StreamWriteFeature.WRITE_BIGDECIMAL_AS_PLAIN).writeValueAsString(createBucketResult);
    CreateBucketResult deserialized = reader.forType(CreateBucketResult.class).readValue(serialized);
    assertEquals(createBucketResult, deserialized);
  }

}