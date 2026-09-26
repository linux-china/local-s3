package com.robothy.s3.datatypes;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@AllArgsConstructor
@Builder
@Getter
@Setter
public class VersioningConfiguration {

  public static final String Enabled = "Enabled";

  public static final String Suspended = "Suspended";

  /**
   * The {@code MfaDelete} value of a configuration whose MFA delete is disabled.
   */
  public static final String Disabled = "Disabled";

  @JsonProperty("Status")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String status;

  /**
   * {@value #Enabled} or {@value #Disabled}; {@code null} if the configuration leaves it out.
   */
  @JsonProperty("MfaDelete")
  @JsonInclude(JsonInclude.Include.NON_NULL)
  private String mfaDelete;

}
