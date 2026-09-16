package com.robothy.s3.rest.model.response;

import com.robothy.s3.datatypes.response.S3Object;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.xml.XmlMapper;

class ListBucketResultTest {

  @Test
  void serialization() throws JacksonException {
    ListBucketResult listBucketResult = ListBucketResult.builder().isTruncated(false)
        .delimiter("/")
        .maxKeys(100)
        .encodingType("url")
        .prefix("dir")
        .contents(List.of(new S3Object(), new S3Object()))
        .commonPrefixes(List.of(new CommonPrefix("a/"), new CommonPrefix("b/")))
        .build();

    new XmlMapper().writerWithDefaultPrettyPrinter()
        .writeValueAsString(listBucketResult);
  }

}