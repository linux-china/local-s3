package com.robothy.s3.datatypes;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A rule of a {@linkplain CORSConfiguration}: the origins, methods and headers of the cross-origin requests it allows.
 * Origins and headers may contain one {@code *} wildcard.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_CORSRule.html">CORSRule</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CORSRule {

  @JsonProperty("ID")
  private String id;

  @JsonProperty("AllowedHeader")
  @JacksonXmlElementWrapper(useWrapping = false)
  private List<String> allowedHeaders;

  @JsonProperty("AllowedMethod")
  @JacksonXmlElementWrapper(useWrapping = false)
  private List<String> allowedMethods;

  @JsonProperty("AllowedOrigin")
  @JacksonXmlElementWrapper(useWrapping = false)
  private List<String> allowedOrigins;

  @JsonProperty("ExposeHeader")
  @JacksonXmlElementWrapper(useWrapping = false)
  private List<String> exposeHeaders;

  @JsonProperty("MaxAgeSeconds")
  private Integer maxAgeSeconds;

}
