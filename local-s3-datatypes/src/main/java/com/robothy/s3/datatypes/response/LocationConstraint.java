package com.robothy.s3.datatypes.response;

import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import tools.jackson.dataformat.xml.annotation.JacksonXmlText;

@JacksonXmlRootElement(localName = "LocationConstraint")
public record LocationConstraint(@JacksonXmlText String locationConstraint) {

}
