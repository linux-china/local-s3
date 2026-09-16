package com.robothy.s3.datatypes.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import tools.jackson.dataformat.xml.annotation.JacksonXmlText;

@AllArgsConstructor
@NoArgsConstructor
@Builder
@JacksonXmlRootElement(localName = "LocationConstraint")
public class LocationConstraint {

  @JacksonXmlText
  private String locationConstraint;

}
