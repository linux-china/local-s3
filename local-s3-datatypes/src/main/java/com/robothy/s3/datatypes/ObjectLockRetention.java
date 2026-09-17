package com.robothy.s3.datatypes;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * The retention of an object version.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ObjectLockRetention.html">ObjectLockRetention</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JacksonXmlRootElement(localName = "Retention")
public class ObjectLockRetention {

  @JsonProperty("Mode")
  private String mode;

  /**
   * The ISO 8601 date and time the version is retained until, e.g. {@code 2030-01-01T00:00:00.000Z}.
   */
  @JsonProperty("RetainUntilDate")
  private String retainUntilDate;

}
