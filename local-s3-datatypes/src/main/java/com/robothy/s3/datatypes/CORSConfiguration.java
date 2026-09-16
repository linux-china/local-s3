package com.robothy.s3.datatypes;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * The cross-origin resource sharing (CORS) configuration of a bucket.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CORSConfiguration.html">CORSConfiguration</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JacksonXmlRootElement(localName = "CORSConfiguration")
public class CORSConfiguration {

  @JsonProperty("CORSRule")
  @JacksonXmlElementWrapper(useWrapping = false)
  private List<CORSRule> corsRules;

}
