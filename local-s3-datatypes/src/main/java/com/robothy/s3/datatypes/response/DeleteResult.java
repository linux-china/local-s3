package com.robothy.s3.datatypes.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.StdSerializer;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import tools.jackson.dataformat.xml.ser.ToXmlGenerator;

/**
 * The answer of {@code DeleteObjects}.
 *
 * @param deletedList the {@link Deleted} objects and the {@link S3Error}s, in the order of the request.
 */
@JacksonXmlRootElement(localName = "DeleteResult")
@JsonSerialize(using = DeleteResult.DeleteResultSerializer.class)
public record DeleteResult(@JacksonXmlElementWrapper(useWrapping = false) List<Object> deletedList) {

  /**
   * An object that {@code DeleteObjects} deleted. Like Amazon S3, it names only what applies to the deletion:
   * a bucket that was never versioned answers the key alone, and the two delete marker fields belong to a
   * deletion that created one. LocalS3 wrote every field, {@code false} or empty, through 2.4.
   *
   * @param deleteMarker whether the deletion created a delete marker. Written only when it did, like Amazon S3
   *                     writes it.
   */
  @JacksonXmlRootElement(localName = "Deleted")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Deleted(
      @JsonInclude(JsonInclude.Include.NON_DEFAULT) @JacksonXmlProperty(localName = "DeleteMarker")
      boolean deleteMarker,
      @JacksonXmlProperty(localName = "DeleteMarkerVersionId") String deleteMarkerVersionId,
      @JacksonXmlProperty(localName = "Key") String key,
      @JacksonXmlProperty(localName = "VersionId") String versionId) {

  }

  static class DeleteResultSerializer extends StdSerializer<DeleteResult> {

    DeleteResultSerializer() {
      super(DeleteResult.class);
    }

    @Override
    public void serialize(DeleteResult deleteResult, JsonGenerator gen, SerializationContext provider) {

      if (gen instanceof ToXmlGenerator) {
        gen.writeStartObject();
        for (Object item : deleteResult.deletedList()) {
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
