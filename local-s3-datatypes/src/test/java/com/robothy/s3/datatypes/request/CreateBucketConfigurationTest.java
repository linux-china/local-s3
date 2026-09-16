package com.robothy.s3.datatypes.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.xml.XmlMapper;

class CreateBucketConfigurationTest {

  @Test
  @SneakyThrows
  void getLocationConstraint() {
    XmlMapper xmlMapper = new XmlMapper();
    CreateBucketConfiguration createBucketConfiguration = new CreateBucketConfiguration("Loc");
    String xml = xmlMapper.writeValueAsString(createBucketConfiguration);
    CreateBucketConfiguration deserialized = xmlMapper.readValue(xml, CreateBucketConfiguration.class);
    assertEquals(createBucketConfiguration, deserialized);
  }
}