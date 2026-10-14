package com.robothy.s3.datatypes.request;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.robothy.s3.datatypes.Tagging;
import java.util.List;
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

  @Test
  @SneakyThrows
  void readsTags() {
    XmlMapper xmlMapper = new XmlMapper();
    String xml = "<CreateBucketConfiguration xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">"
        + "<LocationConstraint>eu-west-1</LocationConstraint>"
        + "<Tags><Tag><Key>team</Key><Value>data</Value></Tag><Tag><Key>env</Key><Value>test</Value></Tag></Tags>"
        + "</CreateBucketConfiguration>";
    CreateBucketConfiguration deserialized = xmlMapper.readValue(xml, CreateBucketConfiguration.class);
    assertEquals("eu-west-1", deserialized.getLocationConstraint());
    assertEquals(List.of(new Tagging.Tag("team", "data"), new Tagging.Tag("env", "test")), deserialized.getTags());
  }
}