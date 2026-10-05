package com.robothy.s3.datatypes.response;

import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.xml.XmlMapper;

class DeleteResultTest {

  @Test
  void testSerialization() {
    DeleteResult deleteResult = new DeleteResult(
        List.of(S3Error.builder().build(), new DeleteResult.Deleted(false, null, null, null)));

    XmlMapper xmlMapper = new XmlMapper();
    Assertions.assertDoesNotThrow(() -> xmlMapper.writerWithDefaultPrettyPrinter()
        .writeValueAsString(deleteResult));
  }

}