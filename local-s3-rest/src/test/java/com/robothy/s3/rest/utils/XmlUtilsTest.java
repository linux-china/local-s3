package com.robothy.s3.rest.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.robothy.s3.datatypes.AccessControlPolicy;
import com.robothy.s3.datatypes.Grant;
import com.robothy.s3.datatypes.Grantee;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * The mapper of the XML documents of the service is configured like Jackson 2 configured a mapper, so that the
 * documents stay what they were before LocalS3 moved to Jackson 3.
 */
class XmlUtilsTest {

  @Test
  void writesTheElementsInTheOrderOfTheFieldsOfTheModel() {
    String xml = XmlUtils.toXml(new FieldOrder("z", "m", "a"));

    assertTrue(xml.indexOf("<Zeta>") < xml.indexOf("<Mu>"), xml);
    assertTrue(xml.indexOf("<Mu>") < xml.indexOf("<Alpha>"), xml);
  }

  @JacksonXmlRootElement(localName = "FieldOrder")
  record FieldOrder(@JsonProperty("Zeta") String zeta, @JsonProperty("Mu") String mu,
                    @JsonProperty("Alpha") String alpha) {
  }

  /**
   * Jackson 3 detects {@code xsi:type} by default and declares its namespace, which the {@code xmlns:xsi} attribute of
   * a grantee declares already; a document that declares it twice isn't well-formed.
   */
  @Test
  void declaresTheXsiNamespaceOfAGranteeOnce() {
    Grantee grantee = new Grantee();
    grantee.setId("123");
    grantee.setType("CanonicalUser");
    Grant grant = new Grant();
    grant.setGrantee(grantee);
    grant.setPermission("FULL_CONTROL");
    AccessControlPolicy policy = AccessControlPolicy.builder().grants(List.of(grant)).build();

    String xml = XmlUtils.toXml(policy);

    assertEquals(xml.indexOf("xmlns:xsi="), xml.lastIndexOf("xmlns:xsi="), xml);
    assertTrue(xml.contains("xsi:type=\"CanonicalUser\""), xml);
    assertEquals(policy, XmlUtils.fromXml(xml, AccessControlPolicy.class));
  }

}
