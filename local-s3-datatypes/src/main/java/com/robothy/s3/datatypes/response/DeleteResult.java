package com.robothy.s3.datatypes.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.SneakyThrows;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import tools.jackson.dataformat.xml.ser.ToXmlGenerator;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JacksonXmlRootElement(localName = "DeleteResult")
@JsonSerialize(using = DeleteResult.DeleteResultSerializer.class)
public class DeleteResult {

  @JacksonXmlElementWrapper(useWrapping = false)
  private List<Object> deletedList;

  /**
   * An object that {@code DeleteObjects} deleted. Like Amazon S3, it names only what applies to the deletion:
   * a bucket that was never versioned answers the key alone, and the two delete marker fields belong to a
   * deletion that created one. LocalS3 wrote every field, {@code false} or empty, through 2.4.
   */
  @Setter
  @Getter
  @JacksonXmlRootElement(localName = "Deleted")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Deleted {

    /**
     * Whether the deletion created a delete marker. Written only when it did, like Amazon S3 writes it.
     */
    @JsonInclude(JsonInclude.Include.NON_DEFAULT)
    @JacksonXmlProperty(localName = "DeleteMarker")
    private boolean deleteMarker;

    @JacksonXmlProperty(localName = "DeleteMarkerVersionId")
    private String deleteMarkerVersionId;

    @JacksonXmlProperty(localName = "Key")
    private String key;

    @JacksonXmlProperty(localName = "VersionId")
    private String versionId;

  }

  static class DeleteResultSerializer extends StdSerializer<DeleteResult> {

    DeleteResultSerializer() {
      this(null);
    }

    protected DeleteResultSerializer(Class<DeleteResult> t) {
      super(t);
    }

    @SneakyThrows
    @Override
    public void serialize(DeleteResult deleteResult, JsonGenerator gen, SerializationContext provider) {

      if (gen instanceof ToXmlGenerator) {
        gen.writeStartObject();
        Field deletedListField = DeleteResult.class.getDeclaredField("deletedList");
        deletedListField.setAccessible(true);
        for (Object item : (List)deletedListField.get(deleteResult)) {
          JacksonXmlRootElement jacksonXmlRootElement = item.getClass().getDeclaredAnnotation(JacksonXmlRootElement.class);
          Objects.requireNonNull(jacksonXmlRootElement, "Must add @JacksonXmlRootElement to " + item.getClass());
          gen.writeName(jacksonXmlRootElement.localName());
          gen.writePOJO(item);
        }

        gen.writeEndObject();
      }

    }

  }

}
