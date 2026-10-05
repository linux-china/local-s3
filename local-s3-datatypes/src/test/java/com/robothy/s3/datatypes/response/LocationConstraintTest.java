package com.robothy.s3.datatypes.response;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.xml.XmlMapper;

class LocationConstraintTest {

  @Test
  void serialization() throws Exception {
    XmlMapper xmlMapper = new XmlMapper();
    LocationConstraint locationConstraint = new LocationConstraint("eu-west-1");
    assertEquals("<LocationConstraint>eu-west-1</LocationConstraint>", xmlMapper.writeValueAsString(locationConstraint));
  }

}