package com.robothy.s3.rest.model.response;

import static org.junit.jupiter.api.Assertions.*;
import com.robothy.s3.datatypes.Owner;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.xml.XmlMapper;

class ListAllMyBucketsResultTest {

  @Test
  void testSerialization() throws JacksonException {
    ListAllMyBucketsResult listAllMyBucketsResult = new ListAllMyBucketsResult();
    listAllMyBucketsResult.setBuckets(List.of(new S3Bucket("bucket1", Instant.now()),
        new S3Bucket("bucket2", Instant.now())));
    listAllMyBucketsResult.setOwner(new Owner("LocalS3", "001"));
    XmlMapper xmlMapper = new XmlMapper();
    assertDoesNotThrow(() -> xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(listAllMyBucketsResult));
    //System.out.println(xml);
  }

}