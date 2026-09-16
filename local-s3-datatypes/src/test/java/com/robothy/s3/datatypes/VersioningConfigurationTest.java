package com.robothy.s3.datatypes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectReader;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.dataformat.xml.XmlMapper;

class VersioningConfigurationTest {

  @Test
  public void test() throws IOException {
    XmlMapper mapper = new XmlMapper();
    ObjectWriter writer = mapper.writer();
    ObjectReader reader = mapper.reader();

    VersioningConfiguration versioningConfiguration = VersioningConfiguration.builder()
        .status(VersioningConfiguration.Enabled)
        .build();

    String xmlStr = writer.writeValueAsString(versioningConfiguration);
    VersioningConfiguration deserialized =
        reader.forType(VersioningConfiguration.class).readValue(xmlStr);
    assertEquals(VersioningConfiguration.Enabled, deserialized.getStatus());
  }

}