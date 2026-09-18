package com.robothy.s3.rest.utils;

import com.ctc.wstx.stax.WstxInputFactory;
import com.ctc.wstx.stax.WstxOutputFactory;
import java.nio.charset.StandardCharsets;
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
    return new S3XmlMapper(XmlMapper.builder(factory)
        .configureForJackson2()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES));
  }

  /**
   * A mapper that declares the namespace of Amazon S3 on the root element of the documents it writes; see
   * {@linkplain S3XmlNamespace}. Declaring it here rather than at the call sites means that every response
   * carries it, including the ones a controller writes with the mapper it holds.
   *
   * <p>Only a whole document is namespaced. A value written into a document that is already being
   * written, e.g. the {@code <Deleted>} and {@code <Error>} children that the serializer of
   * {@code DeleteResult} writes, doesn't go through these methods and inherits the declaration of its root.
   */
  static final class S3XmlMapper extends XmlMapper {

    S3XmlMapper(XmlMapper.Builder builder) {
      super(builder);
    }

    @Override
    public String writeValueAsString(Object value) {
      return S3XmlNamespace.declareOn(super.writeValueAsString(value));
    }

    @Override
    public byte[] writeValueAsBytes(Object value) {
      return writeValueAsString(value).getBytes(StandardCharsets.UTF_8);
    }

  }

  public static String toXml(Object object) {
    return xmlMapper.writeValueAsString(object);
  }

  public static String toPrettyXml(Object object) {
    // The writer of a mapper doesn't go through writeValueAsString, so the namespace is declared here too.
    return S3XmlNamespace.declareOn(xmlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(object));
  }

  public static  <T> T fromXml(String xml, Class<T> clazz) {
    return xmlMapper.readValue(xml, clazz);
  }

}
