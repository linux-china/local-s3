package com.robothy.s3.datatypes.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonRootName;
import com.robothy.s3.datatypes.Tagging;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;


@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonRootName("CreateBucketConfiguration ")
public class CreateBucketConfiguration {

  @JsonProperty("LocationConstraint")
  private String locationConstraint;

  @JacksonXmlElementWrapper(localName = "Tags")
  @JsonProperty("Tag")
  private List<Tagging.Tag> tags;

  public CreateBucketConfiguration(String locationConstraint) {
    this.locationConstraint = locationConstraint;
  }

}
