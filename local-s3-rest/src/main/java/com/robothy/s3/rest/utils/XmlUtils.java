package com.robothy.s3.rest.utils;

import com.ctc.wstx.stax.WstxInputFactory;
import com.ctc.wstx.stax.WstxOutputFactory;
import javax.xml.stream.XMLInputFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.dataformat.xml.XmlFactory;
import tools.jackson.dataformat.xml.XmlMapper;

public class XmlUtils {

  private static final XmlMapper xmlMapper = createXmlMapper();

  /**
   * Create a mapper of the XML documents of the Amazon S3 API. It reads no DTD and no external entity, so that a request
   * body can't make the service read a file or open a connection of its own, and ignores the elements that the model
   * doesn't know.
   *
   * <p>It is configured like Jackson 2 configured a mapper, e.g. so that a document keeps its elements in the order of
   * the fields of its model rather than sorted; {@code java.time} and {@code Optional} values are supported by
   * Jackson 3 itself.
   *
   * @return a new mapper.
   */
  public static XmlMapper createXmlMapper() {
    XMLInputFactory input = new WstxInputFactory();
    input.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, Boolean.FALSE);
    input.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
    input.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
    XmlFactory factory = XmlFactory.builderWithJackson2Defaults()
        .xmlInputFactory(input)
        .xmlOutputFactory(new WstxOutputFactory())
        .build();
    return XmlMapper.builder(factory)
        .configureForJackson2()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();
  }

  public static String toXml(Object object) {
    return xmlMapper.writeValueAsString(object);
  }

  public static String toPrettyXml(Object object) {
    return xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(object);
  }

  public static  <T> T fromXml(String xml, Class<T> clazz) {
    return xmlMapper.readValue(xml, clazz);
  }

}
