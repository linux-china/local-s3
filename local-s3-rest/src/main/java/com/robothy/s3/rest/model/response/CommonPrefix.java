package com.robothy.s3.rest.model.response;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.Getter;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@AllArgsConstructor
@NoArgsConstructor
@JacksonXmlRootElement(localName = "CommonPrefixes")
@EqualsAndHashCode
public class CommonPrefix {

  @JacksonXmlProperty(localName = "Prefix")
  private String prefix;

}
