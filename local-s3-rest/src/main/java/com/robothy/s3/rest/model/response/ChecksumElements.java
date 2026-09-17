package com.robothy.s3.rest.model.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.robothy.s3.core.model.internal.ObjectChecksum;
import com.robothy.s3.datatypes.enums.ChecksumType;
import java.util.Objects;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;

/**
 * The elements that an XML response answers a checksum with, e.g. {@code <ChecksumCRC32>} and
 * {@code <ChecksumType>}, of which only the ones of the algorithm of the checksum are written.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChecksumElements {

  @JacksonXmlProperty(localName = "ChecksumCRC32")
  private String checksumCRC32;

  @JacksonXmlProperty(localName = "ChecksumCRC32C")
  private String checksumCRC32C;

  @JacksonXmlProperty(localName = "ChecksumCRC64NVME")
  private String checksumCRC64NVME;

  @JacksonXmlProperty(localName = "ChecksumSHA1")
  private String checksumSHA1;

  @JacksonXmlProperty(localName = "ChecksumSHA256")
  private String checksumSHA256;

  @JacksonXmlProperty(localName = "ChecksumType")
  private ChecksumType checksumType;

  /**
   * Elements that carry a checksum, for a property that {@code @JsonUnwrapped} reads: Jackson reads an empty
   * {@linkplain ChecksumElements} for a response that carries none of the elements.
   *
   * @param elements the elements read.
   * @return the elements; {@code null} if none of them has a value.
   */
  public static ChecksumElements nullIfEmpty(ChecksumElements elements) {
    return Objects.isNull(elements) || elements.equals(new ChecksumElements()) ? null : elements;
  }

  /**
   * The elements of the checksum of an object, with its type.
   *
   * @param checksum the checksum; {@code null} for none.
   * @return the elements; {@code null} if there is no checksum, which leaves them all out.
   */
  public static ChecksumElements of(ObjectChecksum checksum) {
    ChecksumElements elements = valueOf(checksum);
    if (Objects.nonNull(elements)) {
      elements.setChecksumType(checksum.getType());
    }
    return elements;
  }

  /**
   * The elements of the checksum of a part, which are answered without a type.
   *
   * @param checksum the checksum; {@code null} for none.
   * @return the elements; {@code null} if there is no checksum.
   */
  public static ChecksumElements valueOf(ObjectChecksum checksum) {
    if (Objects.isNull(checksum) || Objects.isNull(checksum.getAlgorithm())) {
      return null;
    }
    ChecksumElements elements = new ChecksumElements();
    switch (checksum.getAlgorithm()) {
      case CRC32 -> elements.setChecksumCRC32(checksum.getValue());
      case CRC32C -> elements.setChecksumCRC32C(checksum.getValue());
      case CRC64NVME -> elements.setChecksumCRC64NVME(checksum.getValue());
      case SHA1 -> elements.setChecksumSHA1(checksum.getValue());
      case SHA256 -> elements.setChecksumSHA256(checksum.getValue());
    }
    return elements;
  }

}
