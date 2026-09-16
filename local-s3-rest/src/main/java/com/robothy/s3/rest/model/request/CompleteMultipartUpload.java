package com.robothy.s3.rest.model.request;

import java.util.List;
import lombok.Data;
import tools.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import tools.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

@Data
@JacksonXmlRootElement(localName = "CompleteMultipartUpload")
public class CompleteMultipartUpload {

  @JacksonXmlProperty(localName = "Part")
  @JacksonXmlElementWrapper(useWrapping = false)
  private List<CompletedPart> parts;

}
