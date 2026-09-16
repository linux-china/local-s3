package com.robothy.s3.rest.model.response;

import static org.junit.jupiter.api.Assertions.*;
import com.robothy.s3.datatypes.Owner;
import com.robothy.s3.datatypes.response.DeleteMarkerEntry;
import com.robothy.s3.datatypes.response.ObjectVersion;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.dataformat.xml.XmlMapper;

class ListVersionsResultTest {

  @Test
  void serialization() throws JacksonException {
    ListVersionsResult listVersionsResult = ListVersionsResult.builder()
        .versions(Arrays.asList(ObjectVersion.builder().versionId("!23123").owner(Owner.DEFAULT_OWNER).build(),
            DeleteMarkerEntry.builder().key("a.txt").owner(Owner.DEFAULT_OWNER).build()))
        .commonPrefixes(List.of(new CommonPrefix("a/"), new CommonPrefix("b/")))
        .build();

    XmlMapper xmlMapper = new XmlMapper();
    String xml = xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(listVersionsResult);
//    System.out.println(xml);
  }

}