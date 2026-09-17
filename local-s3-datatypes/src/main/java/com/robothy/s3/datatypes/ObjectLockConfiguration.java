package com.robothy.s3.datatypes;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * The object lock configuration of a bucket.
 *
 * @see <a href="https://docs.aws.amazon.com/AmazonS3/latest/API/API_ObjectLockConfiguration.html">ObjectLockConfiguration</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JacksonXmlRootElement(localName = "ObjectLockConfiguration")
public class ObjectLockConfiguration {

  /**
   * The only value of {@code ObjectLockEnabled}.
   */
  public static final String ENABLED = "Enabled";

  @JsonProperty("ObjectLockEnabled")
  private String objectLockEnabled;

  @JsonProperty("Rule")
  private Rule rule;

  /**
   * The rule of an object lock configuration.
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class Rule {

    @JsonProperty("DefaultRetention")
    private DefaultRetention defaultRetention;

  }

  /**
   * The retention that new object versions get by default.
   */
  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public static class DefaultRetention {

    @JsonProperty("Mode")
    private String mode;

    @JsonProperty("Days")
    private Integer days;

    @JsonProperty("Years")
    private Integer years;

  }

}
