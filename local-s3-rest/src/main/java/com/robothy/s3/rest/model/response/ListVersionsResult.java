package com.robothy.s3.rest.model.response;

import com.robothy.s3.datatypes.response.VersionItem;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import tools.jackson.dataformat.xml.ser.ToXmlGenerator;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@JacksonXmlRootElement(localName = "ListVersionsResult")
@JsonSerialize(using = ListVersionsResult.ListVersionsResultSerializer.class)
@Getter
public class ListVersionsResult {

  @JacksonXmlProperty(localName = "IsTruncated")
  private boolean isTruncated;

  @JacksonXmlProperty(localName = "KeyMarker")
  private String keyMarker;

  @JacksonXmlProperty(localName = "VersionIdMarker")
  private String versionIdMarker;

  @JacksonXmlProperty(localName = "NextKeyMarker")
  private String nextKeyMarker;

  @JacksonXmlProperty(localName = "NextVersionIdMarker")
  private String nextVersionIdMarker;

  private List<VersionItem> versions;

  @JacksonXmlProperty(localName = "Name")
  private String name;

  @JacksonXmlProperty(localName = "Prefix")
  private String prefix;

  @JacksonXmlProperty(localName = "Delimiter")
  private String delimiter;

  @JacksonXmlProperty(localName = "MaxKeys")
  private int maxKeys;

  @JacksonXmlProperty(localName = "CommonPrefixes")
  @JacksonXmlElementWrapper(useWrapping = false)
  private List<CommonPrefix> commonPrefixes;

  @JacksonXmlProperty(localName = "EncodingType")
  private String encodingType;

  static class ListVersionsResultSerializer extends StdSerializer<ListVersionsResult> {

    ListVersionsResultSerializer() {
      this(null);
    }

    ListVersionsResultSerializer(Class<ListVersionsResult> type) {
      super(type);
    }

    @SneakyThrows
    @Override
    public void serialize(ListVersionsResult value, JsonGenerator gen, SerializationContext provider) {
      if (gen instanceof ToXmlGenerator) {
        ToXmlGenerator xmlGenerator = (ToXmlGenerator) gen;
        xmlGenerator.writeStartObject();
        Field[] fields = ListVersionsResult.class.getDeclaredFields();
        for (Field field : fields) {
          field.setAccessible(true);
          java.lang.Object fieldValue = field.get(value);
          if (fieldValue instanceof List) {
            for (java.lang.Object version : (List) fieldValue) {
              JacksonXmlRootElement annotation = version.getClass().getAnnotation(JacksonXmlRootElement.class);
              Objects.requireNonNull(annotation, "Must add @JacksonXmlRootElement to " + version.getClass());
              xmlGenerator.writeName(annotation.localName());
              xmlGenerator.writePOJO(version);
            }
          } else {
            if ("$jacocoData".equals(field.getName())) {
              continue;
            }

            JacksonXmlProperty jacksonXmlProperty = field.getAnnotation(JacksonXmlProperty.class);
            Objects.requireNonNull(jacksonXmlProperty, "Must add @JacksonXmlProperty to " + value.getClass() + "#" + field.getName());
            xmlGenerator.writeName(jacksonXmlProperty.localName());
            xmlGenerator.writePOJO(fieldValue);
          }
        }
        xmlGenerator.writeEndObject();
      }
    }
  }

}
