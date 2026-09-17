package com.robothy.s3.datatypes;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * The legal hold of an object version.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ObjectLockLegalHold.html">ObjectLockLegalHold</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JacksonXmlRootElement(localName = "LegalHold")
public class ObjectLockLegalHold {

  public static final String ON = "ON";

  public static final String OFF = "OFF";

  @JsonProperty("Status")
  private String status;

}
